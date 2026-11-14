package dev.javahost.core.storage

import java.io.File
import java.io.IOException

/**
 * Pure JVM codec for Minecraft's `server.properties`.
 *
 * The file is a plain Java-properties document, but users (and other tools)
 * edit it by hand, so a naive "parse to map, write map back" loses comments and
 * ordering. [format] therefore takes the *previous raw text* and keeps every
 * comment, blank line, unknown line and even the original formatting of values
 * that did not change. Only lines whose key is modified or removed are
 * rewritten, and genuinely new keys are appended at the end.
 *
 * Only the `key=value` shape that Minecraft produces is interpreted; colon
 * separators are accepted as a fallback. No Android framework types are used,
 * which is what lets the codec be unit tested on a plain JVM.
 */
object ServerPropertiesCodec {

    /* ------------------------------------------------------------------ */
    /* Known keys (Minecraft)                                             */
    /* ------------------------------------------------------------------ */

    const val SERVER_PORT = "server-port"
    const val MOTD = "motd"
    const val MAX_PLAYERS = "max-players"
    const val ONLINE_MODE = "online-mode"
    const val VIEW_DISTANCE = "view-distance"
    const val SIMULATION_DISTANCE = "simulation-distance"
    const val DIFFICULTY = "difficulty"
    const val GAMEMODE = "gamemode"
    const val LEVEL_NAME = "level-name"
    const val PVP = "pvp"
    const val WHITE_LIST = "white-list"
    const val ENABLE_COMMAND_BLOCK = "enable-command-block"

    val KNOWN_KEYS: List<String> = listOf(
        MOTD,
        SERVER_PORT,
        MAX_PLAYERS,
        ONLINE_MODE,
        VIEW_DISTANCE,
        SIMULATION_DISTANCE,
        DIFFICULTY,
        GAMEMODE,
        LEVEL_NAME,
        PVP,
        WHITE_LIST,
        ENABLE_COMMAND_BLOCK
    )

    val DIFFICULTIES: Set<String> = setOf("peaceful", "easy", "normal", "hard")
    val GAMEMODES: Set<String> = setOf("survival", "creative", "adventure", "spectator")

    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    /** Guards [read] against a runaway/hostile file; server.properties is tiny. */
    const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

    /* ------------------------------------------------------------------ */
    /* Lead-agreed public API: parse / format / validatePort              */
    /* ------------------------------------------------------------------ */

    /**
     * Parses the document into an ordered map (last duplicate wins, matching
     * `java.util.Properties`). Comments and blank lines are ignored here; they
     * survive because [format] also receives the raw text.
     */
    fun parse(raw: String): Map<String, String> {
        val lines = splitLines(raw).first
        val result = LinkedHashMap<String, String>()
        for (line in lines) {
            val entry = parseEntry(line) ?: continue
            result[entry.key] = entry.value
        }
        return result
    }

    /**
     * Formats [entries] on top of [previousRaw], preserving comments, order and
     * unknown lines. Keys absent from [entries] are removed from the output.
     * Keys that did not change keep their original raw line (including any odd
     * spacing) so a read/write cycle is byte-stable.
     */
    fun format(entries: Map<String, String>, previousRaw: String = ""): String {
        val (previousLines, hadTrailingNewline) = splitLines(previousRaw)
        val output = ArrayList<String>(previousLines.size + entries.size)
        val emitted = HashSet<String>()

        for (line in previousLines) {
            val entry = parseEntry(line)
            if (entry == null) {
                // Comments, blank lines and anything we do not understand are
                // preserved verbatim.
                output.add(line)
                continue
            }
            val newValue = entries[entry.key] ?: continue // key removed by the caller
            if (!emitted.add(entry.key)) continue // duplicate key: keep the first
            output.add(
                if (newValue == entry.value) line else "${entry.key}=${formatValue(newValue)}"
            )
        }

        for ((key, value) in entries) {
            if (!isWritableKey(key) || !emitted.add(key)) continue
            output.add("$key=${formatValue(value)}")
        }

        if (output.isEmpty()) return ""
        val trailing = hadTrailingNewline || previousRaw.isEmpty()
        return output.joinToString("\n") + if (trailing) "\n" else ""
    }

    /** Parses a port string, returning null unless it is a valid TCP/UDP port. */
    fun validatePort(value: String): Int? {
        val port = value.trim().toIntOrNull() ?: return null
        return port.takeIf { it in MIN_PORT..MAX_PORT }
    }

    /* ------------------------------------------------------------------ */
    /* Typed helpers                                                      */
    /* ------------------------------------------------------------------ */

    fun getInt(entries: Map<String, String>, key: String): Int? =
        entries[key]?.trim()?.toIntOrNull()

    fun parseInt(raw: String?): Int? = raw?.trim()?.toIntOrNull()

    /** Minecraft accepts only `true`/`false`, case-insensitively. */
    fun parseBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun getBoolean(entries: Map<String, String>, key: String, default: Boolean = false): Boolean =
        parseBoolean(entries[key]) ?: default

    fun port(entries: Map<String, String>): Int? = entries[SERVER_PORT]?.let(::validatePort)

    /** Sets `server-port`, throwing [IllegalArgumentException] for out-of-range values. */
    fun setPort(entries: MutableMap<String, String>, port: Int) {
        requireKey(SERVER_PORT)
        entries[SERVER_PORT] = validatePort(port).toString()
    }

    fun putInt(entries: MutableMap<String, String>, key: String, value: Int) {
        requireKey(key)
        entries[key] = value.toString()
    }

