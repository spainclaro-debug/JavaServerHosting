package dev.javahost.core.net

import java.io.File

/**
 * Locates and edits Geyser's generated `config.yml` so the Bedrock LAN bridge
 * can own UDP 19132 while Geyser listens on 19133.
 *
 * Geyser generates one `config.yml` per platform plugin directory
 * (`plugins/Geyser-Spigot/`, `plugins/Geyser-Standalone/`, ...). The Bedrock
 * listen port lives under the top-level `bedrock:` mapping as `port:` in modern
 * configs; older Geyser builds used a top-level `bedrock-port:` key instead.
 *
 * Edits are deliberately minimal and byte-preserving: only the digits of the
 * port value are replaced, so comments, ordering, indentation, inline comments
 * and CRLF line endings all survive. Writes go through a temporary file in the
 * same directory followed by a rename, so a crash cannot leave a truncated
 * `config.yml` behind. Nothing here throws: a missing, unreadable or unexpected
 * file yields [PatchOutcome.changed] = `false` with an explanatory message.
 *
 * Pure JVM (`java.io.File` only) so it is unit-testable without Android.
 */
object BedrockGeyserConfig {

    /** File name Geyser generates inside its plugin directory. */
    private const val CONFIG_FILE_NAME = "config.yml"

    /** Guard against a runaway/hostile file; a Geyser config is a few dozen KiB. */
    private const val MAX_FILE_BYTES: Long = 1L * 1024 * 1024

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    /** Geyser 2.12+ transport that keeps both RakNet (19133) and NetherNet (19134) open. */
    private const val TRANSPORT_BOTH = "both"

    /** `bedrock.transport` key; only patched when Geyser generated it. */
    private const val KEY_TRANSPORT = "transport"

    /** `bedrock.webrtc-port` key; only patched when Geyser generated it. */
    private const val KEY_WEBRTC_PORT = "webrtc-port"

    /** Matches a modern top-level `bedrock:` mapping header (optionally with a comment). */
    private val BEDROCK_HEADER = Regex("""^bedrock[ \t]*:[ \t]*(#.*)?""")

    /** Matches a nested `port:` line with its numeric value; group 1 is the digits. */
    private val NESTED_PORT_LINE = Regex("""^[ \t]*port[ \t]*:[ \t]*([0-9]+)(.*)""")

    /** Matches a legacy top-level `bedrock-port:` line; group 1 is indent, group 2 the digits. */
    private val LEGACY_PORT_LINE = Regex("""^([ \t]*)bedrock-port[ \t]*:[ \t]*([0-9]+)(.*)""")

    /**
     * First `config.yml` inside a directory under `plugins` whose name contains
     * "geyser" (case-insensitive), or `null` when there is none.
     *
     * Directory entries are considered in case-insensitive name order so the
     * result is deterministic when several Geyser flavours are installed.
     */
    fun findConfigFile(pluginsDir: File): File? = runCatching {
        if (!pluginsDir.isDirectory) return@runCatching null
        val children = pluginsDir.listFiles()?.toList().orEmpty()
        children
            .asSequence()
            .filter { child -> runCatching { child.isDirectory }.getOrDefault(false) }
            .filter { child -> child.name.lowercase().contains("geyser") }
            .sortedBy { child -> child.name.lowercase() }
            .map { child -> File(child, CONFIG_FILE_NAME) }
            .firstOrNull { candidate -> runCatching { candidate.isFile }.getOrDefault(false) }
    }.getOrNull()

    /**
     * The current Bedrock port from [file]: the first `port:` nested under a
     * top-level `bedrock:` mapping, or the legacy top-level `bedrock-port:`.
     *
     * Returns `null` when the file is missing/unreadable, contains neither
     * location, or carries an unusable (non-numeric / out-of-range) value.
     */
    fun readBedrockPort(file: File): Int? = runCatching {
        val text = readTextOrNull(file) ?: return@runCatching null
        val body = text.removePrefix(BOM)
        locatePort(body.split('\n'))?.port
    }.getOrNull()

    /** Outcome of [patchToPort]; [originalPort] is the port found before the edit. */
    data class PatchOutcome(
        val changed: Boolean,
        val originalPort: Int?,
        val message: String
    )

