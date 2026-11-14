package dev.javahost.core.server

import dev.javahost.core.model.ExitReason
import dev.javahost.core.model.LogLevel

/** Number of players reported by the server, e.g. when `/list` is queried. */
data class PlayerCount(val online: Int, val max: Int)

enum class PlayerEventType { JOIN, LEAVE }

/** A "player joined/left the game" style console line. */
data class PlayerEvent(val name: String, val type: PlayerEventType)

/**
 * Crash-relevant signatures detectable in plain console output.  The enum keeps
 * the mapping to the shared [ExitReason] so [ExitClassifier] and the parsers
 * cannot drift apart.
 */
enum class CrashSignal(val reason: ExitReason) {
    OUT_OF_MEMORY(ExitReason.CRASH_OUT_OF_MEMORY),
    PORT_IN_USE(ExitReason.CRASH_PORT_IN_USE),
    UNSUPPORTED_JAVA(ExitReason.CRASH_UNSUPPORTED_JAVA),
    MISSING_LIBRARY(ExitReason.CRASH_MISSING_LIBRARY),
    EULA(ExitReason.CRASH_EULA_REJECTED)
}

/**
 * Pure, allocation-light parsing of child-JVM console lines.
 *
 * The server writes plain lines (occasionally with ANSI colour escapes, e.g.
 * Paper).  Everything here is a pure function on a single line so it can be
 * exhaustively unit-tested on a plain JVM; the stateful console ring lives in
 * [ServerManager].
 */
object ConsoleParser {

    /** CSI sequences, OSC sequences and two-character ESC sequences. */
    private val ANSI_PATTERN: Regex = Regex(
        """(?:\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(?:\u0007|\u001B\\)|\u001B[@-Z\\-_])"""
    )

    private val LEVEL_TOKEN: Regex = Regex(
        """(?i)\b(TRACE|FINEST|DEBUG|FINE|FINER|CONFIG|INFO|WARN|WARNING|SEVERE|ERROR|FATAL)\b"""
    )

    private val LEVEL_PREFIX: Regex = Regex(
        """(?i)^(TRACE|FINEST|DEBUG|FINE|FINER|CONFIG|INFO|WARN|WARNING|SEVERE|ERROR|FATAL)\b"""
    )

    private val BRACKET: Regex = Regex("""\[([^\[\]]{1,80})]""")

    private val PLAYER_COUNT: Regex = Regex(
        """there are\s+(\d+)\s+of a max(?:imum)? of\s+(\d+)\s+players online""",
        RegexOption.IGNORE_CASE
    )

    private val PLAYER_JOIN: Regex = Regex(
        """\b([A-Za-z0-9_]{1,16}) (?:joined|has joined) the game\b""",
        RegexOption.IGNORE_CASE
    )

    private val PLAYER_LEAVE: Regex = Regex(
        """\b([A-Za-z0-9_]{1,16}) (?:left the game|lost connection)\b""",
        RegexOption.IGNORE_CASE
    )

    private val MC_PORT: Regex = Regex(
        """starting minecraft server on\s+\S*?:(\d{1,5})""",
        RegexOption.IGNORE_CASE
    )

    private val LISTEN_PORT: Regex = Regex(
        """listening on\s+(?:port\s+|/?(?:[\w.\[\]-]+:))?(\d{1,5})\b""",
        RegexOption.IGNORE_CASE
    )

    private val STARTED_PORT: Regex = Regex(
        """(?:server\s+)?started\s+(?:on|at)\s+port\s+(\d{1,5})\b""",
        RegexOption.IGNORE_CASE
    )

    private val PLAIN_PORT: Regex = Regex(
        """\bport\s*[:=]\s*(\d{1,5})\b""",
        RegexOption.IGNORE_CASE
    )

    /** Removes ANSI escape sequences; returns the input untouched when clean. */
    fun stripAnsi(raw: String): String {
        if (raw.isEmpty() || raw.indexOf('\u001B') < 0) return raw
        return ANSI_PATTERN.replace(raw, "")
    }

    /**
     * Classifies a line as a log level.  Looks at bracketed logger prefixes
     * (`[Server thread/INFO]`), plain level prefixes (`WARN:`), Java exception
     * heuristics, and falls back to [LogLevel.INFO].
     */
    fun classify(raw: String): LogLevel {
        val line = stripAnsi(raw).trim()
        if (line.isEmpty()) return LogLevel.UNKNOWN
        // Bracketed segments such as [12:34:56] [Server thread/WARN]:
        for (match in BRACKET.findAll(line.take(400))) {
            val level = levelFromText(match.groupValues[1])
            if (level != null) return level
        }
        LEVEL_PREFIX.find(line)?.let { return mapLevel(it.groupValues[1]) }
        val lower = line.lowercase()
        if (lower.startsWith("exception") ||
            lower.startsWith("caused by:") ||
            lower.contains("outofmemoryerror") ||
            lower.startsWith("error:") ||
            lower.startsWith("fatal:") ||
            lower.startsWith("severe:")
        ) {
            return LogLevel.ERROR
        }
        if (lower.startsWith("warning:")) return LogLevel.WARN
        return LogLevel.INFO
    }

