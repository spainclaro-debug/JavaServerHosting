package dev.javahost.core.server

import java.io.File

/**
 * Counts established TCP connections on a port by reading `/proc/net/tcp`
 * (and tcp6). This gives generic Java servers a meaningful "connections" stat
 * instead of Minecraft's player count.
 *
 * Android apps can normally read their own network namespace's `/proc/net/tcp`;
 * when the kernel/SELinux denies it, [count] returns null and the UI shows
 * "n/a" rather than inventing a number.
 */
object PortConnectionCounter {

    private val TCP_FILES = listOf("/proc/net/tcp", "/proc/net/tcp6")

    /** e.g. `1: 0100007F:1F90 ... 01 ...` -> 1 established connection on 8080. */
    fun count(port: Int): Int? {
        if (port !in 1..65535) return null
        var readable = false
        var total = 0
        for (path in TCP_FILES) {
            val lines = runCatching { File(path).readLines() }.getOrNull() ?: continue
            readable = true
            total += countLines(lines, port)
        }
        return if (readable) total else null
    }

    /** Pure parser over `/proc/net/tcp`-style lines; unit-testable. */
    fun countLines(lines: List<String>, port: Int): Int {
        if (port !in 1..65535) return 0
        var count = 0
        for ((index, raw) in lines.withIndex()) {
            if (index == 0 || raw.isBlank()) continue // header
            val columns = raw.trim().split(Regex("\\s+"))
            if (columns.size < 4) continue
            val local = columns[1]
            val state = columns[3]
            val localPort = local.substringAfterLast(':', "").toIntOrNull(16) ?: continue
            if (localPort == port && state.equals("01", ignoreCase = true)) count++
        }
        return count
    }
}
