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
 * Up to [MAX_RUNNING_SERVERS] child JVMs may run at the same time, one per
 * instance, as long as their ports differ. [runtime], [consoleEntries] and
 * [activeInstanceId] always describe the *focused* instance (what the console
 * screen and the ongoing notification show); [runtimes] carries the per-instance
 * status used by the Home cards and the server detail screen.
 *
 * Implementations must be process-wide singletons owned by
 * [dev.javahost.AppContainer] so the service and Compose UI observe the same
 * state even when the Activity is destroyed.
 */
interface ServerController {
    val runtime: StateFlow<ServerRuntimeStatus>
    val runtimes: StateFlow<Map<String, ServerRuntimeStatus>>
    val activeInstanceId: StateFlow<String?>
    val consoleEntries: StateFlow<List<ConsoleEntry>>

    /**
     * Resolve the instance, validate JRE/EULA/JAR/port, build the launch command
     * and spawn the child process. Rejects when [MAX_RUNNING_SERVERS] are already
     * running, the instance is already running, or its port conflicts.
     */
    fun start(instanceId: String): StartResult

    /** Graceful stop of one server (others keep running). */
    fun stop(instanceId: String, force: Boolean = false)

    /** Stop then start the same instance; other sessions are unaffected. */
    fun restart(instanceId: String)

    /** Last-resort kill of one wedged session (returns true when cleared). */
    fun forceStop(instanceId: String): Boolean

    /** Last-resort kill of every session (returns the number cleared). */
    fun forceStopAll(): Int

    fun sendCommand(instanceId: String, command: String)

    /** Switch the focused instance (console + notification target). */
    fun focus(instanceId: String)

    fun clearConsole(instanceId: String)

    /** Copy the in-memory console of one instance into [destination]. */
    fun exportConsole(instanceId: String, destination: File): Result<File>

    /** Last N raw lines of one instance (in-memory tail, then crash, then disk). */
    fun recentTail(instanceId: String, count: Int): List<String>

    fun activeLastCrash(instanceId: String): ExitInfo?

    /** Instance-aware historical console tail (disk first when not active). */
    fun readStartupTail(instanceId: String, lines: Int): List<String> = emptyList()

    /** Clears the last-crash report of one instance without touching crash files. */
    fun clearLastCrash(instanceId: String) = Unit

    /**
     * Re-resolve the LAN addresses of one running instance, TCP-probe each one
     * and publish to that instance's runtime. False when it is not running.
     */
    fun probeLan(instanceId: String): Boolean = false

    /** True while a child JVM session exists for [instanceId]. */
    fun hasActiveSession(instanceId: String): Boolean = false

    /** True while at least one child JVM session exists. */
    fun hasAnyActiveSession(): Boolean = false

    /**
     * Name of the instance that owns [port] — either a currently running session
     * or an instance whose configured port is [port]. Used for setup warnings and
     * start-time conflict rejection.
     */
    fun portOwner(port: Int, exceptInstanceId: String? = null): String? = null

    /** Called when the service is being destroyed; must not leave a JVM behind. */
    fun shutdown()

    fun dispose()

    companion object {
        /** Maximum number of simultaneously hosted child JVMs. */
        const val MAX_RUNNING_SERVERS = 5
    }
}