    private fun levelFromText(text: String): LogLevel? {
        val match = LEVEL_TOKEN.find(text) ?: return null
        return mapLevel(match.groupValues[1])
    }

    private fun mapLevel(token: String): LogLevel = when (token.uppercase()) {
        "TRACE", "FINEST" -> LogLevel.TRACE
        "DEBUG", "FINE", "FINER" -> LogLevel.DEBUG
        "CONFIG", "INFO" -> LogLevel.INFO
        "WARN", "WARNING" -> LogLevel.WARN
        "SEVERE", "ERROR", "FATAL" -> LogLevel.ERROR
        else -> LogLevel.INFO
    }

    /**
     * True when the line reports that the server finished booting.  Minecraft
     * logs `Done (36.123s)! For help, type "help"`; generic servers commonly log
     * "Listening on port N" or "Server started".
     */
    fun isReady(raw: String): Boolean {
        val line = stripAnsi(raw)
        if (line.isBlank()) return false
        val lower = line.lowercase()
        if (lower.contains("done (")) return true
        if (lower.contains("done in ")) return true
        if (lower.contains("server started")) return true
        if (lower.contains("started on port")) return true
        if (lower.contains("listening on port")) return true
        if (Regex("""listening on\s+(?:/)?[\w.\[\]-]+:\d+""", RegexOption.IGNORE_CASE).containsMatchIn(line)) return true
        return false
    }

    /** True when the server is asking the operator to accept the Mojang EULA. */
    fun isEulaPrompt(raw: String): Boolean {
        val lower = stripAnsi(raw).lowercase()
        return lower.contains("agree to the eula") ||
            lower.contains("eula.txt") ||
            lower.contains("by changing the setting below to true")
    }

    /** Parses "There are N of a max of M players online". */
    fun parsePlayers(raw: String): PlayerCount? {
        val match = PLAYER_COUNT.find(stripAnsi(raw)) ?: return null
        val online = match.groupValues[1].toIntOrNull() ?: return null
        val max = match.groupValues[2].toIntOrNull() ?: return null
        return PlayerCount(online, max)
    }

    /** Parses Vanilla/Paper join and leave lines. */
    fun parsePlayerEvent(raw: String): PlayerEvent? {
        val line = stripAnsi(raw)
        PLAYER_JOIN.find(line)?.let { return PlayerEvent(it.groupValues[1], PlayerEventType.JOIN) }
        PLAYER_LEAVE.find(line)?.let { return PlayerEvent(it.groupValues[1], PlayerEventType.LEAVE) }
        return null
    }

    /** Parses a server port from common "server is listening" style lines. */
    fun parsePort(raw: String): Int? {
        val line = stripAnsi(raw)
        for (pattern in listOf(MC_PORT, LISTEN_PORT, STARTED_PORT, PLAIN_PORT)) {
            val match = pattern.find(line) ?: continue
            val port = match.groupValues[1].toIntOrNull() ?: continue
            if (port in 1..65535) return port
        }
        return null
    }

    /** Detects a crash-relevant signature in a single line. */
    fun crashSignal(raw: String): CrashSignal? {
        val lower = stripAnsi(raw).lowercase()
        if (lower.isEmpty()) return null
        if (lower.contains("outofmemoryerror") ||
            lower.contains("java heap space") ||
            lower.contains("insufficient memory") ||
            lower.contains("cannot allocate memory") ||
            lower.contains("native memory allocation")
        ) {
            return CrashSignal.OUT_OF_MEMORY
        }
        if (lower.contains("address already in use") ||
            lower.contains("failed to bind to port") ||
            lower.contains("bindexception") ||
            lower.contains("unable to bind") ||
            lower.contains("port is already in use")
        ) {
            return CrashSignal.PORT_IN_USE
        }
        if (lower.contains("unsupportedclassversionerror") ||
            lower.contains("unsupported class file major version") ||
            lower.contains("unsupported major.minor version") ||
            lower.contains("linkageerror") && lower.contains("class file version")
        ) {
            return CrashSignal.UNSUPPORTED_JAVA
        }
        if (lower.contains("noclassdeffounderror") ||
            lower.contains("classnotfoundexception") ||
            lower.contains("could not find or load main class") ||
            lower.contains("unsatisfiedlinkerror") ||
            lower.contains("cannot open shared object file") ||
            lower.contains("no suitable driver")
        ) {
            return CrashSignal.MISSING_LIBRARY
        }
        if (lower.contains("agree to the eula") || lower.contains("eula.txt")) {
            return CrashSignal.EULA
        }
        return null
    }
}
