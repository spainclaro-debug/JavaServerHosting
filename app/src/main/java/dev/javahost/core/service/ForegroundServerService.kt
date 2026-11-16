package dev.javahost.core.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.javahost.JavaHostApp
import dev.javahost.MainActivity
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerRuntimeStatus
import dev.javahost.core.model.StartResult
import dev.javahost.core.system.SilentMediaKeepAlive
import dev.javahost.core.system.WakeLocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Special-use foreground service that owns the lifetime of the hosted server.
 *
 * Responsibilities:
 *  - satisfy the 5-second rule: [startForeground] is called as the very first
 *    thing in [onStartCommand], including for a null (sticky re-delivery) intent;
 *  - hold [WakeLocks] only while the child process actually exists;
 *  - mirror [dev.javahost.core.api.ServerController.runtime] into an ongoing,
 *    throttled notification (state, uptime, players, heap, free RAM) with Stop
 *    and Restart actions and a content intent that deep-links to the instance;
 *  - release everything - locks, notification, silent media, child JVM - on
 *    every exit path (stop, crash, rejection, task removal is ignored on
 *    purpose, service destruction).
 *  - never dispose the process-wide controller on *idle* teardown: the manager
 *    is revivable, so a later Start must work without restarting the app
 *    process. The historic bug was an unconditional
 *    `container.server.shutdown()` in [onDestroy]: every normal Stop destroyed
 *    the service, `shutdown()` permanently disposed the singleton and the next
 *    Start was rejected with "JavaHost is shutting down." until the process was
 *    killed. The controller is only shut down here when a server is still alive.
 *
 * The service never starts the child JVM itself; it asks
 * `container.server.start(instanceId)` and reacts to the observed runtime
 * state. That keeps exactly one JVM owned by one process-wide controller.
 */
class ForegroundServerService : Service() {

    private val app: JavaHostApp
        get() = application as JavaHostApp
    private val container
        get() = app.container

    private lateinit var notifications: ServerNotifications
    private lateinit var wakeLocks: WakeLocks

    /** Created lazily so the initial startForeground() path touches no container. */
    private val silentMedia: SilentMediaKeepAlive by lazy {
        SilentMediaKeepAlive(this, container.settings)
    }

    private var serviceScope: CoroutineScope? = null
    private var runtimeObserver: Job? = null
    private var tickerJob: Job? = null
    private var autoRestartJob: Job? = null

    private var currentInstanceId: String? = null
    private var sawActiveRuntime = false
    private var lastObservedState: RunState? = null
    private var lastNotificationAt = 0L
    private var foregroundPosted = false

    /** Consecutive automatic restarts since the last healthy RUNNING state. */
    private var autoRestartAttempts = 0

    /** Set by a user Stop so a crash racing with it can never auto-restart. */
    @Volatile
    private var manualStopRequested = false

    private val operationInProgress = AtomicBoolean(false)
    private var pendingCommand: (() -> Unit)? = null
    private val finishing = AtomicBoolean(false)

    /**
     * True while a [restartExclusive] operation owns the runtime. The STOPPED
     * state published between the stop and start halves of a restart is
     * transient; treating it as "finished" is the old Restart bug that made the
     * observer tear the service (and the process-wide manager) down mid-restart.
     */
    @Volatile
    private var restartInFlight = false

    /**
     * Start command that arrived while the previous server was still STOPPING.
     * [beginStart] defers it and the running stop operation starts it as soon as
     * the runtime is terminal, without tearing the service down in between.
     */
    @Volatile
    private var pendingStartInstanceId: String? = null

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                          */
    /* ------------------------------------------------------------------ */

    override fun onCreate() {
        super.onCreate()
        notifications = ServerNotifications(this)
        notifications.ensureChannels()
        wakeLocks = WakeLocks(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val extraInstanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID)
        if (!extraInstanceId.isNullOrBlank()) currentInstanceId = extraInstanceId

        // A command can race with stopSelf() (e.g. the user taps Start right
        // after Stop). Revive the instance instead of silently dropping it.
        if (finishing.get()) {
            finishing.set(false)
            foregroundPosted = false
            runtimeObserver = null
            tickerJob = null
            // The previous service generation is gone: a command it still had
            // queued behind a failing operation must never run in this one.
            pendingCommand = null
            cancelAutoRestart()
        }

        // 5-second rule: everything before this line must stay trivial. The
        // placeholder notification deliberately does not touch the container.
        if (!ensureForeground()) return START_NOT_STICKY
        ensureScope()

        when (action) {
            ACTION_START -> {
                if (extraInstanceId.isNullOrBlank()) {
                    failEarly("No server instance was specified.")
                } else {
                    beginStart(extraInstanceId)
                }
            }
            ACTION_STOP -> beginStop(force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true)
            ACTION_RESTART -> beginRestart(extraInstanceId)
            ACTION_FORCE_STOP -> beginForceStop()
            else -> adoptExistingSession()
        }
        return START_STICKY
    }