    /**
     * Outcome of [prepareForBridge].
     *
     * [originalPort] is only recorded when the Bedrock port value actually
     * changed, so a config that already pointed at the bridge port keeps the
     * sidecar/history untouched. [transportPatched] and [webrtcPortPatched]
     * report whether those optional keys were rewritten in this call.
     */
    data class BridgePatchOutcome(
        val changed: Boolean,
        val originalPort: Int?,
        val transportPatched: Boolean,
        val webrtcPortPatched: Boolean,
        val message: String
    )

    /**
     * Rewrites the Bedrock port value to [port] in place, preserving every other
     * byte (comments, ordering, indentation, inline comments, CRLF) and writing
     * atomically through a temporary file in the same directory.
     *
     * Handles both `bedrock:` -> indented `port:` and the legacy top-level
     * `bedrock-port:`. Returns [PatchOutcome.changed] = `false` (with an
     * explanatory message) when the file is missing/unreadable, no Bedrock port
     * location exists, or the port already equals [port]. Never throws.
     */
    fun patchToPort(file: File, port: Int): PatchOutcome = runCatching {
        if (port !in MIN_PORT..MAX_PORT) {
            return@runCatching PatchOutcome(
                changed = false,
                originalPort = null,
                message = "Invalid Bedrock port $port (expected $MIN_PORT-$MAX_PORT)"
            )
        }
        val raw = readTextOrNull(file)
            ?: return@runCatching PatchOutcome(
                changed = false,
                originalPort = null,
                message = "Geyser config not found or unreadable: ${displayPath(file)}"
            )
        val bom = if (raw.startsWith(BOM)) BOM else ""
        val body = raw.removePrefix(BOM)
        val lines = body.split('\n')
        val location = locatePort(lines)
            ?: return@runCatching PatchOutcome(
                changed = false,
                originalPort = null,
                message = "No Bedrock port found in ${displayPath(file)}"
            )
        if (location.port == port) {
            return@runCatching PatchOutcome(
                changed = false,
                originalPort = location.port,
                message = "Bedrock port is already $port in ${displayPath(file)}"
            )
        }
        val line = lines[location.lineIndex]
        val patchedLine = buildString(line.length + 8) {
            append(line, 0, location.valueStart)
            append(port)
            append(line, location.valueEnd, line.length)
        }
        val patchedLines = lines.toMutableList()
        patchedLines[location.lineIndex] = patchedLine
        val patchedBody = bom + patchedLines.joinToString("\n")
        if (!writeAtomically(file, patchedBody)) {
            return@runCatching PatchOutcome(
                changed = false,
                originalPort = location.port,
                message = "Could not write ${displayPath(file)}"
            )
        }
        return@runCatching PatchOutcome(
            changed = true,
            originalPort = location.port,
            message = "Bedrock port ${location.port} -> $port in ${displayPath(file)}"
        )
    }.getOrElse { throwable ->
        PatchOutcome(
            changed = false,
            originalPort = null,
            message = "Could not patch Geyser config: " +
                (throwable.message ?: throwable.javaClass.simpleName)
        )
    }

