package dev.javahost.core.modrinth

import dev.javahost.core.model.ModTarget
import dev.javahost.core.model.ModrinthFile
import dev.javahost.core.model.ModrinthProject
import dev.javahost.core.model.ModrinthVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** Raised when Modrinth answers with data we cannot parse. Message is user-facing. */
class ModrinthPayloadException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Pure(ish) Modrinth API v2 helpers: URL construction, JSON parsing and file-name
 * sanitising. Kept free of Android types *and* of networking so all of it can be
 * unit-tested synchronously. [ModrinthRepository] owns the suspend/network side.
 *
 * API docs: https://docs.modrinth.com/api/
 */
object ModrinthApi {

    const val API_BASE: String = "https://api.modrinth.com/v2"

    /** Modrinth allows 1..100; the UI grid is designed around 20 per page. */
    const val SEARCH_LIMIT: Int = 20

    /** Upper bound accepted from a single API response. */
    const val MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024

    /**
     * Loader ids Modrinth uses as search "categories". Used to split the flat
     * `categories` array of a search hit into [ModrinthProject.loaders].
     */
    val KNOWN_LOADERS: Set<String> = setOf(
        "bukkit", "bungeecord", "canvas", "datapack", "fabric", "folia", "forge",
        "geyser", "iris", "liteloader", "minecraft", "modloader", "neoforge",
        "optifine", "paper", "purpur", "quilt", "rift", "spigot", "sponge",
        "vanilla", "velocity", "waterfall"
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Project type facet value for the UI's plugin/mod switch. */
    fun projectTypeFor(target: ModTarget): String = when (target) {
        ModTarget.PLUGINS -> "plugin"
        ModTarget.MODS -> "mod"
    }

    /**
     * Builds `GET /search` with correct facets. For plugins the project type is
     * `plugin`; loaders are matched through `categories:<loader>` and game
     * versions through `versions:<version>`.
     */
    fun buildSearchUrl(
        apiBase: String,
        target: ModTarget,
        query: String,
        gameVersion: String?,
        loader: String?,
        offset: Int,
        limit: Int = SEARCH_LIMIT
    ): String {
        val facets = buildFacets(target, gameVersion, loader)
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        return buildString {
            append(apiBase.trimEnd('/'))
            append("/search?query=")
            append(encodeQueryValue(query))
            append("&facets=")
            append(encodeQueryValue(facets))
            append("&index=relevance")
            append("&limit=")
            append(safeLimit)
            append("&offset=")
            append(safeOffset)
        }
    }

    /** Facets JSON, e.g. `[["project_type:mod"],["versions:1.20.4"],["categories:fabric"]]`. */
    fun buildFacets(target: ModTarget, gameVersion: String?, loader: String?): String {
        val facets = buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive("project_type:${projectTypeFor(target)}")) })
            val version = gameVersion?.trim()
            if (!version.isNullOrEmpty()) {
                add(buildJsonArray { add(JsonPrimitive("versions:$version")) })
            }
            val loaderName = loader?.trim()?.lowercase()
            if (!loaderName.isNullOrEmpty()) {
                add(buildJsonArray { add(JsonPrimitive("categories:$loaderName")) })
            }
        }
        return facets.toString()
    }

    /** Builds `GET /project/{id}/version`, optionally filtered by game version/loader. */
    fun buildVersionsUrl(
        apiBase: String,
        projectId: String,
        gameVersion: String?,
        loader: String?
    ): String {
        val base = apiBase.trimEnd('/') + "/project/" + encodePathSegment(projectId) + "/version"
        val params = ArrayList<String>(2)
        val version = gameVersion?.trim()
        if (!version.isNullOrEmpty()) {
            params += "game_versions=" + encodeQueryValue(buildJsonArray {
                add(JsonPrimitive(version))
            }.toString())
        }
        val loaderName = loader?.trim()?.lowercase()
        if (!loaderName.isNullOrEmpty()) {
            params += "loaders=" + encodeQueryValue(buildJsonArray {
                add(JsonPrimitive(loaderName))
            }.toString())
        }
        return if (params.isEmpty()) base else base + "?" + params.joinToString("&")
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    /** Parses a `/search` response. Unknown extra fields are ignored. */
    fun parseSearchResults(text: String): List<ModrinthProject> {
        val root = parseElement(text, "search")
        if (root !is JsonObject) {
            throw ModrinthPayloadException(
                "Modrinth returned an unexpected search response. Try again in a moment."
            )
        }
        val hits = root["hits"]
        if (hits !is JsonArray) {
            throw ModrinthPayloadException(
                "Modrinth returned a search response without results. Try again in a moment."
            )
        }
        return hits.mapNotNull { element -> (element as? JsonObject)?.let(::parseSearchHit) }
    }

    private fun parseSearchHit(hit: JsonObject): ModrinthProject? {
        val id = hit.stringOrNull("project_id") ?: hit.stringOrNull("id") ?: return null
        val slug = hit.stringOrNull("slug") ?: id
        val title = hit.stringOrNull("title") ?: slug
        val description = hit.stringOrNull("description").orEmpty()
        val categories = hit.stringList("categories")
        val loaders = categories.filter { it.lowercase() in KNOWN_LOADERS }
        return ModrinthProject(
            id = id,
            slug = slug,
            title = title,
            description = description,
            iconUrl = hit.stringOrNull("icon_url"),
            downloads = hit.longOrZero("downloads"),
            projectType = hit.stringOrNull("project_type") ?: "mod",
            loaders = loaders,
            gameVersions = hit.stringList("versions"),
            author = hit.stringOrNull("author")
        )
    }

    /** Parses `GET /project/{id}/version` (a JSON array of version objects). */
    fun parseVersions(text: String): List<ModrinthVersion> {
        val root = parseElement(text, "versions")
        if (root !is JsonArray) {
            throw ModrinthPayloadException(
                "Modrinth returned an unexpected version list. Try again in a moment."
            )
        }
        return root.mapNotNull { element -> (element as? JsonObject)?.let(::parseVersion) }
    }

    private fun parseVersion(version: JsonObject): ModrinthVersion? {
        val id = version.stringOrNull("id") ?: return null
        val versionNumber = version.stringOrNull("version_number").orEmpty()
        val name = version.stringOrNull("name") ?: versionNumber.ifEmpty { id }
        val files = (version["files"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let(::parseFile)
        }
        return ModrinthVersion(
            id = id,
            projectId = version.stringOrNull("project_id").orEmpty(),
            name = name,
            versionNumber = versionNumber,
            gameVersions = version.stringList("game_versions"),
            loaders = version.stringList("loaders"),
            files = files,
            datePublished = version.stringOrNull("date_published").orEmpty()
        )
    }

    private fun parseFile(file: JsonObject): ModrinthFile? {
        val url = file.stringOrNull("url") ?: return null
        val filename = file.stringOrNull("filename") ?: return null
        val sha1 = (file["hashes"] as? JsonObject)?.stringOrNull("sha1")
        return ModrinthFile(
            filename = filename,
            url = url,
            size = file.longOrZero("size"),
            sha1 = sha1?.takeIf { it.isNotBlank() },
            primary = booleanOrFalse(file, "primary")
        )
    }

    // ---------------------------------------------------------------------
    // File selection / naming
    // ---------------------------------------------------------------------

    /** The file the author marked primary; falls back to the first `.jar` then the first file. */
    fun primaryFile(version: ModrinthVersion): ModrinthFile? =
        version.files.firstOrNull { it.primary }
            ?: version.files.firstOrNull { it.filename.endsWith(".jar", ignoreCase = true) }
            ?: version.files.firstOrNull()

    /**
     * Makes an untrusted Modrinth filename safe for an app-private directory:
     * strips directories, control characters and traversal, keeps a `.jar`
     * extension and bounds the length. Never returns a path separator.
     */
    fun sanitizeFilename(rawName: String): String {
        val base = rawName
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
        val cleaned = buildString(base.length) {
            for (ch in base) {
                when {
                    ch.isLetterOrDigit() -> append(ch)
                    ch == '.' || ch == '_' || ch == '-' || ch == '+' || ch == '@' -> append(ch)
                    ch == ' ' -> append('_')
                    else -> append('_')
                }
            }
        }.trimStart('.')
            .ifEmpty { DEFAULT_FILENAME }
        val withExtension = if (cleaned.endsWith(".jar", ignoreCase = true)) {
            cleaned
        } else {
            cleaned + ".jar"
        }
        val bounded = if (withExtension.length <= MAX_FILENAME_LENGTH) {
            withExtension
        } else {
            // Keep the ".jar" suffix so mod loaders can still find the file.
            val suffix = if (withExtension.endsWith(".jar", ignoreCase = true)) ".jar" else ""
            withExtension.take(MAX_FILENAME_LENGTH - suffix.length) + suffix
        }
        return if (bounded == ".jar" || bounded == "_.jar") DEFAULT_FILENAME else bounded
    }

    // ---------------------------------------------------------------------
    // JSON helpers (kotlinx-serialization-json, no custom data classes)
    // ---------------------------------------------------------------------

    private fun parseElement(text: String, what: String) = try {
        json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw ModrinthPayloadException(
            "Modrinth returned an unreadable $what response (it may be rate-limiting or " +
                "temporarily down). Try again in a moment.",
            e
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] as? JsonPrimitive ?: return null
        // JsonNull is a JsonPrimitive whose content is the literal string "null";
        // it must not be mistaken for an actual string/number value.
        if (element is JsonNull) return null
        return element.content
    }

    private fun JsonObject.longOrZero(key: String): Long {
        val element = this[key] as? JsonPrimitive ?: return 0L
        return element.longOrNull ?: 0L
    }

    private fun booleanOrFalse(obj: JsonObject, key: String): Boolean {
        val element = obj[key] as? JsonPrimitive ?: return false
        return element.content.equals("true", ignoreCase = true)
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")

    private const val DEFAULT_FILENAME = "download.jar"
    private const val MAX_FILENAME_LENGTH = 120
}
