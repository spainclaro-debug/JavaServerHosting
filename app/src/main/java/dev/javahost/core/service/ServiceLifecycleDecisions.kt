package dev.javahost.core.service

import dev.javahost.core.model.RunState

/** What a Start command must do given one instance's published runtime and real session. */
enum class StartDecision {
    /** A child JVM for the published runtime already exists: observe it, do not spawn another. */
    ADOPT_RUNNING,

    /** A stop of the same instance is winding down: remember it and start when the stop ends. */
    QUEUE_AFTER_STOP,

    /** The runtime snapshot looks active but no child JVM exists: force-clear it, then start. */
    CLEAR_STALE_THEN_START,

    /** Nothing is running: start normally. */
    START_FRESH
}

/** What a Restart command must do for one instance. */
enum class RestartDecision {
    /** A real session exists: ask the controller to stop and start the same instance. */
    RESTART_EXISTING,

    /** The runtime snapshot looks active but no child JVM exists: force-clear it, then start. */
    CLEAR_STALE_THEN_START,

    /** Nothing is running: treat the restart as a fresh start. */
    START_FRESH
}

/**
 * Pure transition rules for the foreground service's per-instance Stop/Start/
 * Restart handling, plus the service-wide keep-alive decision.
 *
 * Each instance has its own published [dev.javahost.core.model.ServerRuntimeStatus]
 * snapshot, which can disagree with reality after an OEM process kill or a
 * lifecycle bug, so the decision functions take both that instance's snapshot
 * and the controller's truthful per-instance session flag
 * ([dev.javahost.core.api.ServerController.hasActiveSession]). Keeping the rules
 * here makes every lifecycle decision unit-testable on a plain JVM; the service
 * only executes the returned decision.
 *
 * All functions are pure, deterministic and never throw.
 */
object ServiceLifecycleDecisions {

    /**
     * Decides what a Start command must do for one instance.
     *
     * - a connected STOPPING transition of the same instance queues the requested
     *   start so it runs immediately after that stop (_Start during Stop_ must not
     *   be dropped);
     * - an active runtime backed by a real session is adopted (never two JVMs for
     *   the same instance);
     * - an active runtime **without** a session is a stale snapshot and is
     *   cleared before starting, which recovers from lifecycle bugs;
     * - anything else starts fresh.
     */
    fun decideStart(
        runtimeIsActive: Boolean,
        runtimeState: RunState?,
        hasSession: Boolean
    ): StartDecision = when {
        runtimeIsActive && runtimeState == RunState.STOPPING -> StartDecision.QUEUE_AFTER_STOP
        runtimeIsActive && hasSession -> StartDecision.ADOPT_RUNNING
        runtimeIsActive -> StartDecision.CLEAR_STALE_THEN_START
        else -> StartDecision.START_FRESH
    }

    /**
     * Decides what a Restart command must do for one instance.
     *
     * The transient terminal `STOPPED` published between the stop and start halves
     * of an in-flight restart is not visible here: the service ignores it while its
     * per-instance `restartInFlight` guard is set.
     */
    fun decideRestart(
        runtimeIsActive: Boolean,
        runtimeState: RunState?,
        hasSession: Boolean
    ): RestartDecision = when {
        runtimeIsActive && hasSession -> RestartDecision.RESTART_EXISTING
        runtimeIsActive -> RestartDecision.CLEAR_STALE_THEN_START
        else -> RestartDecision.START_FRESH
    }

    /**
     * Whether the foreground service must stay alive.
     *
     * It must, as long as any instance still has an active runtime, any child JVM
     * session exists, or the service still has queued/in-flight work (a start
     * waiting behind a stop, an auto-restart delay, a restart operation). It is
     * only allowed to finish when all three are false - that is the "no sessions
     * remain" rule that keeps one server's stop from tearing down the others.
     */
    fun shouldKeepServiceAlive(
        anyActiveRuntime: Boolean,
        anyActiveSession: Boolean,
        hasPendingWork: Boolean
    ): Boolean = anyActiveRuntime || anyActiveSession || hasPendingWork

    /**
     * The instance the ongoing notification/console should describe: the focused
     * instance when it is one of [activeInstanceIds], else the first active
     * instance in stable (sorted) order, else null.
     */
    fun resolveFocusedInstanceId(
        focusedInstanceId: String?,
        activeInstanceIds: Collection<String>
    ): String? {
        val focused = focusedInstanceId?.takeIf { it.isNotBlank() }
        if (focused != null && focused in activeInstanceIds) return focused
        return activeInstanceIds.asSequence()
            .filter { it.isNotBlank() }
            .sorted()
            .firstOrNull()
    }
}