    /**
     * Prepares Geyser's config for the Bedrock LAN bridge in a single atomic edit:
     *
     *  - moves `bedrock.port` to [bedrockPort] (the bridge needs 19132 free);
     *  - when `bedrock.transport` exists, forces it to `both` (Geyser 2.12+
     *    defaults to `nethernet`, which does not open the RakNet UDP port the
     *    bridge relays to);
     *  - when `bedrock.webrtc-port` exists, moves it to [webrtcPort] so `both`
     *    gets distinct RakNet and NetherNet UDP ports;
     *  - the legacy top-level `bedrock-port` is handled like [patchToPort].
     *
     * Only the scalar values are replaced: indentation, comments, inline
     * comments, CRLF and every other byte are preserved, and the file is written
     * atomically. Missing optional keys are not created. Idempotent: when
     * everything already matches, [BridgePatchOutcome.changed] is `false`.
     * Never throws.
     */
    fun prepareForBridge(
        file: File,
        bedrockPort: Int,
        webrtcPort: Int
    ): BridgePatchOutcome = runCatching {
        if (bedrockPort !in MIN_PORT..MAX_PORT || webrtcPort !in MIN_PORT..MAX_PORT) {
            return@runCatching BridgePatchOutcome(
                changed = false,
                originalPort = null,
                transportPatched = false,
                webrtcPortPatched = false,
                message = "Invalid Bedrock bridge ports $bedrockPort/$webrtcPort " +
                    "(expected $MIN_PORT-$MAX_PORT)"
            )
        }
        val raw = readTextOrNull(file)
            ?: return@runCatching BridgePatchOutcome(
                changed = false,
                originalPort = null,
                transportPatched = false,
                webrtcPortPatched = false,
                message = "Geyser config not found or unreadable: ${displayPath(file)}"
            )
        val bom = if (raw.startsWith(BOM)) BOM else ""
        val body = raw.removePrefix(BOM)
        val lines = body.split('\n')
        val portLocation = locatePort(lines)
            ?: return@runCatching BridgePatchOutcome(
                changed = false,
                originalPort = null,
                transportPatched = false,
                webrtcPortPatched = false,
                message = "No Bedrock port found in ${displayPath(file)}"
            )

        val edits = ArrayList<ScalarEdit>()
        val recordedOriginalPort = portLocation.port.takeIf { it != bedrockPort }
        if (recordedOriginalPort != null) {
            edits.add(
                ScalarEdit(
                    lineIndex = portLocation.lineIndex,
                    valueStart = portLocation.valueStart,
                    valueEnd = portLocation.valueEnd,
                    replacement = bedrockPort.toString()
                )
            )
        }

        var transportPatched = false
        var webrtcPortPatched = false
        val block = locateBedrockBlock(lines)
        if (block != null) {
            val transport = locateNestedScalar(lines, block, KEY_TRANSPORT)
            if (transport != null && !transport.value.unquoted().equals(TRANSPORT_BOTH, ignoreCase = true)) {
                edits.add(
                    ScalarEdit(
                        lineIndex = transport.lineIndex,
                        valueStart = transport.valueStart,
                        valueEnd = transport.valueEnd,
                        replacement = TRANSPORT_BOTH
                    )
                )
                transportPatched = true
            }
            val webrtc = locateNestedScalar(lines, block, KEY_WEBRTC_PORT)
            if (webrtc != null) {
                val current = webrtc.value.unquoted().toIntOrNull()
                if (current != webrtcPort) {
                    edits.add(
                        ScalarEdit(
                            lineIndex = webrtc.lineIndex,
                            valueStart = webrtc.valueStart,
                            valueEnd = webrtc.valueEnd,
                            replacement = webrtcPort.toString()
                        )
                    )
                    webrtcPortPatched = true
                }
            }
        }

        if (edits.isEmpty()) {
            return@runCatching BridgePatchOutcome(
                changed = false,
                originalPort = null,
                transportPatched = false,
                webrtcPortPatched = false,
                message = "Geyser config is already prepared for the Bedrock bridge in " +
                    displayPath(file)
            )
        }

        val patchedBody = bom + applyScalarEdits(lines, edits).joinToString("\n")
        if (!writeAtomically(file, patchedBody)) {
            return@runCatching BridgePatchOutcome(
                changed = false,
                originalPort = recordedOriginalPort,
                transportPatched = false,
                webrtcPortPatched = false,
                message = "Could not write ${displayPath(file)}"
            )
        }

        val changes = ArrayList<String>(3)
        if (recordedOriginalPort != null) {
            changes.add("bedrock.port $recordedOriginalPort -> $bedrockPort")
        }
        if (transportPatched) changes.add("bedrock.transport -> $TRANSPORT_BOTH")
        if (webrtcPortPatched) changes.add("bedrock.webrtc-port -> $webrtcPort")
        return@runCatching BridgePatchOutcome(
            changed = true,
            originalPort = recordedOriginalPort,
            transportPatched = transportPatched,
            webrtcPortPatched = webrtcPortPatched,
            message = "Patched ${changes.joinToString(", ")} in ${displayPath(file)}"
        )
    }.getOrElse { throwable ->
        BridgePatchOutcome(
            changed = false,
            originalPort = null,
            transportPatched = false,
            webrtcPortPatched = false,
            message = "Could not prepare Geyser config: " +
                (throwable.message ?: throwable.javaClass.simpleName)
        )
    }