    /**
     * The task being swiped away must not kill a running server: the service
     * keeps running in the foreground (manifest also sets stopWithTask=false).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; keeping foreground service alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Releases the service's resources, and shuts the process-wide controller
     * down **only when a server is actually alive**.
     *
     * This distinction is load-bearing: the foreground service is destroyed
     * after every normal Stop, and `ServerController.shutdown()` disposes the
     * singleton ([dev.javahost.core.server.ServerManager] sets `disposed`,
     * cancels its scope and latches `shutdownOnce`). Calling it unconditionally
     * used to poison every later Start with "JavaHost is shutting down." until
     * the whole app process was killed. When idle, the controller is intentionally
     * left usable for the next foreground-service instance.
     */
    override fun onDestroy() {
        finishing.set(true)
        cancelAutoRestart()
        runtimeObserver?.cancel()
        tickerJob?.cancel()
        serviceScope?.cancel()
        runtimeObserver = null
        tickerJob = null
        pendingCommand = null
        pendingStartInstanceId = null
        restartInFlight = false
        runCatching { silentMedia.stop() }
        runCatching { wakeLocks.release() }
        // A child JVM still alive (system-initiated destruction, crash alert
        // while running, app shutdown) must never be left behind; an idle
        // teardown must not dispose the manager.
        val sessionActive = runCatching { container.server.hasActiveSession() }.getOrDefault(false)
        val runtimeActive = runCatching { container.server.runtime.value.isActive }.getOrDefault(false)
        if (sessionActive || runtimeActive) {
            runCatching { container.server.shutdown() }
        } else {
            Log.i(TAG, "Service destroyed while idle; keeping the process-wide controller usable")
        }
        // Belt and braces: whether we stopped ourselves (normal stop, crash
        // alert, failed auto-restart) or the system destroyed us, never leave
        // the ongoing foreground notification behind. Alerts stay cancellable
        // by the user on purpose.
        runCatching { notifications.cancelStatus() }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundPosted = false
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* Actions                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Bookkeeping shared by every deliberate manual start: a Start tap and the
     * start that runs right after a queued Start-during-Stop. It opens a fresh
     * crash-loop budget, clears the "user asked to stop" flag, cancels pending
     * automatic restarts/alerts and adopts the requested instance id.
     */
    private fun prepareManualStart(instanceId: String) {
        autoRestartAttempts = 0
        manualStopRequested = false
        cancelAutoRestart()
        notifications.cancelAlert()
        currentInstanceId = instanceId
    }

    private fun beginStart(instanceId: String) {
        // Capture before the bookkeeping clears it: a Stop issued a split second
        // ago must still be treated as a stop in progress below.
        val stopWasRequested = manualStopRequested
        prepareManualStart(instanceId)

        val status = currentRuntime()
        val decision = ServiceLifecycleDecisions.decideStart(
            runtimeIsActive = status?.isActive == true,
            runtimeState = status?.state,
            hasSession = hasActiveSession()
        )
        when (decision) {
            StartDecision.ADOPT_RUNNING -> {
                // A Stop may have been issued a split second ago while the runtime
                // still reads RUNNING (STOPPING is published asynchronously). Queue
                // the start so it is not lost when the stop wins. A restart in
                // flight already brings the server back, so it must NOT queue: a
                // stale queued id would otherwise be consumed by a later Stop and
                // restart the server unexpectedly.
                if (stopWasRequested) {
                    pendingStartInstanceId = instanceId
                    Log.i(TAG, "Stop in progress; queued start of $instanceId until it finishes")
                } else {
                    // Do not spawn a second JVM; adopt the running one.
                    status?.let { adopt(it) }
                }
            }
            StartDecision.QUEUE_AFTER_STOP -> {
                // Start pressed while the previous server is still stopping. The
                // running stop operation starts this instance as soon as the
                // runtime is terminal - no service teardown in between.
                pendingStartInstanceId = instanceId
                Log.i(TAG, "Stop in progress; queued start of $instanceId until it finishes")
                if (!operationInProgress.get()) {
                    // The STOPPING state was published outside this service's stop
                    // operation (e.g. by a stale teardown). Drive the stop to
                    // completion so the queued start cannot be stranded.
                    dispatchExclusive { stopExclusive(force = false) }
                }
            }
            StartDecision.CLEAR_STALE_THEN_START -> {
                // The runtime claims to be active but no child JVM exists: clear
                // the zombie snapshot, then start fresh.
                dispatchExclusive { clearStaleAndStartExclusive(instanceId) }
            }
            StartDecision.START_FRESH -> dispatchExclusive { startExclusive(instanceId) }
        }
    }

    /** What [startServer] did, so callers can keep the notification/auto-restart flow. */
    private enum class StartOutcome {
        STARTED,

        /** Rejection/crash already reported and the service already finished. */
        REJECTED,

        /** The controller said Ok but the child was already gone; caller decides. */
        EXITED_IMMEDIATELY
    }

    /** Spawns [instanceId] with no previous runtime to clear. */
    private fun startExclusive(instanceId: String) =
        runStartOperation(instanceId, clearStaleFirst = false)

    /** Clears a zombie "active" runtime, then spawns [instanceId]. */
    private fun clearStaleAndStartExclusive(instanceId: String) =
        runStartOperation(instanceId, clearStaleFirst = true)

    /**
     * Runs a start inside the current exclusive operation and releases the
     * operation in `finally`; a command queued while it ran is dispatched by
     * [finishOperation].
     */
    private fun runStartOperation(instanceId: String, clearStaleFirst: Boolean) {
        scope().launch {
            try {
                if (clearStaleFirst) clearStaleRuntime()
                handleStartOutcome(startServer(instanceId))
            } finally {
                finishOperation()
            }
        }
    }

    /** Best-effort removal of a runtime that claims to be active without a session. */
    private suspend fun clearStaleRuntime() {
        withContext(Dispatchers.IO) {
            runCatching { container.server.forceStop() }
                .onFailure { Log.w(TAG, "Could not clear the stale server session", it) }
        }
        withTimeoutOrNull(STALE_CLEAR_WAIT_MS) {
            container.server.runtime.first { !it.isActive }
        }
    }

    /**
     * Spawns [instanceId] and keeps notifications/locks in sync.
     *
     * Rejections are reported and finish the service here (same as before). A
     * successful start whose runtime is already terminal is returned as
     * [StartOutcome.EXITED_IMMEDIATELY] so a normal Start can run the crash
     * post-mortem while a Restart can report the restart as failed.
     */
    private suspend fun startServer(instanceId: String): StartOutcome {
        startObserving()
        postStatus(force = true)
        wakeLocks.acquire()
        val result = withContext(Dispatchers.IO) {
            runCatching { container.server.start(instanceId) }
        }
        val startResult = result.getOrElse { error ->
            Log.e(TAG, "Server start threw", error)
            val current = currentRuntime()
            if (current?.isActive == true) {
                // A concurrent (auto) restart won the race: adopt it instead of
                // tearing down a live server.
                adopt(current)
                return StartOutcome.STARTED
            }
            rejectStart(instanceId, error.message ?: "The server could not be started.")
            return StartOutcome.REJECTED
        }
        return when (startResult) {
            is StartResult.Ok -> {
                sawActiveRuntime = true
                silentMedia.start()
                postStatus(force = true)
                // The child can exit between start() returning and the observer's
                // first collect; report that race instead of leaving a stale
                // "Starting" foreground service behind.
                val status = currentRuntime()
                if (status == null || status.isActive) StartOutcome.STARTED
                else StartOutcome.EXITED_IMMEDIATELY
            }
            is StartResult.Rejected -> {
                val current = currentRuntime()
                if (current?.isActive == true) {
                    adopt(current)
                    StartOutcome.STARTED
                } else {
                    rejectStart(instanceId, startResult.message)
                    StartOutcome.REJECTED
                }
            }
        }
    }

    private fun rejectStart(instanceId: String?, message: String) {
        wakeLocks.release()
        silentMedia.stop()
        notifications.postStartRejected(
            message,
            instanceName(instanceId),
            contentIntent(instanceId),
            restartServiceIntent(instanceId)
        )
        finishService()
    }

    private fun handleStartOutcome(outcome: StartOutcome) {
        when (outcome) {
            StartOutcome.STARTED, StartOutcome.REJECTED -> Unit
            StartOutcome.EXITED_IMMEDIATELY -> {
                val status = currentRuntime()
                if (status != null) handleFinished(status) else finishService()
            }
        }
    }

    private fun beginStop(force: Boolean) {
        // A manual stop is final: cancel any pending auto-restart, and make sure
        // a crash that races with this stop cannot schedule another one. An
        // explicit Stop also cancels a Start that was queued behind a stop.
        manualStopRequested = true
        cancelAutoRestart()
        pendingStartInstanceId = null
        dispatchExclusive { stopExclusive(force) }
    }

    private fun stopExclusive(force: Boolean) {
        val runtime = currentRuntime()
        val wasActive = runtime?.isActive == true
        val targetId = runtime?.instanceId
        if (wasActive) sawActiveRuntime = true
        startObserving()
        postStatus(force = true)
        scope().launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching { container.server.stop(force = force) }
                        .onFailure { error ->
                            Log.w(TAG, "Graceful stop failed; forcing", error)
                            runCatching { container.server.stop(force = true) }
                        }
                }
                // Wait for the controller to actually publish STOPPED/CRASHED.
                // Checking immediately after stop() is useless because stop() is
                // asynchronous: it only published STOPPING, which used to leave
                // the FGS notification looping on "Shutting down…" forever. The
                // caps match the manager's ~11 s worst-case shutdown (8 s save +
                // 2 s SIGTERM grace + SIGKILL).
                val stopped = withTimeoutOrNull(
                    if (force) FORCE_STOP_WAIT_MS else GRACEFUL_STOP_WAIT_MS
                ) {
                    container.server.runtime.first { status ->
                        status.instanceId != targetId || !status.isActive
                    }
                } != null
                silentMedia.stop()
                wakeLocks.release()
                val stillActive = !stopped && currentRuntime()?.isActive == true
                if (stillActive) {
                    // Last resort: pid-level kill + terminal state reset. This
                    // must always end with the notification removed; a wedged
                    // STOPPING state previously looped "Shutting down…" forever.
                    Log.w(TAG, "Stop did not complete; forcing session reset")
                    runCatching { container.server.forceStop() }
                    withTimeoutOrNull(STALE_CLEAR_WAIT_MS) {
                        container.server.runtime.first { !it.isActive }
                    }
                    notifications.postCrashAlert(
                        title = "Server stop forced",
                        text = "The server did not stop gracefully and was force-stopped. Check the console for errors.",
                        contentIntent = contentIntent(targetId ?: currentInstanceId),
                        restartIntent = restartServiceIntent(targetId ?: currentInstanceId)
                    )
                } else {
                    // The user asked for the stop, so any pending auto-restart
                    // alert no longer describes reality.
                    notifications.cancelAlert()
                }
                // A Start pressed while the stop was winding down must not be
                // dropped: keep this foreground service alive and hand the queued
                // start to the command queue. No finishService() in between, so
                // the "Stopping…" notification becomes "Starting…" seamlessly.
                finishStopOrRunQueuedStart()
            } finally {
                finishOperation()
            }
        }
    }

    /**
     * Ends a completed stop: runs the Start command that arrived while the stop
     * was in progress (same foreground service, no teardown between) and/or lets
     * an already-queued command (e.g. Restart pressed during the stop) run;
     * otherwise finishes the service exactly as before.
     */
    private fun finishStopOrRunQueuedStart() {
        val queued = pendingStartInstanceId
        pendingStartInstanceId = null
        if (queued != null) {
            Log.i(TAG, "Stop finished; starting queued server $queued")
            // Run the same bookkeeping as a Start tap: the stop path must not
            // leave manualStopRequested set or keep the old crash-loop budget and
            // "Server stop forced" alert behind.
            prepareManualStart(queued)
            // finishOperation() dispatches this right after the stop operation ends.
            if (pendingCommand == null) {
                pendingCommand = { startExclusive(queued) }
            }
        }
        if (pendingCommand == null) {
            // Nothing queued: this stop ends the foreground service.
            finishService()
        }
        // A command queued while the stop ran (Start during Stop, Restart or a
        // second Stop) keeps the service alive; finishOperation dispatches it.
    }

    private fun beginForceStop() {
        manualStopRequested = true
        cancelAutoRestart()
        // A force stop is final: drop a Start that was queued behind a stop.
        pendingStartInstanceId = null
        val id = currentInstanceId ?: runCatching { container.server.runtime.value.instanceId }.getOrNull()
        scope().launch {
            runCatching { container.server.forceStop() }
            silentMedia.stop()
            wakeLocks.release()
            notifications.postCrashAlert(
                title = "Server force-stopped",
                text = "The server was terminated immediately; check the console for a stuck shutdown.",
                contentIntent = contentIntent(id),
                restartIntent = restartServiceIntent(id)
            )
            finishService()
        }
    }

    private fun beginRestart(instanceId: String?) {
        // A manual restart also starts a fresh crash-loop budget.
        autoRestartAttempts = 0
        manualStopRequested = false
        cancelAutoRestart()
        notifications.cancelAlert()
        // A restart supersedes a Start that was queued behind a previous stop.
        pendingStartInstanceId = null
        val runtime = currentRuntime()
        val targetId = instanceId?.takeIf { it.isNotBlank() }
            ?: runtime?.instanceId
            ?: currentInstanceId
            ?: runCatching { container.settings.current().lastActiveInstanceId }.getOrNull()
        if (targetId == null && runtime?.isActive != true) {
            failEarly("There is no server to restart.")
            return
        }
        if (targetId != null) currentInstanceId = targetId
        dispatchExclusive { restartExclusive(targetId) }
    }

    private fun restartExclusive(instanceId: String?) {
        // A restart is authoritative: no Start queued for an earlier stop may
        // survive into (or after) it - only the stop completion path may consume
        // a queued start.
        pendingStartInstanceId = null
        val status = currentRuntime()
        val wasActive = status?.isActive == true
        if (wasActive) sawActiveRuntime = true
        val decision = ServiceLifecycleDecisions.decideRestart(
            runtimeIsActive = wasActive,
            runtimeState = status?.state,
            hasSession = hasActiveSession()
        )
        when (decision) {
            RestartDecision.RESTART_EXISTING ->
                restartExistingExclusive(instanceId, status)
            RestartDecision.CLEAR_STALE_THEN_START,
            RestartDecision.START_FRESH -> {
                if (instanceId == null) {
                    failEarly("There is no server to restart.")
                    finishOperation()
                } else {
                    runRestartFreshOperation(
                        instanceId = instanceId,
                        clearStaleFirst = decision == RestartDecision.CLEAR_STALE_THEN_START
                    )
                }
            }
        }
    }

    /**
     * Restart by asking the controller to stop+start the same instance.
     *
     * The whole stop→start cycle stays inside one exclusive command and
     * [restartInFlight] is held until it completes, so the transient terminal
     * `STOPPED` published between the halves is ignored by the runtime observer
     * instead of finishing the service (that was the broken Restart button). The
     * service only finishes if no new session becomes active.
     */
    private fun restartExistingExclusive(instanceId: String?, before: ServerRuntimeStatus?) {
        restartInFlight = true
        startObserving()
        postStatus(force = true)
        scope().launch {
            try {
                // Keep the wake locks across the stop→start gap: the child JVM
                // briefly disappears, but the user asked for it back immediately.
                wakeLocks.acquire()
                withContext(Dispatchers.IO) {
                    runCatching { container.server.restart() }
                        .onFailure { Log.w(TAG, "Restart failed", it) }
                }
                when (awaitRestartOutcome(before)) {
                    RestartOutcome.SUCCEEDED -> {
                        // The restart succeeded. Clear the guard before the final
                        // status so a crash in the same instant still runs the
                        // normal post-mortem instead of being ignored as transient.
                        restartInFlight = false
                        sawActiveRuntime = true
                        silentMedia.start()
                        postStatus(force = true)
                        val latest = currentRuntime()
                        if (latest != null && !latest.isActive) handleFinished(latest)
                    }
                    RestartOutcome.FAILED -> {
                        wakeLocks.release()
                        silentMedia.stop()
                        val id = instanceId ?: currentInstanceId
                        notifications.postStartRejected(
                            "The server could not be restarted. Check the console for details.",
                            instanceName(id),
                            contentIntent(id),
                            restartServiceIntent(id)
                        )
                        finishService()
                    }
                }
            } finally {
                // Defensive: a Start pressed while a restart's stop phase looked
                // like a normal STOPPING must not outlive the restart.
                pendingStartInstanceId = null
                restartInFlight = false
                finishOperation()
            }
        }
    }

    /** Fresh start for a restart command; a stale runtime is cleared first when needed. */
    private fun runRestartFreshOperation(instanceId: String, clearStaleFirst: Boolean) {
        restartInFlight = true
        scope().launch {
            try {
                if (clearStaleFirst) clearStaleRuntime()
                val outcome = startServer(instanceId)
                // The restart is over once start() returned: let the normal start
                // post-mortem handle an immediate crash (alert/auto-restart)
                // instead of suppressing it behind the restart guard.
                restartInFlight = false
                handleStartOutcome(outcome)
            } finally {
                // Defensive: never let a queued start outlive the restart.
                pendingStartInstanceId = null
                restartInFlight = false
                finishOperation()
            }
        }
    }

    private enum class RestartOutcome { SUCCEEDED, FAILED }

    /**
     * Waits (bounded by [RESTART_WAIT_MS]) for the restart's new session to
     * become active. The pre-restart RUNNING snapshot must not count as success,
     * so a changed identity (pid or startedAt) is required. A CRASHED state fails
     * fast; on timeout the manager's session flag is the tie-breaker so a slow
     * OEM start is not torn down.
     */
    private suspend fun awaitRestartOutcome(before: ServerRuntimeStatus?): RestartOutcome {
        val matched = withTimeoutOrNull(RESTART_WAIT_MS) {
            container.server.runtime.first { status ->
                when {
                    status.state == RunState.CRASHED -> true
                    status.state == RunState.STOPPING -> false
                    status.isActive -> hasNewSessionIdentity(status, before)
                    // Else: the transient STOPPED between the stop and start
                    // halves of the restart — keep waiting for the new session.
                    else -> false
                }
            }
        }
        if (matched == null) {
            return if (hasActiveSession()) {
                Log.w(TAG, "Restart did not publish an active state in time; a session exists, adopting it")
                RestartOutcome.SUCCEEDED
            } else {
                Log.w(TAG, "Restart did not start a new session within ${RESTART_WAIT_MS}ms")
                RestartOutcome.FAILED
            }
        }
        if (matched.state == RunState.CRASHED) {
            Log.w(TAG, "Restart produced a crashed session")
            return RestartOutcome.FAILED
        }
        return RestartOutcome.SUCCEEDED
    }

    /** True when [status] describes a different session than the pre-restart [before]. */
    private fun hasNewSessionIdentity(
        status: ServerRuntimeStatus,
        before: ServerRuntimeStatus?
    ): Boolean {
        if (before == null) return true
        return status.pid != before.pid || status.startedAt != before.startedAt
    }

    /**
     * Sticky restart with a null intent: adopt a still-running server, or
     * surface the persisted crash and stop.
     */
    private fun adoptExistingSession() {
        val runtime = runCatching { container.server.runtime.value }.getOrNull()
        if (runtime?.isActive == true) {
            adopt(runtime)
            return
        }
        val exit = runtime?.lastExit ?: runCatching { container.server.activeLastCrash() }.getOrNull()
        if (runtime?.state == RunState.CRASHED || exit?.expected == false) {
            val id = runtime?.instanceId ?: currentInstanceId
                ?: runCatching { container.settings.current().lastActiveInstanceId }.getOrNull()
            notifications.postCrashAlert(
                title = "Server stopped unexpectedly",
                text = exit?.summary?.takeIf { it.isNotBlank() }
                    ?: "The server process exited. Tap to check the console.",
                contentIntent = contentIntent(id),
                restartIntent = restartServiceIntent(id)
            )
        }
        finishService()
    }

    private fun adopt(runtime: ServerRuntimeStatus) {
        currentInstanceId = runtime.instanceId ?: currentInstanceId
        sawActiveRuntime = true
        wakeLocks.acquire()
        silentMedia.start()
        startObserving()
        postStatus(force = true)
    }

    private fun failEarly(message: String) {
        notifications.postStartRejected(
            message = message,
            instanceName = instanceName(currentInstanceId),
            contentIntent = contentIntent(currentInstanceId),
            restartIntent = restartServiceIntent(currentInstanceId)
        )
        finishService()
    }

    /* ------------------------------------------------------------------ */
    /* Runtime observation                                                */
    /* ------------------------------------------------------------------ */

    private fun startObserving() {
        ensureScope()
        if (runtimeObserver?.isActive != true) {
            runtimeObserver = scope().launch {
                container.server.runtime.collect { status ->
                    lastObservedState = status.state
                    if (status.state == RunState.RUNNING) {
                        // A healthy boot ends the consecutive-crash budget.
                        autoRestartAttempts = 0
                        manualStopRequested = false
                    }
                    if (status.isActive) {
                        sawActiveRuntime = true
                        postStatus(force = false)
                    } else if (sawActiveRuntime && !operationInProgress.get() && !restartInFlight) {
                        handleFinished(status)
                    }
                }
            }
        }
        if (tickerJob?.isActive != true) {
            tickerJob = scope().launch {
                while (isActive) {
                    delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                    if (finishing.get()) continue
                    // While waiting to auto-restart, keep the dedicated
                    // "Restarting automatically" notification instead of
                    // overwriting it with the crashed runtime snapshot.
                    if (autoRestartJob?.isActive == true) continue
                    val status = runCatching { container.server.runtime.value }.getOrNull() ?: continue
                    // Apply the silent-media toggle live while a server runs, so
                    // the user does not need to restart the instance.
                    if (sawActiveRuntime && status.isActive) {
                        val settings = runCatching { container.settings.current() }.getOrNull()
                        if (settings?.silentMediaHack == true) silentMedia.start() else silentMedia.stop()
                    }
                    // A stop already posted its "Shutting down…" notification;
                    // repeating it every 2 s looks like notification spam and
                    // made a slow stop feel broken.
                    if (status.state == RunState.STOPPING) continue
                    if (sawActiveRuntime && (status.isActive || status.state != RunState.STOPPED)) {
                        postStatus(force = true)
                    } else if (status.isActive) {
                        postStatus(force = false)
                    }
                }
            }
        }
    }

    private fun handleFinished(status: ServerRuntimeStatus) {
        if (finishing.get()) return
        // A restart owns the transient terminal state published between its stop
        // and start halves; treating it as "finished" here is the old Restart bug
        // that tore the service (and the process-wide manager) down mid-restart.
        if (restartInFlight) return
        // A pending auto-restart already owns this crash; nothing else to do.
        if (autoRestartJob?.isActive == true) return
        val crashed = status.state == RunState.CRASHED || status.lastExit?.expected == false
        silentMedia.stop()
        wakeLocks.release()
        if (crashed) {
            val instanceId = status.instanceId ?: currentInstanceId
            if (canAutoRestart(instanceId)) {
                if (autoRestartAttempts < MAX_AUTO_RESTART_ATTEMPTS) {
                    scheduleAutoRestart(instanceId!!)
                    return
                }
                notifications.postCrashAlert(
                    title = "Server keeps crashing",
                    text = "Automatic restart limit reached ($MAX_AUTO_RESTART_ATTEMPTS attempts). Tap to inspect the console.",
                    contentIntent = contentIntent(instanceId),
                    restartIntent = restartServiceIntent(instanceId)
                )
            } else {
                val exit = status.lastExit ?: runCatching { container.server.activeLastCrash() }.getOrNull()
                notifications.postCrashAlert(
                    title = "Server stopped unexpectedly",
                    text = exit?.summary?.takeIf { it.isNotBlank() }
                        ?: "The server process exited. Tap to check the console.",
                    contentIntent = contentIntent(status.instanceId ?: currentInstanceId),
                    restartIntent = restartServiceIntent(status.instanceId ?: currentInstanceId)
                )
            }
        }
        finishService()
    }

    /** True when the crashed instance opted into automatic restarts. */
    private fun canAutoRestart(instanceId: String?): Boolean {
        if (manualStopRequested) return false
        if (instanceId == null) return false
        val instance = runCatching { container.instances.get(instanceId) }.getOrNull()
            ?: return false
        val settings = runCatching { container.settings.current() }.getOrNull()
        return instance.autoRestart || settings?.autoRestartOnCrash == true
    }

    /**
     * Keeps the service in the foreground, shows a visible "attempt n/3"
     * notification and retries the same instance once after a short delay.
     * The attempt budget is reset by a healthy RUNNING state or a manual
     * start/stop/restart (see [beginStart], [beginStop], [beginRestart]).
     */
    private fun scheduleAutoRestart(instanceId: String) {
        if (autoRestartJob?.isActive == true) return
        autoRestartAttempts++
        currentInstanceId = instanceId
        val attempt = autoRestartAttempts
        val name = instanceName(instanceId)
        runCatching {
            notifications.postStatus(
                notifications.buildAutoRestart(
                    instanceName = name,
                    attempt = attempt,
                    maxAttempts = MAX_AUTO_RESTART_ATTEMPTS,
                    contentIntent = contentIntent(instanceId),
                    stopIntent = stopServiceIntent()
                )
            )
            notifications.postAutoRestartAlert(
                instanceName = name,
                attempt = attempt,
                maxAttempts = MAX_AUTO_RESTART_ATTEMPTS,
                contentIntent = contentIntent(instanceId)
            )
        }.onFailure { Log.w(TAG, "Could not post auto-restart notification", it) }

        autoRestartJob = scope().launch {
            delay(AUTO_RESTART_DELAY_MS)
            if (finishing.get() || manualStopRequested) return@launch
            wakeLocks.acquire()
            val result = withContext(Dispatchers.IO) {
                runCatching { container.server.start(instanceId) }
            }
            val startResult = result.getOrNull()
            if (startResult == null) {
                notifications.postStartRejected(
                    result.exceptionOrNull()?.message ?: "Automatic restart failed.",
                    instanceName(instanceId),
                    contentIntent(instanceId),
                    restartServiceIntent(instanceId)
                )
                finishService()
                return@launch
            }
            if (startResult is StartResult.Rejected) {
                notifications.postStartRejected(
                    startResult.message,
                    instanceName(instanceId),
                    contentIntent(instanceId),
                    restartServiceIntent(instanceId)
                )
                finishService()
                return@launch
            }
            sawActiveRuntime = true
            silentMedia.start()
            notifications.cancelAlert()
            postStatus(force = true)
            // Clear the handle before re-evaluating: an immediate second crash
            // must be allowed to schedule the next attempt.
            autoRestartJob = null
            val latest = runCatching { container.server.runtime.value }.getOrNull()
            if (latest != null && !latest.isActive) handleFinished(latest)
        }
    }

    /** Cancels a pending automatic restart (manual start/stop/restart/destroy). */
    private fun cancelAutoRestart() {
        autoRestartJob?.cancel()
        autoRestartJob = null
    }

    /* ------------------------------------------------------------------ */
    /* Notification plumbing                                              */
    /* ------------------------------------------------------------------ */

    private fun ensureForeground(): Boolean {
        if (foregroundPosted) return true
        val notification = notifications.buildStarting(
            instanceName = null,
            contentIntent = contentIntent(currentInstanceId),
            stopIntent = stopServiceIntent()
        )
        return try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, ServerNotifications.ID_STATUS, notification, type)
            foregroundPosted = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground rejected", t)
            stopSelf()
            false
        }
    }

    private fun postStatus(force: Boolean) {
        if (finishing.get()) return
        val status = runCatching { container.server.runtime.value }.getOrNull() ?: return
        // While a start is in flight the controller may still report the previous
        // instance; prefer the id the command told us about.
        val id = if (status.isActive) {
            status.instanceId ?: currentInstanceId
        } else {
            currentInstanceId ?: status.instanceId
        }
        val instance = id?.let { runCatching { container.instances.get(it) }.getOrNull() }
        val name = instance?.name ?: status.instanceName ?: instanceName(id)
        val isMinecraft = instance?.isMinecraft ?: (status.playersMax > 0)
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < MIN_NOTIFICATION_INTERVAL_MS) return
        lastNotificationAt = now
        // The controller usually supplies device RAM; only fall back to an
        // ActivityManager read when it has not reported numbers yet.
        val fallbackAvailMb = if (status.deviceAvailMb > 0) {
            status.deviceAvailMb
        } else {
            runCatching { container.memory.availRamMb() }.getOrDefault(0L)
        }
        val fallbackTotalMb = if (status.deviceTotalMb > 0) {
            status.deviceTotalMb
        } else {
            runCatching { container.memory.totalRamMb() }.getOrDefault(0L)
        }
        runCatching {
            notifications.postStatus(
                notifications.buildStatus(
                    status = status,
                    instanceName = name,
                    isMinecraft = isMinecraft,
                    fallbackDeviceAvailMb = fallbackAvailMb,
                    fallbackDeviceTotalMb = fallbackTotalMb,
                    contentIntent = contentIntent(id),
                    stopIntent = stopServiceIntent(),
                    restartIntent = restartServiceIntent(id),
                    forceStopIntent = forceStopServiceIntent()
                )
            )
        }.onFailure { Log.w(TAG, "Could not update status notification", it) }
    }

    private fun contentIntent(instanceId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!instanceId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_INSTANCE_ID, instanceId)
            }
            putExtra(MainActivity.EXTRA_OPEN_CONSOLE, true)
        }
        return PendingIntent.getActivity(this, REQUEST_CONTENT, intent, PENDING_FLAGS)
    }

    private fun stopServiceIntent(): PendingIntent {
        val intent = Intent(this, ForegroundServerService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_FORCE, false)
        }
        return PendingIntent.getForegroundService(this, REQUEST_STOP, intent, PENDING_FLAGS)
    }

    private fun forceStopServiceIntent(): PendingIntent {
        val intent = Intent(this, ForegroundServerService::class.java).apply {
            action = ACTION_FORCE_STOP
        }
        return PendingIntent.getForegroundService(this, REQUEST_FORCE_STOP, intent, PENDING_FLAGS)
    }

    private fun restartServiceIntent(instanceId: String?): PendingIntent {
        val intent = Intent(this, ForegroundServerService::class.java).apply {
            action = ACTION_RESTART
            if (!instanceId.isNullOrBlank()) putExtra(EXTRA_INSTANCE_ID, instanceId)
        }
        return PendingIntent.getForegroundService(this, REQUEST_RESTART, intent, PENDING_FLAGS)
    }

    private fun finishService() {
        if (!finishing.compareAndSet(false, true)) return
        // Drop any command still queued behind the operation that is finishing:
        // this service generation is going away and a revived instance must not
        // dispatch it (a stale Stop would kill the fresh server).
        pendingCommand = null
        cancelAutoRestart()
        silentMedia.stop()
        wakeLocks.release()
        runtimeObserver?.cancel()
        tickerJob?.cancel()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundPosted = false
        notifications.cancelStatus()
        stopSelf()
    }

    /* ------------------------------------------------------------------ */
    /* Serialization helpers                                              */
    /* ------------------------------------------------------------------ */

    private fun dispatchExclusive(command: () -> Unit) {
        if (finishing.get()) return
        if (operationInProgress.get()) {
            pendingCommand = command
            return
        }
        operationInProgress.set(true)
        command()
    }

    private fun finishOperation() {
        operationInProgress.set(false)
        if (finishing.get()) return
        val next = pendingCommand
        pendingCommand = null
        next?.let { dispatchExclusive(it) }
    }

    private fun ensureScope() {
        if (serviceScope == null) {
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    private fun scope(): CoroutineScope = serviceScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { serviceScope = it }

    private fun instanceName(instanceId: String?): String? =
        instanceId?.let { runCatching { container.instances.get(it)?.name }.getOrNull() }

    /** Current runtime snapshot, or null when the controller is unavailable. */
    private fun currentRuntime(): ServerRuntimeStatus? =
        runCatching { container.server.runtime.value }.getOrNull()

    /**
     * The controller's truthful "a child JVM exists" flag, independent of the
     * published runtime snapshot; false on any failure.
     */
    private fun hasActiveSession(): Boolean =
        runCatching { container.server.hasActiveSession() }.getOrDefault(false)

    /* ------------------------------------------------------------------ */
    /* Public entry points used by the UI                                 */
    /* ------------------------------------------------------------------ */

    companion object {
        private const val TAG = "ForegroundServerService"

        const val ACTION_START = "dev.javahost.action.START_SERVER"
        const val ACTION_STOP = "dev.javahost.action.STOP_SERVER"
        const val ACTION_RESTART = "dev.javahost.action.RESTART_SERVER"
        const val ACTION_FORCE_STOP = "dev.javahost.action.FORCE_STOP_SERVER"
        const val EXTRA_INSTANCE_ID = "instance_id"
        private const val EXTRA_FORCE = "force"

        private const val REQUEST_CONTENT = 41
        private const val REQUEST_STOP = 42
        private const val REQUEST_FORCE_STOP = 43
        private const val REQUEST_RESTART = 43

        private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_NOTIFICATION_INTERVAL_MS = 1_500L

        /** Wait before retrying a crashed server so a hot crash loop cannot spin. */
        private const val AUTO_RESTART_DELAY_MS = 5_000L

        /**
         * Cap for waiting for a graceful stop to publish a terminal state. The
         * manager's worst case is ~11 s (8 s Minecraft save + 2 s SIGTERM grace +
         * SIGKILL); the old 40 s wait made a slow stop feel broken.
         */
        private const val GRACEFUL_STOP_WAIT_MS = 20_000L

        /** Cap for waiting for a forced stop to publish a terminal state. */
        private const val FORCE_STOP_WAIT_MS = 15_000L

        /** Bounded wait for a zombie runtime to clear before a fresh start. */
        private const val STALE_CLEAR_WAIT_MS = 3_000L

        /**
         * Cap for a restart to publish a new active session. Generous on purpose:
         * a slow device must not be mistaken for a failed restart (which would
         * finish the service and could shut the just-started JVM down).
         */
        private const val RESTART_WAIT_MS = 45_000L

        /** Consecutive automatic restarts allowed without a healthy RUNNING state. */
        const val MAX_AUTO_RESTART_ATTEMPTS = 3

        /** Starts (or adopts) the hosted server inside the foreground service. */
        fun start(context: Context, instanceId: String) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            startServiceSafely(context, intent)
        }

        /** Stops the hosted server; [force] destroys the JVM without the graceful save. */
        fun stop(context: Context, force: Boolean = false) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_FORCE, force)
            }
            startServiceSafely(context, intent)
        }

        /** Restarts the currently active server (or starts it again after a crash). */
        fun forceStop(context: Context) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_FORCE_STOP
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_RESTART
            }
            startServiceSafely(context, intent)
        }

        private fun startServiceSafely(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                // Android 12+ may refuse a background FGS start; never crash the
                // caller (the UI can surface a message and retry from an Activity).
                Log.e(TAG, "Could not start ForegroundServerService", t)
            }
        }
    }
}
