package dev.javahost.core.service

import dev.javahost.core.model.RunState

/** What a Start command must do given the published runtime and the real session. */
enum class StartDecision {
    /** A child JVM for the published runtime already exists: observe it, do not spawn another. */
    ADOPT_RUNNING,

    /** A stop is winding down: remember the requested instance and start it when the stop ends. */
    QUEUE_AFTER_STOP,

    /** The runtime snapshot looks active but no child JVM exists: force-clear it, then start. */
    CLEAR_STALE_THEN_START,

    /** Nothing is running: start normally. */
    START_FRESH
}

/** What a Restart command must do. */
enum class RestartDecision {
    /** A real session exists: ask the controller to stop and start the same instance. */
    RESTART_EXISTING,

    /** The runtime snapshot looks active but no child JVM exists: force-clear it, then start. */
    CLEAR_STALE_THEN_START,

    /** Nothing is running: treat the restart as a fresh start. */
    START_FRESH
}

/**
 * Pure transition rules for the foreground service's Stop/Start/Restart handling.
 *
 * The published [dev.javahost.core.model.ServerRuntimeStatus] snapshot can
 * disagree with reality after an OEM process kill or a lifecycle bug, so
 * [decideStart] and [decideRestart] take both the snapshot and the controller's
 * truthful session flag ([dev.javahost.core.api.ServerController.hasActiveSession]).
 * Keeping the rules here makes the service's lifecycle decisions unit-testable on
 * a plain JVM; the service only executes the returned decision.
 */
object ServiceLifecycleDecisions {

    /**
     * Decides what a Start command must do.
     *
     * - a connected STOPPING transition queues the requested start so it runs
     *   immediately after the stop (_Start during Stop_ must not be dropped);
     * - an active runtime backed by a real session is adopted (never two JVMs);
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
     * Decides what a Restart command must do.
     *
     * The transient terminal `STOPPED` published between the stop and start halves
     * of an in-flight restart is not visible here: the service ignores it while its
     * `restartInFlight` guard is set.
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
}
