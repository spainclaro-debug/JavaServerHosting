package dev.javahost.core.server

import dev.javahost.core.model.ExitReason

/** Result of post-mortem classification for an exited child process. */
data class ClassifiedExit(
    val reason: ExitReason,
    /** POSIX signal derived from the exit code (128 + signal), when applicable. */
    val signal: Int?,
    val summary: String,
    /** True when the exit was requested by the user (stop / console "stop"). */
    val expected: Boolean
)

/**
 * Turns a raw exit code plus the last console lines into an [ExitReason].
 *
 * Android reports a signalled child as `128 + signal` (SIGKILL -> 137), and the
 * low-memory killer commonly uses SIGKILL, so 137 is treated as a system kill
 * rather than an application crash.  Everything else is matched against the
 * console tail, which is what the "why did my server die" message is built from.
 */
object ExitClassifier {

    /** Exit codes above 128 encode a signal on Linux/Android (128 + signal). */
    fun signalOf(exitCode: Int?): Int? {
        val code = exitCode ?: return null
        return if (code in 129..192) code - 128 else null
    }

    /** Scans the tail from the most recent line backwards for a crash signature. */
    fun scanTail(tail: List<String>): CrashSignal? {
        for (line in tail.asReversed()) {
            ConsoleParser.crashSignal(line)?.let { return it }
        }
        return null
    }

    fun classify(
        exitCode: Int?,
        stopRequested: Boolean,
        force: Boolean,
        tail: List<String>,
        observedSignal: CrashSignal? = null
    ): ClassifiedExit {
        val signal = signalOf(exitCode)
        if (stopRequested) {
            val summary = if (force) "Stopped by user (forced)" else "Stopped by user"
            return ClassifiedExit(ExitReason.STOPPED_BY_USER, signal, summary, expected = true)
        }
        // Exit code 0 means the JVM itself completed normally. Do this before
        // scanning the tail: Minecraft/plugins can print a stale class-load or
        // native-library WARN during shutdown, and treating that as the cause of
        // death produces the false "server exited unexpectedly: missing class"
        // report after a perfectly normal user stop.
        if (exitCode == 0) {
            return ClassifiedExit(
                reason = ExitReason.STOPPED_BY_USER,
                signal = signal,
                summary = if (stopRequested) "Stopped by user" else "The server exited normally (exit code 0).",
                expected = true
            )
        }
        if (signal == 9) {
            return ClassifiedExit(
                reason = ExitReason.KILLED_BY_SYSTEM,
                signal = signal,
                summary = "Process was killed by the operating system (SIGKILL). " +
                    "The device may have been low on memory or terminated the app's process group.",
                expected = false
            )
        }
        val detected = scanTail(tail) ?: observedSignal
        if (detected != null) {
            return ClassifiedExit(detected.reason, signal, summaryFor(detected), expected = false)
        }
        if (signal == 15) {
            return ClassifiedExit(
                reason = ExitReason.CRASH_UNKNOWN,
                signal = signal,
                summary = "The process was terminated by SIGTERM (exit code 143).",
                expected = false
            )
        }
        val codeText = exitCode?.toString() ?: "unknown"
        return ClassifiedExit(
            reason = ExitReason.CRASH_UNKNOWN,
            signal = signal,
            summary = "Server exited unexpectedly with code $codeText.",
            expected = false
        )
    }

    fun summaryFor(signal: CrashSignal): String = when (signal) {
        CrashSignal.OUT_OF_MEMORY ->
            "The JVM ran out of memory. Increase the server's memory or reduce the world size."
        CrashSignal.PORT_IN_USE ->
            "The server could not bind its port because another process is already using it."
        CrashSignal.UNSUPPORTED_JAVA ->
            "The server was built for a newer Java version. Select a newer Java runtime in the server settings."
        CrashSignal.MISSING_LIBRARY ->
            "A required class or native library is missing. Re-import the server JAR or check the server files."
        CrashSignal.EULA ->
            "The server stopped because the Minecraft EULA was not accepted."
    }
}
