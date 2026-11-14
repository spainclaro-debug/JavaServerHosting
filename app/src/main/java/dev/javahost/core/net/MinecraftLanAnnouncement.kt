package dev.javahost.core.net

/**
 * Builds the payload of Minecraft's LAN discovery protocol.
 *
 * A Minecraft client discovers an "Open to LAN" world by listening on the UDP
 * multicast group [MULTICAST_GROUP] at port [DEFAULT_PORT] for a message shaped
 * like `[MOTD]<motd>[/MOTD][AD]<port>[/AD]`. Dedicated servers never announce
 * themselves, so JavaHost sends this payload itself while a server is running.
 *
 * All strings are sanitised before they are put on the wire: the MOTD is
 * user-controlled text, and unescaped `[`/`]` or line breaks would corrupt the
 * packet (or let a crafted MOTD close the tag early and inject a fake `[AD]`).
 */
object MinecraftLanAnnouncement {

    /** IPv4 multicast group Minecraft clients listen on for LAN games. */
    const val MULTICAST_GROUP = "224.0.2.60"

    /** UDP port Minecraft clients listen on for LAN games. */
    const val DEFAULT_PORT = 4445

    /** How often the announcer repeats the payload; clients only need one packet, this is for lossy Wi-Fi. */
    const val INTERVAL_MS = 1500L

    /** Longest MOTD kept, in UTF-16 code units, so a malicious MOTD cannot bloat the packet. */
    private const val MAX_MOTD_LENGTH = 100

    /** Fallback MOTD used when both the MOTD and its fallback are blank. */
    private const val DEFAULT_MOTD = "Minecraft server"

    /**
     * Formats the discovery message for [motd] and [port], e.g.
     * `[MOTD]Refuge[/MOTD][AD]25565[/AD]`.
     *
     * The MOTD is passed through [sanitizeMotd] first, so callers may hand over
     * the raw user-provided text; a blank MOTD becomes "Minecraft server".
     *
     * @throws IllegalArgumentException when [port] is outside 1..65535.
     */
    fun message(motd: String, port: Int): String {
        require(port in 1..65535) { "Minecraft LAN port must be in 1..65535, was $port" }
        return "[MOTD]${sanitizeMotd(motd, DEFAULT_MOTD)}[/MOTD][AD]$port[/AD]"
    }

    /**
     * UTF-8 bytes of the discovery message, ready for a `DatagramPacket`.
     *
     * Equivalent to `message(sanitizeMotd(motd, "Minecraft server"), port)`.
     *
     * @throws IllegalArgumentException when [port] is outside 1..65535.
     */
    fun payload(motd: String, port: Int): ByteArray =
        message(sanitizeMotd(motd, DEFAULT_MOTD), port).toByteArray(Charsets.UTF_8)

    /**
     * Returns a protocol-safe MOTD.
     *
     * CR/LF/tab/control characters and any other whitespace are collapsed into
     * single spaces, `[` becomes `(` and `]` becomes `)` so the MOTD cannot close
     * the `[MOTD]` tag, the result is trimmed and capped at 100 characters.
     * When the result is blank, [fallback] (sanitised the same way) is used, and
     * when that is blank too, "Minecraft server" is returned. Never returns blank.
     */
    fun sanitizeMotd(raw: String?, fallback: String): String {
        val motd = cleanMotd(raw)
        if (motd.isNotEmpty()) return motd
        val safeFallback = cleanMotd(fallback)
        return safeFallback.ifEmpty { DEFAULT_MOTD }
    }

    /**
     * Normalises one MOTD candidate: strips control characters, escapes brackets,
     * collapses whitespace, trims and caps the length. Returns "" for null/blank input.
     */
    private fun cleanMotd(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val collapsed = StringBuilder(raw.length)
        for (char in raw) {
            val normalised = when {
                char == '[' -> '('
                char == ']' -> ')'
                char.isISOControl() || char.isWhitespace() -> ' '
                else -> char
            }
            if (normalised == ' ') {
                if (collapsed.isNotEmpty() && collapsed.last() != ' ') collapsed.append(' ')
            } else {
                collapsed.append(normalised)
            }
        }
        val trimmed = collapsed.toString().trim()
        if (trimmed.length <= MAX_MOTD_LENGTH) return trimmed
        val cut = trimmed.substring(0, MAX_MOTD_LENGTH)
        // Do not leave a dangling space when the cut landed inside a whitespace run.
        return cut.trim()
    }
}
