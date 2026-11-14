package dev.javahost.core.modrinth

import dev.javahost.core.api.DownloadException
import dev.javahost.core.api.Downloader
import dev.javahost.core.model.ModTarget
import dev.javahost.core.model.ModrinthProject
import dev.javahost.core.model.ModrinthVersion
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.storage.InstanceStore
import dev.javahost.core.storage.Paths
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Readable, user-facing Modrinth failure. [message] is safe to show in the UI. */
class ModrinthException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Modrinth API v2 client for plugins/mods (Modrinth is the only supported
 * third-party source; CurseForge is intentionally absent).
 *
 * Networking is delegated to the shared [Downloader]; the app wires it up as
 * `OkHttpDownloader(userAgent = BuildConfig.MODRINTH_USER_AGENT)`, which sends
 * the descriptive `User-Agent` header that Modrinth v2 requires on every
 * request. This class itself has no Android or UI dependencies and returns
 * [Result]s whose failure message is always human-readable (no stack traces).
 *
 * The constructor taking resolver lambdas exists so the whole download/install
 * pipeline can be unit-tested on a plain JVM without an Android `Context`.
 */
class ModrinthRepository(
    private val downloader: Downloader,
    private val instanceResolver: (String) -> ServerInstance?,
    private val installDirResolver: (String, ModTarget) -> File,
    private val apiBaseUrl: String = ModrinthApi.API_BASE,
    private val searchLimit: Int = ModrinthApi.SEARCH_LIMIT
) {

    /** Production constructor used by AppContainer. */
    constructor(downloader: Downloader, instances: InstanceStore, paths: Paths) : this(
        downloader = downloader,
        instanceResolver = { instanceId -> instances.get(instanceId) },
        installDirResolver = { instanceId, target ->
            when (target) {
                ModTarget.PLUGINS -> paths.pluginsDir(instanceId)
                ModTarget.MODS -> paths.modsDir(instanceId)
            }
        }
    )

    /**
     * Searches Modrinth. [gameVersion] and [loader] are optional filters; the
     * facets always include the project type (`mod` or `plugin`).
     */
    suspend fun search(
        target: ModTarget,
        query: String,
        gameVersion: String?,
        loader: String?,
        offset: Int = 0
    ): Result<List<ModrinthProject>> = resultOf {
        val url = ModrinthApi.buildSearchUrl(
            apiBase = apiBaseUrl,
            target = target,
            query = query,
            gameVersion = gameVersion,
            loader = loader,
            offset = offset,
            limit = searchLimit
        )
        ModrinthApi.parseSearchResults(fetchJson(url, "search Modrinth"))
    }

    /**
     * Lists downloadable versions of a project, newest first as returned by the
     * API. Returns an empty list when the project has no matching version.
     */
    suspend fun listVersions(
        projectId: String,
        gameVersion: String?,
        loader: String?
    ): Result<List<ModrinthVersion>> = resultOf {
        if (projectId.isBlank()) {
            throw ModrinthException("No Modrinth project was selected.")
        }
        val url = ModrinthApi.buildVersionsUrl(apiBaseUrl, projectId, gameVersion, loader)
        val versions = ModrinthApi.parseVersions(fetchJson(url, "load versions from Modrinth"))
        versions
    }

    /**
     * Downloads the primary file of [version] into the instance's
     * `plugins/` or `mods/` folder, verifying the Modrinth SHA-1 and the
     * advertised size first. The final rename is atomic, so a failed or
     * cancelled download never leaves a half-written JAR that a server could
     * load.
     */
    suspend fun install(
        instanceId: String,
        version: ModrinthVersion,
        target: ModTarget
    ): Result<File> = resultOf {
        val instance = instanceResolver(instanceId)
            ?: throw ModrinthException(
                "That server no longer exists. Reopen it from the server list and try again."
            )
        val file = ModrinthApi.primaryFile(version)
            ?: throw ModrinthException(
                "\"${version.name}\" has no downloadable file on Modrinth. Pick another version."
            )
        if (file.url.isBlank()) {
            throw ModrinthException(
                "Modrinth did not provide a download URL for \"${file.filename}\". Try another version."
            )
        }
        val safeName = ModrinthApi.sanitizeFilename(file.filename)
        val directory = installDirResolver(instance.id, target)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw ModrinthException(
                "Couldn't create the ${folderName(target)} folder for \"${instance.name}\". " +
                    "Check the app's storage and try again."
            )
        }
        val staging = File(directory, ".$safeName.${UUID.randomUUID()}.part")
        try {
            val expectedSha1 = file.sha1?.trim()?.takeIf { it.isNotEmpty() }
            val download = downloader.download(
                url = file.url,
                destination = staging,
                expectedSha1 = expectedSha1
            )
            if (file.size > 0 && download.bytes != file.size) {
                throw ModrinthException(
                    "The downloaded file \"$safeName\" is ${download.bytes} bytes but Modrinth " +
                        "lists ${file.size}. The download may be corrupted; try again."
                )
            }
            val destination = File(directory, safeName)
            moveIntoPlace(staging, destination)
            destination
        } catch (e: ModrinthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } finally {
            // OkHttpDownloader uses <dest>.part internally; clean both up.
            staging.delete()
            File(staging.path + ".part").delete()
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private suspend fun fetchJson(url: String, action: String): String = try {
        downloader.fetchText(url, ModrinthApi.MAX_RESPONSE_BYTES)
    } catch (e: DownloadException) {
        throw ModrinthException(describeDownloadError(action, e), e)
    } catch (e: CancellationException) {
        throw e
    }

    /**
     * Moves a fully downloaded/verified staging file into its final name.
     * Tries an atomic rename first; if the filesystem cannot do that (or the
     * rename crosses a mount), falls back to copy-then-rename inside the target
     * directory so the final file still appears atomically.
     */
    private fun moveIntoPlace(staging: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                staging.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // Fall through to the copy-based path below.
        } catch (_: IOException) {
            // Fall through to the copy-based path below.
        }
        val sibling = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.part")
        try {
            staging.copyTo(sibling, overwrite = true)
            try {
                Files.move(
                    sibling.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: IOException) {
                sibling.copyTo(destination, overwrite = true)
            }
        } finally {
            sibling.delete()
            staging.delete()
        }
    }

    private fun describeDownloadError(action: String, error: DownloadException): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("429") ->
                "Modrinth is rate-limiting requests right now. Wait a minute and try again."
            raw.contains("404") ->
                "The requested Modrinth project or version wasn't found; it may have been removed."
            raw.contains("403") ->
                "Modrinth rejected the request (HTTP 403). Update JavaHost and try again."
            raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
                "Modrinth didn't respond in time. Check your connection and try again."
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("Failed to connect", ignoreCase = true) ||
                raw.contains("connect", ignoreCase = true) ->
                "Couldn't connect to Modrinth. Check your internet connection and try again."
            else -> {
                val detail = raw.take(240).trim()
                if (detail.isEmpty()) {
                    "Couldn't $action. Check your internet connection and try again."
                } else {
                    "Couldn't $action: $detail"
                }
            }
        }
    }

    private fun folderName(target: ModTarget): String = when (target) {
        ModTarget.PLUGINS -> "plugins"
        ModTarget.MODS -> "mods"
    }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModrinthException) {
        Result.failure(e)
    } catch (e: ModrinthPayloadException) {
        Result.failure(
            ModrinthException(
                e.message ?: "Modrinth returned an unexpected response. Try again in a moment.",
                e
            )
        )
    } catch (e: DownloadException) {
        Result.failure(ModrinthException(describeDownloadError("contact Modrinth", e), e))
    } catch (e: IOException) {
        Result.failure(
            ModrinthException(
                "Couldn't reach Modrinth. Check your internet connection and try again.",
                e
            )
        )
    } catch (e: Exception) {
        Result.failure(
            ModrinthException(
                "Modrinth request failed (${e.message ?: e::class.simpleName ?: "unknown error"}).",
                e
            )
        )
    }
}