    /**
     * Restores [originalPort] after [patchToPort], but only while the current
     * value is still [expectedCurrentPort]; a config the user (or Geyser) has
     * changed since is left alone.
     *
     * Returns `true` when the file now holds [originalPort] (including the
     * already-restored case), `false` when it was left untouched. Never throws.
     */
    fun restorePort(file: File, expectedCurrentPort: Int, originalPort: Int): Boolean = runCatching {
        if (originalPort !in MIN_PORT..MAX_PORT) return@runCatching false
        val current = readBedrockPort(file) ?: return@runCatching false
        if (current != expectedCurrentPort) return@runCatching false
        if (current == originalPort) return@runCatching true
        patchToPort(file, originalPort).changed
    }.getOrDefault(false)

    /**
     * Short human-readable note for the console, e.g.
     * `plugins/Geyser-Spigot/config.yml (bedrock.port=19133)` or
     * `no Geyser config found`. Never throws.
     */
    fun describe(file: File?): String = runCatching {
        if (file == null || !file.isFile) return@runCatching "no Geyser config found"
        val path = displayPath(file)
        val port = readBedrockPort(file)
        if (port == null) "$path (no bedrock.port)" else "$path (bedrock.port=$port)"
    }.getOrDefault("no Geyser config found")

    /* ------------------------------------------------------------------ */
    /* Internals                                                          */
    /* ------------------------------------------------------------------ */

    /** One editable Bedrock port occurrence: where its digits sit and what they say. */
    private data class PortLocation(
        val lineIndex: Int,
        val valueStart: Int,
        val valueEnd: Int,
        val port: Int
    )

    /** Locates a modern `bedrock:` -> `port:` first, then the legacy top-level key. */
    private fun locatePort(lines: List<String>): PortLocation? =
        locateModernPort(lines) ?: locateLegacyPort(lines)

