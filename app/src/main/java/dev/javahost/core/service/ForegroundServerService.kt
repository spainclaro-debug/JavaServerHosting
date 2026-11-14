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

    override fun onDestroy() {
        finishing.set(true)
        cancelAutoRestart()
        runtimeObserver?.cancel()
        tickerJob?.cancel()
        serviceScope?.cancel()
        runtimeObserver = null
        tickerJob = null
        runCatching { silentMedia.stop() }
        runCatching { wakeLocks.release() }
        runCatching { container.server.shutdown() }
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

    private fun beginStart(instanceId: String) {
        // A deliberate manual start always begins a fresh crash-loop budget.
        autoRestartAttempts = 0
        manualStopRequested = false
        cancelAutoRestart()
        notifications.cancelAlert()
        currentInstanceId = instanceId
        val existing = runCatching { container.server.runtime.value }.getOrNull()
        if (existing?.isActive == true) {
            // Do not spawn a second JVM; adopt the running one.
            adopt(existing)
            return
        }
        dispatchExclusive { startExclusive(instanceId) }
    }

    private fun startExclusive(instanceId: String) {
        startObserving()
        postStatus(force = true)
        scope().launch {
            try {
                wakeLocks.acquire()
                val result = withContext(Dispatchers.IO) {
                    runCatching { container.server.start(instanceId) }
                }
                result.onFailure { error ->
                    Log.e(TAG, "Server start threw", error)
                    wakeLocks.release()
                    silentMedia.stop()
                    notifications.postStartRejected(
                        error.message ?: "The server could not be started.",
                        instanceName(instanceId),
                        contentIntent(instanceId),
                        restartServiceIntent(instanceId)
                    )
                    finishService()
                }.onSuccess { startResult ->
                    when (startResult) {
                        is StartResult.Ok -> {
                            sawActiveRuntime = true
                            silentMedia.start()
                            postStatus(force = true)
                            // The child can exit between start() returning and
                            // the observer's first collect; handle that race so
                            // a crash never leaves a stale "Starting" FGS behind.
                            val status = runCatching { container.server.runtime.value }.getOrNull()
                            if (status != null && !status.isActive) handleFinished(status)
                        }
                        is StartResult.Rejected -> {
                            val current = runCatching { container.server.runtime.value }.getOrNull()
                            if (current?.isActive == true) {
                                // A concurrent (auto) restart won the race: adopt
                                // it instead of tearing down a live server.
                                adopt(current)
                            } else {
                                wakeLocks.release()
                                silentMedia.stop()
                                notifications.postStartRejected(
                                    startResult.message,
                                    instanceName(instanceId),
                                    contentIntent(instanceId),
                                    restartServiceIntent(instanceId)
                                )
                                finishService()
                            }
                        }
                    }
                }
            } finally {
                finishOperation()
            }
        }
    }

    private fun beginStop(force: Boolean) {
        // A manual stop is final: cancel any pending auto-restart, and make sure
        // a crash that races with this stop cannot schedule another one.
        manualStopRequested = true
        cancelAutoRestart()
        dispatchExclusive { stopExclusive(force) }
    }

    private fun stopExclusive(force: Boolean) {
        val wasActive = runCatching { container.server.runtime.value.isActive }.getOrDefault(false)
        val targetId = runCatching { container.server.runtime.value.instanceId }.getOrNull()
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
                // the FGS notification looping on "Shutting down…" forever.
                val stopped = withTimeoutOrNull(if (force) 15_000L else 40_000L) {
                    container.server.runtime.first { status ->
                        status.instanceId != targetId || !status.isActive
                    }
                } != null
                silentMedia.stop()
                wakeLocks.release()
                val stillActive = !stopped &&
                    runCatching { container.server.runtime.value.isActive }.getOrDefault(false)
                if (stillActive) {
                    // Last resort: pid-level kill + terminal state reset. This
                    // must always end with the notification removed; a wedged
                    // STOPPING state previously looped "Shutting down…" forever.
                    Log.w(TAG, "Stop did not complete; forcing session reset")
                    runCatching { container.server.forceStop() }
                    withTimeoutOrNull(3_000L) {
                        container.server.runtime.first { !it.isActive }
                    }
                    notifications.postCrashAlert(
                        title = "Server stop forced",
                        text = "The server did not stop gracefully and was force-stopped. Check the console for errors.",
                        contentIntent = contentIntent(targetId ?: currentInstanceId),
                        restartIntent = restartServiceIntent(targetId ?: currentInstanceId)
                    )
                    finishService()
                } else {
                    // The user asked for the stop, so any pending auto-restart
                    // alert no longer describes reality.
                    notifications.cancelAlert()
                    finishService()
                }
            } finally {
                finishOperation()
            }
        }
    }

    private fun beginForceStop() {
        manualStopRequested = true
        cancelAutoRestart()
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
        val runtime = runCatching { container.server.runtime.value }.getOrNull()
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
        val wasActive = runCatching { container.server.runtime.value.isActive }.getOrDefault(false)
        if (wasActive) sawActiveRuntime = true
        startObserving()
        postStatus(force = true)
        scope().launch {
            try {
                wakeLocks.acquire()
                if (wasActive) {
                    withContext(Dispatchers.IO) {
                        runCatching { container.server.restart() }
                            .onFailure { Log.w(TAG, "Restart failed", it) }
                    }
                } else if (instanceId != null) {
                    val started = withContext(Dispatchers.IO) {
                        runCatching { container.server.start(instanceId) }
                    }
                    val startResult = started.getOrNull()
                    if (startResult == null) {
                        wakeLocks.release()
                        notifications.postStartRejected(
                            started.exceptionOrNull()?.message ?: "The server could not be started.",
                            instanceName(instanceId),
                            contentIntent(instanceId),
                            restartServiceIntent(instanceId)
                        )
                        finishService()
                        return@launch
                    }
                    if (startResult is StartResult.Rejected) {
                        wakeLocks.release()
                        notifications.postStartRejected(
                            startResult.message,
                            instanceName(instanceId),
                            contentIntent(instanceId),
                            restartServiceIntent(instanceId)
                        )
                        finishService()
                        return@launch
                    }
                }
                sawActiveRuntime = true
                silentMedia.start()
                postStatus(force = true)
                val finalStatus = runCatching { container.server.runtime.value }.getOrNull()
                if (finalStatus != null && !finalStatus.isActive) handleFinished(finalStatus)
            } finally {
                finishOperation()
            }
        }
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
                    } else if (sawActiveRuntime && !operationInProgress.get()) {
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
