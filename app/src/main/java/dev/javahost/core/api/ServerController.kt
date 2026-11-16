package dev.javahost.core.api

import dev.javahost.core.model.ConsoleEntry
import dev.javahost.core.model.ExitInfo
import dev.javahost.core.model.ServerRuntimeStatus
import dev.javahost.core.model.StartResult
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Contract used by the UI and the foreground service.
 *
 * Exactly one Java server process is hosted at a time; multiple instances may
 * exist on disk. Implementations must be process-wide singletons owned by
 * [dev.javahost.AppContainer] so the service and Compose UI observe the same
 * state even when the Activity is destroyed.
 */
interface ServerController {
    val runtime: StateFlow<ServerRuntimeStatus>
    val activeInstanceId: StateFlow<String?>
    val consoleEntries: StateFlow<List<ConsoleEntry>>

    /**
     * Resolve the instance, validate JRE/EULA/JAR, build the launch command and
     * spawn the child process. Call from the foreground service.
     */
    fun start(instanceId: String): StartResult

    /** Graceful stop (sends "stop" for Minecraft, then destroys after a timeout). */
    fun stop(force: Boolean = false)

    /** Stop then start the same instance. No-op when nothing is active. */
    fun restart()

    fun sendCommand(command: String)

    fun clearConsole()

    /** Copy the current in-memory console into [destination]. */
    fun exportConsole(destination: File): Result<File>

    /** Last N raw lines, e.g. to show a post-mortem after an OOM kill. */
    fun recentTail(count: Int): List<String>

    fun activeLastCrash(): ExitInfo?

    /** Instance-aware historical console tail (disk first when not the active session). */
    fun readStartupTail(instanceId: String, lines: Int): List<String> = emptyList()

    /** Clears the last-crash report without touching the saved crash files. */
    fun clearLastCrash() = Unit

    /**
     * Last-resort recovery for a wedged stop (normally only needed if a ROM
     * kills process reaping): kill the child by pid, publish a terminal state,
     * and clear the session so a later Start/Restart can work. Returns true if a
     * session was forcibly cleared.
     */
    fun forceStop(): Boolean = false

    /**
     * Re-resolve the LAN addresses of the running server, TCP-probe each one and
     * publish the result to [ServerRuntimeStatus.lan] (the UI's "Test LAN
     * reachability" action). Returns false when no server is active.
     */
    fun probeLan(): Boolean = false

    /**
     * True while a child JVM session actually exists. This is deliberately
     * independent of the published [runtime] state: after a lifecycle bug or an
     * OEM process kill the runtime can be stale, and the foreground service uses
     * this to distinguish "really running" from "stale snapshot".
     */
    fun hasActiveSession(): Boolean = false

    /** Called when the service is being destroyed; must not leave a JVM behind. */
    fun shutdown()

    fun dispose()
}
