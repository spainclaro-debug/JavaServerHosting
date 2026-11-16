package dev.javahost.core.service

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
import dev.javahost.core.api.ServerController
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Special-use foreground service that owns the lifetime of up to
 * [ServerController.MAX_RUNNING_SERVERS] hosted child JVMs.
 *
 * Responsibilities:
 *  - satisfy the 5-second rule: [startForeground] is called as the very first
 *    thing in [onStartCommand], including for a null (sticky re-delivery) intent;
 *  - stay in the foreground while **any** instance is active, and finish only
 *    when no session is left (plus no queued/in-flight work);
 *  - mirror the focused instance into an ongoing notification (state, uptime,
 *    players, heap, free RAM, "N servers running") with Stop focused, Restart
 *    focused and Stop all actions;
 *  - keep per-instance crash alerts and a per-instance auto-restart budget (max
 *    [MAX_AUTO_RESTART_ATTEMPTS] consecutive attempts, [AUTO_RESTART_DELAY_MS]
 *    apart); a manual stop of one instance only cancels that instance's restart;
 *  - hold [WakeLocks] / silent media while at least one child necessarily exists
 *    or a lifecycle operation is still in flight;
 *  - keep the 1.4.x lifecycle guarantees, now per instance: Start during a Stop
 *    of the same instance queues and runs right after it, a restart is one
 *    exclusive operation per instance whose transient terminal state is ignored,
 *    and a stale `isActive` runtime without a session is force-cleared first;
 *  - never dispose the process-wide controller on *idle* teardown: [onDestroy]
 *    calls `container.server.shutdown()` only when
 *    `hasAnyActiveSession()` is still true.
 *
 * All mutable lifecycle bookkeeping below (operation queues, focus, restart
 * guards) is confined to the main thread; the controller's `StateFlow`s are
 * collected on the main dispatcher and every child-JVM call is dispatched to
 * [Dispatchers.IO].
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

    /* ------------------------------------------------------------------ */
    /* Per-instance lifecycle bookkeeping (main thread only)              */
    /* ------------------------------------------------------------------ */

    /** Instances with a service lifecycle operation currently running. */
    private val operationsInFlight = HashSet<String>()

    /** Command waiting for the current operation of that same instance. */
    private val pendingCommands = LinkedHashMap<String, () -> Unit>()

    /** Starts requested while the same instance was still stopping. */
    private val queuedStarts = LinkedHashSet<String>()

    /** Instances whose restart operation is currently running (transient stop ignored). */
    private val restartInFlight = HashSet<String>()

    /** Consecutive automatic restarts since the last healthy RUNNING state, per instance. */
    private val autoRestartAttempts = HashMap<String, Int>()
    private val autoRestartJobs = HashMap<String, Job>()

    /** Instances the user asked to stop; a crash racing with that stop cannot auto-restart them. */
    private val manualStopRequested = HashSet<String>()

    /** Instances already observed active, used to detect active->terminal transitions. */
    private val activeSeen = HashSet<String>()

    private var focusedInstanceId: String? = null
    private var lastNotificationAt = 0L
    private var foregroundPosted = false
    private val finishing = AtomicBoolean(false)

    /**
     * Service generation. Bumped when the service finishes or is revived while a
     * stopSelf() is still pending; operation continuations from an older
     * generation are ignored by [finishOperation] so they can never mutate (or
     * finish) the revived generation.
     */
    private var generation = 0

    /** Generation that owns each in-flight per-instance operation. */
    private val operationTokens = HashMap<String, Int>()

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
        val extraInstanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID)?.takeIf { it.isNotBlank() }

        // A command can race with stopSelf() (e.g. the user taps Start right
        // after Stop). Revive the instance instead of silently dropping it.
        if (finishing.get()) {
            finishing.set(false)
            foregroundPosted = false
            runtimeObserver = null
            tickerJob = null
            // Retire the previous generation: cancel its coroutines (if still
            // running) and make sure nothing it queued or is still unwinding can
            // mutate this one (a stale Stop would kill the fresh server, a stale
            // Start would spawn an unexpected one).
            generation++
            serviceScope?.cancel()
            serviceScope = null
            resetOperationState()
            resetAutoRestartState()
            manualStopRequested.clear()
            activeSeen.clear()
        }

        // 5-second rule: everything before this line must stay trivial. The
        // placeholder notification deliberately does not touch the container.
        if (!ensureForeground()) return START_NOT_STICKY
        ensureScope()

        when (action) {
            ACTION_START -> {
                if (extraInstanceId == null) {
                    failEarly("No server instance was specified.")
                } else {
                    beginStart(extraInstanceId)
                }
            }
            ACTION_STOP -> beginStop(
                instanceId = resolveInstanceId(extraInstanceId),
                force = intent?.getBooleanExtra(EXTRA_FORCE, false) == true
            )
            ACTION_STOP_ALL -> beginStopAll()
            ACTION_RESTART -> beginRestart(resolveInstanceId(extraInstanceId))
            ACTION_FORCE_STOP -> beginForceStop(resolveInstanceId(extraInstanceId))
            ACTION_FORCE_STOP_ALL -> beginForceStopAll()
            else -> adoptExistingSession()
        }
        return START_STICKY
    }

    /**
     * The task being swiped away must not kill the hosted servers: the service
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
     * This distinction is load-bearing (1.4.0 guarantee): the foreground service
     * is destroyed after the last server's normal Stop, and
     * `ServerController.shutdown()` disposes the singleton (the manager sets
     * `disposed`, cancels its scope and latches `shutdownOnce`). Calling it
     * unconditionally used to poison every later Start with "JavaHost is
     * shutting down." until the whole app process was killed. When idle, the
     * controller is intentionally left usable for the next foreground-service
     * instance.
     */
    override fun onDestroy() {
        finishing.set(true)
        generation++
        resetAutoRestartState()
        manualStopRequested.clear()
        activeSeen.clear()
        runtimeObserver?.cancel()
        tickerJob?.cancel()
        serviceScope?.cancel()
        runtimeObserver = null
        tickerJob = null
        resetOperationState()
        runCatching { silentMedia.stop() }
        runCatching { wakeLocks.release() }
        // A child JVM still alive (system-initiated destruction, crash alert
        // while running, app shutdown) must never be left behind; an idle
        // teardown must not dispose the manager.
        val sessionActive = runCatching { container.server.hasAnyActiveSession() }.getOrDefault(false)
        val runtimeActive = runCatching { container.server.runtimes.value.values.any { it.isActive } }
            .getOrDefault(false)
        if (sessionActive || runtimeActive) {
            runCatching { container.server.shutdown() }
        } else {
            Log.i(TAG, "Service destroyed while idle; keeping the process-wide controller usable")
        }
        // Belt and braces: whether we stopped ourselves or the system destroyed
        // us, never leave the ongoing foreground notification behind. Alerts stay
        // cancellable by the user on purpose.
        runCatching { notifications.cancelStatus() }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foregroundPosted = false
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* Actions: Start                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Bookkeeping shared by every deliberate manual start of [instanceId]: a
     * fresh crash-loop budget, clearing the "user asked to stop" flag, cancelling
     * that instance's pending auto-restart and superseded queued start, and
     * dismissing the alert notification. Never touches the other instances.
     */
    private fun prepareManualStart(instanceId: String) {
        autoRestartAttempts.remove(instanceId)
        manualStopRequested.remove(instanceId)
        cancelAutoRestart(instanceId)
        queuedStarts.remove(instanceId)
        notifications.cancelAlert()
    }

    private fun beginStart(instanceId: String) {
        val stopWasRequested = manualStopRequested.contains(instanceId)
        prepareManualStart(instanceId)

        val status = currentRuntimes()[instanceId]
        val decision = ServiceLifecycleDecisions.decideStart(
            runtimeIsActive = status?.isActive == true,
            runtimeState = status?.state,
            hasSession = hasActiveSession(instanceId)
        )
        when (decision) {
            StartDecision.ADOPT_RUNNING -> {
                // A Stop of this instance may have been issued a split second ago
                // while its runtime still reads RUNNING (STOPPING is published
                // asynchronously). Queue the start so it is not lost when the stop
                // wins; a restart in flight already brings the server back, so it
                // must not queue (a stale id would later restart the server
                // unexpectedly after a manual stop).
                if (stopWasRequested) {
                    queuedStarts.add(instanceId)
                    Log.i(TAG, "Stop of $instanceId in progress; start queued until it finishes")
                    if (!operationsInFlight.contains(instanceId)) {
                        // A stale STOPPING/stop flag without an actual stop
                        // operation would otherwise strand the queued start.
                        dispatchExclusive(instanceId) { stopExclusive(instanceId, force = false) }
                    }
                } else {
                    // Do not spawn a second JVM; adopt the running one.
                    adopt(instanceId)
                }
            }
            StartDecision.QUEUE_AFTER_STOP -> {
                queuedStarts.add(instanceId)
                Log.i(TAG, "Stop of $instanceId in progress; start queued until it finishes")
                if (!operationsInFlight.contains(instanceId)) {
                    // The STOPPING state was published outside this service's stop
                    // operation (e.g. by a stale teardown). Drive the stop to
                    // completion so the queued start cannot be stranded.
                    dispatchExclusive(instanceId) { stopExclusive(instanceId, force = false) }
                }
            }
            StartDecision.CLEAR_STALE_THEN_START -> {
                // The runtime claims to be active but no child JVM exists: clear
                // the zombie snapshot, then start fresh.
                dispatchExclusive(instanceId) { clearStaleAndStartExclusive(instanceId) }
            }
            StartDecision.START_FRESH -> dispatchExclusive(instanceId) { startExclusive(instanceId) }
        }
    }

    /** What [startServer] did, so callers can keep the notification/auto-restart flow. */
    private enum class StartOutcome {
        STARTED,

        /** Rejection already reported and the per-instance bookkeeping cleaned up. */
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
     * Runs a start inside the current exclusive operation of [instanceId] and
     * releases that operation in `finally`; a command queued while it ran is
     * dispatched by [finishOperation].
     */
    private fun runStartOperation(instanceId: String, clearStaleFirst: Boolean) {
        scope().launch {
            try {
                if (clearStaleFirst) clearStaleRuntime(instanceId)
                handleStartOutcome(instanceId, startServer(instanceId))
            } finally {
                finishOperation(instanceId)
            }
        }
    }

    /** Best-effort removal of a runtime that claims to be active without a session. */
    private suspend fun clearStaleRuntime(instanceId: String) {
        withContext(Dispatchers.IO) {
            runCatching { container.server.forceStop(instanceId) }
                .onFailure { Log.w(TAG, "Could not clear the stale session of $instanceId", it) }
        }
        withTimeoutOrNull(STALE_CLEAR_WAIT_MS) {
            container.server.runtimes.first { statuses -> statuses[instanceId]?.isActive != true }
        }
    }

    /**
     * Spawns [instanceId] and keeps notifications/locks in sync.
     *
     * Rejections are reported and (when no other server is left) the service
     * finishes; other instances are never affected. A successful start whose
     * runtime is already terminal is returned as
     * [StartOutcome.EXITED_IMMEDIATELY] so a normal Start can run the crash
     * post-mortem while a Restart can report the restart as failed.
     */
    private suspend fun startServer(instanceId: String): StartOutcome {
        startObserving()
        syncRuntimeResources()
        postStatus(force = true)
        val result = withContext(Dispatchers.IO) {
            runCatching { container.server.start(instanceId) }
        }
        val startResult = result.getOrElse { error ->
            Log.e(TAG, "Server start of $instanceId threw", error)
            val current = currentRuntimes()[instanceId]
            if (current?.isActive == true) {
                // A concurrent (auto) restart won the race: adopt it instead of
                // tearing down a live server.
                adopt(instanceId)
                return StartOutcome.STARTED
            }
            rejectStart(instanceId, error.message ?: "The server could not be started.")
            return StartOutcome.REJECTED
        }
        return when (startResult) {
            is StartResult.Ok -> {
                activeSeen.add(instanceId)
                syncRuntimeResources()
                postStatus(force = true)
                // The child can exit between start() returning and the observer's
                // first collect; report that race instead of leaving a stale
                // "Starting" foreground service behind.
                val status = currentRuntimes()[instanceId]
                if (status == null || status.isActive) StartOutcome.STARTED
                else StartOutcome.EXITED_IMMEDIATELY
            }
            is StartResult.Rejected -> {
                val current = currentRuntimes()[instanceId]
                if (current?.isActive == true) {
                    adopt(instanceId)
                    StartOutcome.STARTED
                } else {
                    rejectStart(instanceId, startResult.message)
                    StartOutcome.REJECTED
                }
            }
        }
    }

    private fun rejectStart(instanceId: String, message: String) {
        notifications.postStartRejected(
            message,
            instanceName(instanceId),
            contentIntent(instanceId),
            restartServiceIntent(instanceId)
        )
        maybeFinishService()
    }

    private fun handleStartOutcome(instanceId: String, outcome: StartOutcome) {
        when (outcome) {
            StartOutcome.STARTED, StartOutcome.REJECTED -> Unit
            StartOutcome.EXITED_IMMEDIATELY -> {
                val status = currentRuntimes()[instanceId]
                if (status != null) handleInstanceFinished(instanceId, status) else maybeFinishService()
            }
        }
    }

    /** Adopts an already-live instance instead of spawning a second JVM. */
    private fun adopt(instanceId: String) {
        activeSeen.add(instanceId)
        startObserving()
        syncRuntimeResources()
        postStatus(force = true)
    }

    /* ------------------------------------------------------------------ */
    /* Actions: Stop                                                      */
    /* ------------------------------------------------------------------ */

    private fun beginStop(instanceId: String?, force: Boolean) {
        if (instanceId == null) {
            maybeFinishService()
            return
        }
        // A manual stop of one instance is final for that instance only: cancel
        // its pending auto-restart, drop a Start queued behind its stop, and make
        // sure a crash racing with the stop cannot auto-restart it.
        manualStopRequested.add(instanceId)
        cancelAutoRestart(instanceId)
        queuedStarts.remove(instanceId)
        dispatchExclusive(instanceId) { stopExclusive(instanceId, force) }
    }

    private fun stopExclusive(instanceId: String, force: Boolean) {
        val status = currentRuntimes()[instanceId]
        val wasActive = status?.isActive == true
        startObserving()
        syncRuntimeResources()
        postStatus(force = true)
        scope().launch {
            try {
                if (wasActive) {
                    withContext(Dispatchers.IO) {
                        runCatching { container.server.stop(instanceId, force = force) }
                            .onFailure { error ->
                                Log.w(TAG, "Graceful stop of $instanceId failed; forcing", error)
                                runCatching { container.server.stop(instanceId, force = true) }
                            }
                    }
                    // Publish the "Stopping…" state once, now that the controller
                    // has flipped this instance to STOPPING (the ticker deliberately
                    // skips STOPPING so the notification cannot loop on it).
                    postStatus(force = true)
                    // Wait for the controller to actually publish a terminal state
                    // for this instance. stop() is asynchronous: it only published
                    // STOPPING, which used to leave the FGS notification looping on
                    // "Stopping…" forever. The caps match the manager's ~11 s
                    // worst-case shutdown (8 s save + 2 s SIGTERM grace + SIGKILL).
                    val stopped = withTimeoutOrNull(
                        if (force) FORCE_STOP_WAIT_MS else GRACEFUL_STOP_WAIT_MS
                    ) {
                        container.server.runtimes.first { statuses ->
                            statuses[instanceId]?.isActive != true
                        }
                    } != null
                    val stillActive = !stopped && currentRuntimes()[instanceId]?.isActive == true
                    if (stillActive) {
                        // Last resort: pid-level kill + terminal state reset. The
                        // other sessions are untouched.
                        Log.w(TAG, "Stop of $instanceId did not complete; forcing session reset")
                        runCatching { container.server.forceStop(instanceId) }
                        withTimeoutOrNull(STALE_CLEAR_WAIT_MS) {
                            container.server.runtimes.first { statuses ->
                                statuses[instanceId]?.isActive != true
                            }
                        }
                        notifications.postCrashAlert(
                            title = "Server stop forced",
                            text = "The server did not stop gracefully and was force-stopped. " +
                                "Check its console for errors.",
                            contentIntent = contentIntent(instanceId),
                            restartIntent = restartServiceIntent(instanceId)
                        )
                    } else {
                        // The user asked for the stop, so any pending auto-restart
                        // alert of this instance no longer describes reality.
                        notifications.cancelAlert()
                    }
                }
                // A Start pressed while this same instance was stopping must not
                // be dropped: queue it in the same service generation (no
                // teardown between), then finishOperation dispatches it.
                if (queuedStarts.remove(instanceId)) {
                    prepareManualStart(instanceId)
                    if (!pendingCommands.containsKey(instanceId)) {
                        Log.i(TAG, "Stop of $instanceId finished; starting queued server")
                        pendingCommands[instanceId] = { startExclusive(instanceId) }
                    }
                }
            } finally {
                syncRuntimeResources()
                finishOperation(instanceId)
            }
        }
    }

    private fun beginStopAll() {
        queuedStarts.clear()
        val ids = activeInstanceIds()
        if (ids.isEmpty()) {
            // Stale runtimes without a session can still exist; clear them too.
            if (hasAnyActiveSession()) {
                beginForceStopAll()
            } else {
                maybeFinishService()
            }
            return
        }
        for (instanceId in ids) {
            manualStopRequested.add(instanceId)
            cancelAutoRestart(instanceId)
            dispatchExclusive(instanceId) { stopExclusive(instanceId, force = false) }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Actions: Force stop                                                */
    /* ------------------------------------------------------------------ */

    private fun beginForceStop(instanceId: String?) {
        if (instanceId == null) {
            beginForceStopAll()
            return
        }
        manualStopRequested.add(instanceId)
        cancelAutoRestart(instanceId)
        queuedStarts.remove(instanceId)
        // A force stop is final for this instance: drop its queued command too.
        pendingCommands.remove(instanceId)
        scope().launch {
            runCatching { container.server.forceStop(instanceId) }
            syncRuntimeResources()
            postStatus(force = true)
            maybeFinishService()
        }
    }

    private fun beginForceStopAll() {
        for (instanceId in currentRuntimes().keys) {
            manualStopRequested.add(instanceId)
            cancelAutoRestart(instanceId)
        }
        queuedStarts.clear()
        pendingCommands.clear()
        scope().launch {
            runCatching { container.server.forceStopAll() }
            syncRuntimeResources()
            postStatus(force = true)
            maybeFinishService()
        }
    }

    /* ------------------------------------------------------------------ */
    /* Actions: Restart                                                   */
    /* ------------------------------------------------------------------ */

    private fun beginRestart(instanceId: String?) {
        if (instanceId == null) {
            failEarly("There is no server to restart.")
            return
        }
        // A manual restart starts a fresh crash-loop budget for that instance
        // only and supersedes a Start queued behind a previous stop.
        autoRestartAttempts.remove(instanceId)
        manualStopRequested.remove(instanceId)
        cancelAutoRestart(instanceId)
        queuedStarts.remove(instanceId)
        notifications.cancelAlert()
        dispatchExclusive(instanceId) { restartExclusive(instanceId) }
    }

    private fun restartExclusive(instanceId: String) {
        // A restart is authoritative: no Start queued for an earlier stop may
        // survive into (or after) it - only the stop completion path may consume
        // a queued start.
        queuedStarts.remove(instanceId)
        restartInFlight.add(instanceId)
        val status = currentRuntimes()[instanceId]
        val decision = ServiceLifecycleDecisions.decideRestart(
            runtimeIsActive = status?.isActive == true,
            runtimeState = status?.state,
            hasSession = hasActiveSession(instanceId)
        )
        when (decision) {
            RestartDecision.RESTART_EXISTING -> restartExistingExclusive(instanceId, status)
            RestartDecision.CLEAR_STALE_THEN_START ->
                runRestartFreshOperation(instanceId, clearStaleFirst = true)
            RestartDecision.START_FRESH ->
                runRestartFreshOperation(instanceId, clearStaleFirst = false)
        }
    }

    /**
     * Restart of one instance by asking the controller to stop+start it.
     *
     * The whole stop→start cycle stays inside one exclusive operation of that
     * instance and [restartInFlight] is held until it completes, so the transient
     * terminal `STOPPED` published between the halves is ignored by the runtime
     * observer instead of finishing the service (that was the broken Restart
     * button in 1.3.x). Other sessions are untouched.
     */
    private fun restartExistingExclusive(instanceId: String, before: ServerRuntimeStatus?) {
        startObserving()
        syncRuntimeResources()
        postStatus(force = true)
        scope().launch {
            try {
                // Keep the wake lock across the stop→start gap: the child JVM
                // briefly disappears, but the user asked for it back immediately.
                withContext(Dispatchers.IO) {
                    runCatching { container.server.restart(instanceId) }
                        .onFailure { Log.w(TAG, "Restart of $instanceId failed", it) }
                }
                when (awaitRestartOutcome(instanceId, before)) {
                    RestartOutcome.SUCCEEDED -> {
                        // The restart succeeded. Clear the guard before the final
                        // status so a crash in the same instant still runs the
                        // normal post-mortem instead of being ignored as transient.
                        restartInFlight.remove(instanceId)
                        activeSeen.add(instanceId)
                        syncRuntimeResources()
                        postStatus(force = true)
                        val latest = currentRuntimes()[instanceId]
                        if (latest != null && !latest.isActive) {
                            handleInstanceFinished(instanceId, latest)
                        }
                    }
                    RestartOutcome.FAILED -> {
                        notifications.postStartRejected(
                            "The server could not be restarted. Check its console for details.",
                            instanceName(instanceId),
                            contentIntent(instanceId),
                            restartServiceIntent(instanceId)
                        )
                    }
                }
            } finally {
                // Defensive: a Start pressed while this restart's stop phase
                // looked like a normal STOPPING must not outlive the restart.
                queuedStarts.remove(instanceId)
                restartInFlight.remove(instanceId)
                finishOperation(instanceId)
            }
        }
    }

    /** Fresh start for a restart command; a stale runtime is cleared first when needed. */
    private fun runRestartFreshOperation(instanceId: String, clearStaleFirst: Boolean) {
        scope().launch {
            try {
                if (clearStaleFirst) clearStaleRuntime(instanceId)
                val outcome = startServer(instanceId)
                // The restart is over once start() returned: let the normal start
                // post-mortem handle an immediate crash (alert/auto-restart)
                // instead of suppressing it behind the restart guard.
                restartInFlight.remove(instanceId)
                handleStartOutcome(instanceId, outcome)
            } finally {
                // Defensive: never let a queued start outlive the restart.
                queuedStarts.remove(instanceId)
                restartInFlight.remove(instanceId)
                finishOperation(instanceId)
            }
        }
    }

    private enum class RestartOutcome { SUCCEEDED, FAILED }

    /**
     * Waits (bounded by [RESTART_WAIT_MS]) for this instance's restarted session
     * to become active. The pre-restart snapshot must not count as success, so a
     * changed identity (pid or startedAt) is required. A CRASHED state fails fast;
     * on timeout the controller's session flag is the tie-breaker so a slow OEM
     * start is not torn down.
     */
    private suspend fun awaitRestartOutcome(
        instanceId: String,
        before: ServerRuntimeStatus?
    ): RestartOutcome {
        val matched = withTimeoutOrNull(RESTART_WAIT_MS) {
            container.server.runtimes.first { statuses ->
                val status = statuses[instanceId] ?: return@first false
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
            return if (hasActiveSession(instanceId)) {
                Log.w(TAG, "Restart of $instanceId did not publish an active state in time; adopting its session")
                RestartOutcome.SUCCEEDED
            } else {
                Log.w(TAG, "Restart of $instanceId did not start a new session within ${RESTART_WAIT_MS}ms")
                RestartOutcome.FAILED
            }
        }
        if (matched[instanceId]?.state == RunState.CRASHED) {
            Log.w(TAG, "Restart of $instanceId produced a crashed session")
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

    /* ------------------------------------------------------------------ */
    /* Sticky re-delivery / failure                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Sticky restart with a null intent: adopt the servers that are still
     * running, or surface the newest persisted crash when nothing is left.
     */
    private fun adoptExistingSession() {
        val statuses = currentRuntimes()
        if (statuses.values.any { it.isActive }) {
            startObserving()
            syncRuntimeResources()
            postStatus(force = true)
            return
        }
        val crashed = statuses.values
            .filter { it.state == RunState.CRASHED || it.lastExit?.expected == false }
            .maxByOrNull { it.lastExit?.timestamp ?: 0L }
        if (crashed != null) {
            val id = crashed.instanceId
            val exit = crashed.lastExit ?: id?.let {
                runCatching { container.server.activeLastCrash(it) }.getOrNull()
            }
            notifications.postCrashAlert(
                title = "Server stopped unexpectedly",
                text = exit?.summary?.takeIf { it.isNotBlank() }
                    ?: "The server process exited. Tap to check the console.",
                contentIntent = contentIntent(id),
                restartIntent = restartServiceIntent(id)
            )
        }
        maybeFinishService()
    }

    private fun failEarly(message: String) {
        val id = focusedInstanceId
            ?: runCatching { container.server.activeInstanceId.value }.getOrNull()
        notifications.postStartRejected(
            message = message,
            instanceName = instanceName(id),
            contentIntent = contentIntent(id),
            restartIntent = restartServiceIntent(id)
        )
        maybeFinishService()
    }

    /* ------------------------------------------------------------------ */
    /* Runtime observation                                                */
    /* ------------------------------------------------------------------ */

    private fun startObserving() {
        ensureScope()
        if (runtimeObserver?.isActive != true) {
            runtimeObserver = scope().launch {
                combine(
                    container.server.runtimes,
                    container.server.activeInstanceId
                ) { statuses, focused -> statuses to focused }
                    .collect { (statuses, focused) -> onRuntimesChanged(statuses, focused) }
            }
        }
        if (tickerJob?.isActive != true) {
            tickerJob = scope().launch {
                while (isActive) {
                    delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                    if (finishing.get()) continue
                    val statuses = currentRuntimes()
                    val anyActive = statuses.values.any { it.isActive } || hasAnyActiveSession()
                    syncRuntimeResources()
                    if (!anyActive) continue
                    // Apply the silent-media toggle live while any server runs, so
                    // the user does not need to restart the instances.
                    val settings = runCatching { container.settings.current() }.getOrNull()
                    if (settings?.silentMediaHack == true) silentMedia.start() else silentMedia.stop()
                    // A stop already posted its "Shutting down…" notification;
                    // repeating it every 2 s looks like notification spam.
                    val (_, focusedStatus) = notificationTarget(statuses)
                    if (focusedStatus?.state == RunState.STOPPING) continue
                    postStatus(force = true)
                }
            }
        }
    }

    /** Reacts to a new per-instance status map (the controller's single source of truth). */
    private fun onRuntimesChanged(
        statuses: Map<String, ServerRuntimeStatus>,
        focused: String?
    ) {
        val previousFocus = focusedInstanceId
        if (focused != null) focusedInstanceId = focused
        val focusChanged = focused != null && focused != previousFocus

        var anyActive = false
        for ((instanceId, status) in statuses) {
            if (status.isActive) {
                anyActive = true
                activeSeen.add(instanceId)
                if (status.state == RunState.RUNNING) {
                    // A healthy boot ends the consecutive-crash budget of that
                    // instance and clears its manual-stop flag.
                    autoRestartAttempts.remove(instanceId)
                    manualStopRequested.remove(instanceId)
                }
            } else if (activeSeen.remove(instanceId)) {
                handleInstanceFinished(instanceId, status)
            }
        }
        syncRuntimeResources()
        if (anyActive || focusChanged) postStatus(force = focusChanged)
        maybeFinishService()
    }

    /**
     * Handles an instance that transitioned from active to terminal. Crashes get
     * a per-instance alert and, when the instance opted in, a per-instance
     * automatic restart with its own consecutive-attempt budget.
     */
    private fun handleInstanceFinished(instanceId: String, status: ServerRuntimeStatus) {
        if (finishing.get()) return
        // A restart of this instance owns its transient terminal states.
        if (restartInFlight.contains(instanceId)) return
        if (autoRestartJobs[instanceId]?.isActive == true) return
        if (operationsInFlight.contains(instanceId) || pendingCommands.containsKey(instanceId)) return
        activeSeen.remove(instanceId)
        // Reached only when no operation owns this instance any more: a start
        // still queued for it would never be consumed, so drop it to avoid
        // keeping the service alive forever.
        queuedStarts.remove(instanceId)

        val crashed = status.state == RunState.CRASHED || status.lastExit?.expected == false
        if (crashed) {
            val attempts = autoRestartAttempts[instanceId] ?: 0
            if (canAutoRestart(instanceId) && attempts < MAX_AUTO_RESTART_ATTEMPTS) {
                scheduleAutoRestart(instanceId)
                return
            }
            if (canAutoRestart(instanceId)) {
                notifications.postCrashAlert(
                    title = "Server keeps crashing",
                    text = "Automatic restart limit reached ($MAX_AUTO_RESTART_ATTEMPTS attempts) " +
                        "for ${instanceName(instanceId) ?: "this server"}. Tap to inspect its console.",
                    contentIntent = contentIntent(instanceId),
                    restartIntent = restartServiceIntent(instanceId)
                )
            } else {
                val exit = status.lastExit ?: runCatching {
                    container.server.activeLastCrash(instanceId)
                }.getOrNull()
                notifications.postCrashAlert(
                    title = "Server stopped unexpectedly",
                    text = exit?.summary?.takeIf { it.isNotBlank() }
                        ?: "The server process exited. Tap to check the console.",
                    contentIntent = contentIntent(instanceId),
                    restartIntent = restartServiceIntent(instanceId)
                )
            }
        }
        syncRuntimeResources()
        maybeFinishService()
    }

    /** True when the crashed instance opted into automatic restarts. */
    private fun canAutoRestart(instanceId: String): Boolean {
        if (manualStopRequested.contains(instanceId)) return false
        val instance = runCatching { container.instances.get(instanceId) }.getOrNull()
            ?: return false
        val settings = runCatching { container.settings.current() }.getOrNull()
        return instance.autoRestart || settings?.autoRestartOnCrash == true
    }

    /**
     * Keeps the foreground service alive, shows a visible "attempt n/3"
     * notification and retries only [instanceId] after a short delay. The
     * attempt budget of that instance is reset by a healthy RUNNING state or by
     * a manual start/stop/restart of it.
     */
    private fun scheduleAutoRestart(instanceId: String) {
        if (autoRestartJobs[instanceId]?.isActive == true) return
        val attempt = (autoRestartAttempts[instanceId] ?: 0) + 1
        autoRestartAttempts[instanceId] = attempt
        val name = instanceName(instanceId)
        runCatching {
            if (runtimesAnyActive() || hasAnyActiveSession()) {
                // Other servers are still running: keep the normal status card,
                // the alert below names the crashed one.
                postStatus(force = true)
            } else {
                notifications.postStatus(
                    notifications.buildAutoRestart(
                        instanceName = name,
                        attempt = attempt,
                        maxAttempts = MAX_AUTO_RESTART_ATTEMPTS,
                        contentIntent = contentIntent(instanceId),
                        stopIntent = stopServiceIntent(instanceId)
                    )
                )
            }
            notifications.postAutoRestartAlert(
                instanceName = name,
                attempt = attempt,
                maxAttempts = MAX_AUTO_RESTART_ATTEMPTS,
                contentIntent = contentIntent(instanceId)
            )
        }.onFailure { Log.w(TAG, "Could not post auto-restart notification", it) }

        val job = scope().launch {
            try {
                delay(AUTO_RESTART_DELAY_MS)
                if (finishing.get() || manualStopRequested.contains(instanceId)) return@launch
                if (operationsInFlight.contains(instanceId)) return@launch
                if (currentRuntimes()[instanceId]?.isActive == true) return@launch
                syncRuntimeResources()
                val result = withContext(Dispatchers.IO) {
                    runCatching { container.server.start(instanceId) }
                }
                // Stop counting this job as pending work before deciding whether
                // the service should finish.
                autoRestartJobs.remove(instanceId)
                val startResult = result.getOrNull()
                if (startResult == null) {
                    notifications.postStartRejected(
                        result.exceptionOrNull()?.message ?: "Automatic restart failed.",
                        instanceName(instanceId),
                        contentIntent(instanceId),
                        restartServiceIntent(instanceId)
                    )
                    maybeFinishService()
                    return@launch
                }
                if (startResult is StartResult.Rejected) {
                    notifications.postStartRejected(
                        startResult.message,
                        instanceName(instanceId),
                        contentIntent(instanceId),
                        restartServiceIntent(instanceId)
                    )
                    maybeFinishService()
                    return@launch
                }
                activeSeen.add(instanceId)
                syncRuntimeResources()
                notifications.cancelAlert()
                postStatus(force = true)
            } finally {
                autoRestartJobs.remove(instanceId)
            }
        }
        autoRestartJobs[instanceId] = job
    }

    /** Cancels a pending automatic restart of [instanceId]; attempts are kept. */
    private fun cancelAutoRestart(instanceId: String) {
        autoRestartJobs.remove(instanceId)?.cancel()
    }

    /** Cancels every pending/finished automatic restart (service teardown). */
    private fun resetAutoRestartState() {
        autoRestartJobs.values.forEach { it.cancel() }
        autoRestartJobs.clear()
        autoRestartAttempts.clear()
    }

    /* ------------------------------------------------------------------ */
    /* Notification plumbing                                              */
    /* ------------------------------------------------------------------ */

    private fun ensureForeground(): Boolean {
        if (foregroundPosted) return true
        val notification = notifications.buildStarting(
            instanceName = null,
            contentIntent = contentIntent(focusedInstanceId),
            stopIntent = null
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

    /**
     * Picks the instance the ongoing notification describes: the controller's
     * focused instance while it is active, otherwise the first active instance,
     * otherwise the last focused one.
     */
    private fun notificationTarget(
        statuses: Map<String, ServerRuntimeStatus>
    ): Pair<String?, ServerRuntimeStatus?> {
        val controllerFocus = runCatching { container.server.activeInstanceId.value }.getOrNull()
        val activeIds = statuses.values.filter { it.isActive }.mapNotNull { it.instanceId }
        val preferred = ServiceLifecycleDecisions.resolveFocusedInstanceId(
            controllerFocus?.takeIf { statuses[it]?.isActive == true },
            activeIds
        ) ?: controllerFocus ?: focusedInstanceId
        val status = preferred?.let { statuses[it] }
            ?: statuses.values.firstOrNull { it.isActive }
            ?: runCatching { container.server.runtime.value }.getOrNull()
        return preferred to status
    }

    private fun postStatus(force: Boolean) {
        if (finishing.get()) return
        val statuses = currentRuntimes()
        val runningCount = statuses.values.count { it.isActive }
        val (focusedId, status) = notificationTarget(statuses)
        if (status == null) return
        // Do not overwrite the "Starting…" placeholder (or a just-finished
        // notification that finishService is about to remove) with a terminal
        // snapshot while nothing is active.
        if (runningCount == 0 && !status.isActive) return
        val id = focusedId ?: status.instanceId
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
                    runningCount = runningCount,
                    fallbackDeviceAvailMb = fallbackAvailMb,
                    fallbackDeviceTotalMb = fallbackTotalMb,
                    contentIntent = contentIntent(id),
                    stopIntent = id?.let { stopServiceIntent(it) },
                    restartIntent = id?.let { restartServiceIntent(it) },
                    stopAllIntent = stopAllServiceIntent()
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
        }
        return PendingIntent.getActivity(
            this,
            requestCode(REQUEST_CONTENT, instanceId),
            intent,
            PENDING_FLAGS
        )
    }

    private fun stopServiceIntent(instanceId: String): PendingIntent {
        val intent = Intent(this, ForegroundServerService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            putExtra(EXTRA_FORCE, false)
        }
        return PendingIntent.getForegroundService(
            this,
            requestCode(REQUEST_STOP, instanceId),
            intent,
            PENDING_FLAGS
        )
    }

    private fun restartServiceIntent(instanceId: String?): PendingIntent {
        val intent = Intent(this, ForegroundServerService::class.java).apply {
            action = ACTION_RESTART
            if (!instanceId.isNullOrBlank()) putExtra(EXTRA_INSTANCE_ID, instanceId)
        }
        return PendingIntent.getForegroundService(
            this,
            requestCode(REQUEST_RESTART, instanceId),
            intent,
            PENDING_FLAGS
        )
    }

    private fun stopAllServiceIntent(): PendingIntent {
        val intent = Intent(this, ForegroundServerService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        return PendingIntent.getForegroundService(this, REQUEST_STOP_ALL, intent, PENDING_FLAGS)
    }

    /** One request code per (action, instance) so per-instance notification actions never collide. */
    private fun requestCode(base: Int, instanceId: String?): Int =
        if (instanceId.isNullOrBlank()) base else base * 31 + instanceId.hashCode()

    /** Removes the foreground notification and stops the service; safe to call repeatedly. */
    private fun finishService() {
        if (!finishing.compareAndSet(false, true)) return
        // Retire this generation: operations still unwinding in the background
        // must not mutate the next one.
        generation++
        // Drop any command still queued behind the operations that are ending:
        // this service generation is going away and a revived instance must not
        // dispatch it (a stale Stop would kill the fresh server).
        resetOperationState()
        resetAutoRestartState()
        manualStopRequested.clear()
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
    /* Per-instance serialization                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Runs [command] as the exclusive lifecycle operation of [instanceId], or
     * queues it behind the operation that instance is already running. Different
     * instances run their operations independently, so starting or stopping one
     * server never blocks another.
     */
    private fun dispatchExclusive(instanceId: String, command: () -> Unit) {
        if (finishing.get()) return
        if (operationsInFlight.contains(instanceId)) {
            pendingCommands[instanceId] = command
            return
        }
        operationsInFlight.add(instanceId)
        operationTokens[instanceId] = generation
        command()
    }

    private fun finishOperation(instanceId: String) {
        // A continuation from a previous service generation must not touch the
        // bookkeeping of the current one.
        if (operationTokens[instanceId] != generation) return
        operationTokens.remove(instanceId)
        operationsInFlight.remove(instanceId)
        syncRuntimeResources()
        if (finishing.get()) return
        val next = pendingCommands.remove(instanceId)
        if (next != null) dispatchExclusive(instanceId, next)
        maybeFinishService()
    }

    /**
     * True while the service still has work that will touch a child JVM: a
     * running operation, a queued command, a start waiting behind a stop, or an
     * automatic restart waiting for its delay.
     */
    private fun hasPendingWork(): Boolean =
        operationsInFlight.isNotEmpty() ||
            pendingCommands.isNotEmpty() ||
            queuedStarts.isNotEmpty() ||
            autoRestartJobs.values.any { it.isActive }

    /**
     * Finishes the foreground service only when no runtime is active, no child
     * JVM session exists and no queued/in-flight work remains. This is the
     * "no sessions remain" rule: stopping one of several servers never tears the
     * service (or the manager) down.
     */
    private fun maybeFinishService() {
        if (finishing.get()) return
        val keepAlive = ServiceLifecycleDecisions.shouldKeepServiceAlive(
            anyActiveRuntime = runtimesAnyActive(),
            anyActiveSession = hasAnyActiveSession(),
            hasPendingWork = hasPendingWork()
        )
        if (!keepAlive) finishService()
    }

    /* ------------------------------------------------------------------ */
    /* Resources / helpers                                                */
    /* ------------------------------------------------------------------ */

    /** Holds the wake lock and silent media exactly while a JVM exists or work is pending. */
    private fun syncRuntimeResources() {
        val hold = runtimesAnyActive() ||
            hasAnyActiveSession() ||
            operationsInFlight.isNotEmpty() ||
            pendingCommands.isNotEmpty() ||
            queuedStarts.isNotEmpty() ||
            autoRestartJobs.values.any { it.isActive }
        runCatching {
            if (hold) {
                wakeLocks.acquire()
                silentMedia.start()
            } else {
                silentMedia.stop()
                wakeLocks.release()
            }
        }
    }

    private fun resetOperationState() {
        operationsInFlight.clear()
        operationTokens.clear()
        pendingCommands.clear()
        queuedStarts.clear()
        restartInFlight.clear()
    }

    private fun ensureScope() {
        if (serviceScope == null) {
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    private fun scope(): CoroutineScope = serviceScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { serviceScope = it }

    /** The controller may not be constructed yet during the very first command. */
    private fun currentRuntimes(): Map<String, ServerRuntimeStatus> =
        runCatching { container.server.runtimes.value }.getOrDefault(emptyMap())

    private fun runtimesAnyActive(): Boolean =
        runCatching { container.server.runtimes.value.values.any { it.isActive } }.getOrDefault(false)

    private fun activeInstanceIds(): List<String> =
        currentRuntimes().values.filter { it.isActive }.mapNotNull { it.instanceId }

    private fun hasAnyActiveSession(): Boolean =
        runCatching { container.server.hasAnyActiveSession() }.getOrDefault(false)

    private fun hasActiveSession(instanceId: String): Boolean =
        runCatching { container.server.hasActiveSession(instanceId) }.getOrDefault(false)

    /**
     * Resolves the instance id a UI/notification command refers to: the explicit
     * intent extra, else the controller's focused instance, else the first
     * active one.
     */
    private fun resolveInstanceId(explicit: String?): String? =
        explicit
            ?: runCatching { container.server.activeInstanceId.value }.getOrNull()
            ?: focusedInstanceId
            ?: activeInstanceIds().firstOrNull()

    private fun instanceName(instanceId: String?): String? =
        instanceId?.let { runCatching { container.instances.get(it)?.name }.getOrNull() }

    /* ------------------------------------------------------------------ */
    /* Public entry points used by the UI                                 */
    /* ------------------------------------------------------------------ */

    companion object {
        private const val TAG = "ForegroundServerService"

        const val ACTION_START = "dev.javahost.action.START_SERVER"
        const val ACTION_STOP = "dev.javahost.action.STOP_SERVER"
        const val ACTION_RESTART = "dev.javahost.action.RESTART_SERVER"
        const val ACTION_FORCE_STOP = "dev.javahost.action.FORCE_STOP_SERVER"
        const val ACTION_STOP_ALL = "dev.javahost.action.STOP_ALL_SERVERS"
        const val ACTION_FORCE_STOP_ALL = "dev.javahost.action.FORCE_STOP_ALL_SERVERS"
        const val EXTRA_INSTANCE_ID = "instance_id"
        private const val EXTRA_FORCE = "force"

        private const val REQUEST_CONTENT = 41
        private const val REQUEST_STOP = 42
        private const val REQUEST_FORCE_STOP = 43
        private const val REQUEST_RESTART = 44
        private const val REQUEST_STOP_ALL = 45

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
         * close the foreground service and could shut the just-started JVM down).
         */
        private const val RESTART_WAIT_MS = 45_000L

        /** Consecutive automatic restarts allowed per instance without a healthy RUNNING state. */
        const val MAX_AUTO_RESTART_ATTEMPTS = 3

        /** Starts (or adopts) the hosted server [instanceId] inside the foreground service. */
        fun start(context: Context, instanceId: String) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            startServiceSafely(context, intent)
        }

        /** Stops [instanceId]; [force] destroys that JVM without the graceful save. */
        fun stop(context: Context, instanceId: String, force: Boolean = false) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_INSTANCE_ID, instanceId)
                putExtra(EXTRA_FORCE, force)
            }
            startServiceSafely(context, intent)
        }

        /** Restarts [instanceId] (or starts it again after a crash); others are unaffected. */
        fun restart(context: Context, instanceId: String) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_RESTART
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            startServiceSafely(context, intent)
        }

        /** Last-resort kill of one wedged session, then finish if nothing else runs. */
        fun forceStop(context: Context, instanceId: String) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_FORCE_STOP
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stops every running server (graceful, per instance). */
        fun stopAll(context: Context) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            startServiceSafely(context, intent)
        }

        /** Kills every running session immediately. */
        fun forceStopAll(context: Context) {
            val intent = Intent(context, ForegroundServerService::class.java).apply {
                action = ACTION_FORCE_STOP_ALL
            }
            ContextCompat.startForegroundService(context, intent)
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