    /**
     * The first `port:` that is indented deeper than a top-level `bedrock:`
     * mapping header. Blank lines and comment lines do not end the mapping; the
     * first non-blank line at the header's own indentation does.
     */
    private fun locateModernPort(lines: List<String>): PortLocation? {
        var inBedrock = false
        for (index in lines.indices) {
            val line = lines[index].removeSuffix("\r")
            val trimmed = line.trim()
            if (!inBedrock) {
                if (leadingWhitespace(line) == 0 && BEDROCK_HEADER.matches(trimmed)) {
                    inBedrock = true
                }
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            // A line at (or left of) the mapping header's indentation ends the block.
            if (leadingWhitespace(line) == 0) return null
            val match = NESTED_PORT_LINE.matchEntire(line) ?: continue
            val valueGroup = match.groups[1] ?: continue
            val port = valueGroup.value.toIntOrNull() ?: continue
            if (port !in MIN_PORT..MAX_PORT) continue
            return PortLocation(
                lineIndex = index,
                valueStart = valueGroup.range.first,
                valueEnd = valueGroup.range.last + 1,
                port = port
            )
        }
        return null
    }

    /** The first top-level legacy `bedrock-port:` line, if any. */
    private fun locateLegacyPort(lines: List<String>): PortLocation? {
        for (index in lines.indices) {
            val line = lines[index].removeSuffix("\r")
            val match = LEGACY_PORT_LINE.matchEntire(line) ?: continue
            val indentGroup = match.groups[1] ?: continue
            if (indentGroup.value.isNotEmpty()) continue
            val valueGroup = match.groups[2] ?: continue
            val port = valueGroup.value.toIntOrNull() ?: continue
            if (port !in MIN_PORT..MAX_PORT) continue
            return PortLocation(
                lineIndex = index,
                valueStart = valueGroup.range.first,
                valueEnd = valueGroup.range.last + 1,
                port = port
            )
        }
        return null
    }

    /** Line range of the first top-level `bedrock:` mapping: header excluded, exclusive end. */
    private data class BlockRange(val startExclusive: Int, val endExclusive: Int)

    /** One scalar value inside the top-level `bedrock:` block. */
    private data class ScalarLocation(
        val lineIndex: Int,
        val valueStart: Int,
        val valueEnd: Int,
        val value: String
    )

    /** One scalar replacement on one line; offsets refer to that line's original text. */
    private data class ScalarEdit(
        val lineIndex: Int,
        val valueStart: Int,
        val valueEnd: Int,
        val replacement: String
    )

    /** The line range of the first top-level `bedrock:` mapping, if any. */
    private fun locateBedrockBlock(lines: List<String>): BlockRange? {
        for (index in lines.indices) {
            val line = lines[index].removeSuffix("\r")
            if (leadingWhitespace(line) != 0) continue
            if (!BEDROCK_HEADER.matches(line.trim())) continue
            var end = lines.size
            for (next in index + 1 until lines.size) {
                val candidate = lines[next].removeSuffix("\r")
                val candidateTrimmed = candidate.trim()
                if (candidateTrimmed.isEmpty() || candidateTrimmed.startsWith("#")) continue
                if (leadingWhitespace(candidate) == 0) {
                    end = next
                    break
                }
            }
            return BlockRange(index + 1, end)
        }
        return null
    }

    /** The first `key: value` scalar line inside [block], or null when the key is absent. */
    private fun locateNestedScalar(
        lines: List<String>,
        block: BlockRange,
        key: String
    ): ScalarLocation? {
        val pattern = keyScalarRegex(key)
        for (index in block.startExclusive until block.endExclusive) {
            val line = lines[index].removeSuffix("\r")
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val match = pattern.matchEntire(line) ?: continue
            val valueGroup = match.groups[1] ?: continue
            return ScalarLocation(
                lineIndex = index,
                valueStart = valueGroup.range.first,
                valueEnd = valueGroup.range.last + 1,
                value = valueGroup.value
            )
        }
        return null
    }

    /** Matches `key: scalar` with the scalar in group 1; the rest of the line is group 2. */
    private fun keyScalarRegex(key: String): Regex =
        Regex("^[ \\t]*" + Regex.escape(key) + "[ \\t]*:[ \\t]*([^ \\t#]+)(.*)")

    /**
     * Applies [edits] to a copy of [lines]. Each line is rebuilt from its original
     * text; multiple edits on one line are applied right-to-left so earlier
     * offsets stay valid.
     */
    private fun applyScalarEdits(lines: List<String>, edits: List<ScalarEdit>): List<String> {
        val patched = lines.toMutableList()
        for ((lineIndex, lineEdits) in edits.groupBy { it.lineIndex }) {
            var line = patched[lineIndex]
            for (edit in lineEdits.sortedByDescending { it.valueStart }) {
                line = line.substring(0, edit.valueStart) +
                    edit.replacement +
                    line.substring(edit.valueEnd)
            }
            patched[lineIndex] = line
        }
        return patched
    }

    /** Strips one pair of matching YAML quotes from a scalar value, if present. */
    private fun String.unquoted(): String {
        if (length >= 2 && (first() == '"' || first() == '\'') && last() == first()) {
            return substring(1, length - 1)
        }
        return this
    }

    /** Reads the file as UTF-8, or null when it is missing, not a file, too large or unreadable. */
    private fun readTextOrNull(file: File): String? {
        return try {
            if (!file.isFile) return null
            val length = file.length()
            if (length > MAX_FILE_BYTES) return null
            file.readText(Charsets.UTF_8)
        } catch (throwable: Throwable) {
            null
        }
    }

    /**
     * Writes [text] through a unique temporary file in the target's directory and
     * renames it into place. Falls back to a direct overwrite on filesystems where
     * the rename is refused, and always cleans up the temporary file.
     */
    private fun writeAtomically(file: File, text: String): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory) return false
        val temp = try {
            File.createTempFile(".${file.name}.", ".tmp", parent)
        } catch (throwable: Throwable) {
            return false
        }
        return try {
            temp.writeText(text, Charsets.UTF_8)
            if (temp.renameTo(file)) {
                true
            } else {
                file.writeText(text, Charsets.UTF_8)
                true
            }
        } catch (throwable: Throwable) {
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** `plugins/Geyser-Spigot/config.yml` when the grandparents are known, else the plain path. */
    private fun displayPath(file: File): String {
        val parent = file.parentFile
        val grandParent = parent?.parentFile
        val path = if (parent != null && grandParent != null) {
            "${grandParent.name}/${parent.name}/${file.name}"
        } else {
            file.path
        }
        return path.replace('\\', '/')
    }

    /** Number of leading spaces/tabs in [line]. */
    private fun leadingWhitespace(line: String): Int {
        var count = 0
        while (count < line.length && (line[count] == ' ' || line[count] == '\t')) count++
        return count
    }

    /** UTF-8 byte-order mark; preserved as-is by [patchToPort]. */
    private const val BOM = "\uFEFF"
}