    fun putBoolean(entries: MutableMap<String, String>, key: String, value: Boolean) {
        requireKey(key)
        entries[key] = value.toString()
    }

    fun putString(entries: MutableMap<String, String>, key: String, value: String) {
        requireKey(key)
        entries[key] = value.trim()
    }

    fun motd(entries: Map<String, String>): String? = entries[MOTD]

    /** MOTD is a single line: CR/LF become spaces so the file cannot be corrupted. */
    fun setMotd(entries: MutableMap<String, String>, value: String) {
        putString(entries, MOTD, value.replace('\r', ' ').replace('\n', ' '))
    }

    fun levelName(entries: Map<String, String>): String? = entries[LEVEL_NAME]

    /** Level names are single path segments; separators and line breaks are stripped. */
    fun setLevelName(entries: MutableMap<String, String>, value: String) {
        val cleaned = value
            .replace('/', '_')
            .replace('\\', '_')
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        require(cleaned.isNotEmpty()) { "Level name cannot be empty" }
        putString(entries, LEVEL_NAME, cleaned)
    }

    fun difficulty(entries: Map<String, String>): String? =
        entries[DIFFICULTY]?.trim()?.lowercase()?.takeIf { it in DIFFICULTIES }

    fun setDifficulty(entries: MutableMap<String, String>, value: String) {
        val normalized = value.trim().lowercase()
        require(normalized in DIFFICULTIES) {
            "Invalid difficulty \"$value\"; expected one of ${DIFFICULTIES.joinToString()}"
        }
        putString(entries, DIFFICULTY, normalized)
    }

    fun gamemode(entries: Map<String, String>): String? =
        entries[GAMEMODE]?.trim()?.lowercase()?.takeIf { it in GAMEMODES }

    fun setGamemode(entries: MutableMap<String, String>, value: String) {
        val normalized = value.trim().lowercase()
        require(normalized in GAMEMODES) {
            "Invalid gamemode \"$value\"; expected one of ${GAMEMODES.joinToString()}"
        }
        putString(entries, GAMEMODE, normalized)
    }

    /* ------------------------------------------------------------------ */
    /* File I/O helpers (still pure JVM)                                  */
    /* ------------------------------------------------------------------ */

    /** Reads a `server.properties` file; returns an empty map when it does not exist. */
    @Throws(IOException::class)
    fun read(file: File, maxBytes: Long = MAX_FILE_BYTES): Map<String, String> = parse(readRaw(file, maxBytes))

    @Throws(IOException::class)
    fun readRaw(file: File, maxBytes: Long = MAX_FILE_BYTES): String {
        if (!file.exists()) return ""
        if (!file.isFile) throw IOException("${file.name} is not a regular file")
        val length = file.length()
        if (length > maxBytes) {
            throw IOException("${file.name} is unexpectedly large ($length bytes, limit $maxBytes)")
        }
        return file.readText(Charsets.UTF_8)
    }

    /**
     * Rewrites [file] from [entries] while preserving untouched lines. The write
     * is atomic (temp file + rename) so a crash cannot truncate the properties.
     */
    @Throws(IOException::class)
    fun write(file: File, entries: Map<String, String>) {
        val previous = if (file.isFile) readRaw(file) else ""
        writeText(file, entries, previous)
    }

    @Throws(IOException::class)
    fun writeText(file: File, entries: Map<String, String>, previousRaw: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create directory ${parent.absolutePath}")
        }
        val text = format(entries, previousRaw)
        val temp = File(parent, ".${file.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            if (!temp.delete()) temp.deleteOnExit()
        }
    }

    fun isValidPort(value: Int): Boolean = value in MIN_PORT..MAX_PORT

    /** Validating variant for callers that already have an [Int]. */
    fun validatePort(port: Int): Int {
        require(port in MIN_PORT..MAX_PORT) {
            "Server port must be between $MIN_PORT and $MAX_PORT (got $port)"
        }
        return port
    }

    /* ------------------------------------------------------------------ */
    /* Internals                                                          */
    /* ------------------------------------------------------------------ */

    private data class EntryLine(val key: String, val value: String)

    private fun splitLines(raw: String): Pair<List<String>, Boolean> {
        val text = raw.removePrefix("\uFEFF")
        if (text.isEmpty()) return emptyList<String>() to false
        val hadTrailingNewline = text.endsWith('\n')
        val body = if (hadTrailingNewline) text.dropLast(1) else text
        val lines = body.split('\n').map { line ->
            if (line.endsWith('\r')) line.dropLast(1) else line
        }
        return lines to hadTrailingNewline
    }

    private fun parseEntry(line: String): EntryLine? {
        val trimmedStart = line.trimStart()
        if (trimmedStart.isEmpty()) return null
        if (trimmedStart.startsWith("#") || trimmedStart.startsWith("!")) return null
        val equals = line.indexOf('=')
        val colon = line.indexOf(':')
        val separator = when {
            equals >= 0 && (colon < 0 || equals < colon) -> equals
            colon >= 0 -> colon
            else -> return null
        }
        val key = line.substring(0, separator).trim()
        if (key.isEmpty()) return null
        val value = line.substring(separator + 1).trim()
        return EntryLine(key, value)
    }

    private fun formatValue(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').trim()

    private fun isWritableKey(key: String): Boolean =
        key.isNotBlank() &&
            !key.contains('=') &&
            !key.contains(':') &&
            !key.contains('\n') &&
            !key.contains('\r')

    private fun requireKey(key: String) {
        require(isWritableKey(key)) { "Invalid server property key: \"$key\"" }
    }
}
