package dev.javahost.core.server

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import dev.javahost.core.api.JreController
import dev.javahost.core.api.ServerController
import dev.javahost.core.jar.JarInspector
import dev.javahost.core.launch.LaunchPlan
import dev.javahost.core.launch.LaunchPlanBuilder
import dev.javahost.core.model.ConsoleEntry
import dev.javahost.core.model.ExitInfo
import dev.javahost.core.model.ExitReason
import dev.javahost.core.model.LanEndpointInfo
import dev.javahost.core.model.LanProbeInfo
import dev.javahost.core.model.LanStatus
import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.LogLevel
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.model.ServerRuntimeStatus
import dev.javahost.core.model.StartResult
import dev.javahost.core.net.AndroidLanInfo
import dev.javahost.core.net.LanEndpoint
import dev.javahost.core.net.LanNetworkState
import dev.javahost.core.net.LanProbeResult
import dev.javahost.core.net.MinecraftLanAnnouncement
import dev.javahost.core.net.MinecraftLanAnnouncer
import dev.javahost.core.net.SocketTcpProbe
import dev.javahost.core.net.TcpProbe
import dev.javahost.core.storage.InstanceStore
import dev.javahost.core.storage.Paths
import dev.javahost.core.storage.ServerPropertiesCodec
import dev.javahost.core.storage.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The single child-JVM host of the app.
 *
 * Owns the only Java server process the app may run, its stdin/stdout, the
 * in-memory console, the on-disk log tail and the crash post-mortem.  It is a
 * process-wide singleton created by [dev.javahost.AppContainer] and shared by
 * the Compose UI (through the ViewModel) and the foreground service, so it never
 * touches Android UI classes and does not care whether the Activity is alive.
 *
 * Lifecycle rules:
 *  - exactly one child JVM at a time; [start] rejects while a process exists or
 *    another start/stop/restart transition is in flight,
 *  - [start] is synchronous (spawns immediately) and safe from a background
 *    thread; the service calls it from an FGS action,
 *  - [stop] launches the graceful shutdown asynchronously (the notification
 *    action must return quickly and the UI may already be dead),
 *  - [restart] is asynchronous and serialized with start/stop by a [Mutex],
 *  - [shutdown] kills the child synchronously-ish (destroyForcibly) and cancels
 *    all coroutines so the service can die without leaking a JVM.
 *
 * "Unexpected" exits go through the crash post-mortem: classify, save
 * `crashes/crash-<ts>.txt` + `crashes/last-crash.txt`, persist exit fields via
 * [InstanceStore] and publish a [RunState.CRASHED] runtime with [ExitInfo].
 *
 * The constructor signature is fixed by the team contract; do not change it.
 */
class ServerManager(
    context: Context,
    private val paths: Paths,
    private val instances: InstanceStore,
    private val settings: SettingsStore,
    private val jre: JreController
) : ServerController {

    private val appContext: Context = context.applicationContext ?: context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val lock = Any()

    @Volatile
    private var disposed = false
    private val shutdownOnce = AtomicBoolean(false)

    @Volatile
    private var lastCrash: ExitInfo? = null

    private val initialStatus: ServerRuntimeStatus = recoverInitialStatus()
    private val _runtime = MutableStateFlow(initialStatus)
    override val runtime: StateFlow<ServerRuntimeStatus> = _runtime.asStateFlow()

    private val _activeInstanceId =
        MutableStateFlow(initialStatus.instanceId ?: settings.current().lastActiveInstanceId)
    override val activeInstanceId: StateFlow<String?> = _activeInstanceId.asStateFlow()

    // ---- console ---------------------------------------------------------
    private val consoleLock = Any()
    private val consoleBuffer = ArrayDeque<ConsoleEntry>()
    private val seq = AtomicLong(0L)
    private val consoleDirty = AtomicBoolean(false)

    @Volatile
    private var consoleLimit: Int =
        settings.current().consoleLogLimit.coerceIn(MIN_CONSOLE_LIMIT, MAX_CONSOLE_LIMIT)

    private val _consoleEntries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    override val consoleEntries: StateFlow<List<ConsoleEntry>> = _consoleEntries.asStateFlow()

    /** Live console stream for consumers that prefer deltas over snapshots. */
    private val _consoleLive = MutableSharedFlow<ConsoleEntry>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val consoleLive: SharedFlow<ConsoleEntry> = _consoleLive.asSharedFlow()

    private val consolePublisherJob: Job = scope.launch {
        while (isActive) {
            delay(CONSOLE_PUBLISH_INTERVAL_MS)
            publishConsoleIfDirty()
        }
    }

    // ---- lifecycle state -------------------------------------------------
    @Volatile
    private var session: Session? = null

    private var restartJob: Job? = null

    // ---- LAN access / Minecraft discovery --------------------------------
    private val lanInfo = AndroidLanInfo(appContext)
    private val tcpProbe: TcpProbe = SocketTcpProbe()
    private var lanAnnouncer: MinecraftLanAnnouncer? = null
    private val lanProbeInFlight = AtomicBoolean(false)

    // =====================================================================
    // Contract: start
    // =====================================================================

    override fun start(instanceId: String): StartResult {
        if (!lifecycleMutex.tryLock()) {
            return StartResult.Rejected("Another start/stop/restart operation is still in progress. Try again in a moment.")
        }
        try {
            synchronized(lock) {
                if (disposed) return StartResult.Rejected("JavaHost is shutting down.")
                if (session != null) return StartResult.Rejected("A server is already running. Stop it before starting another one.")
                return runCatching { startInternal(instanceId) }
                    .getOrElse { error ->
                        StartResult.Rejected(
                            "Could not start the server: ${error.message ?: error.javaClass.simpleName}"
                        )
                    }
            }
        } finally {
            lifecycleMutex.unlock()
        }
    }

    /**
     * Resolve + spawn.  Caller must hold [lifecycleMutex] and [lock].
     * All validation happens before any resource is created.
     */
    private fun startInternal(instanceId: String): StartResult {
        val instance = instances.get(instanceId)
            ?: return StartResult.Rejected("Server instance not found. It may have been deleted.")
        val appSettings = settings.current()

        // 1. Java runtime.
        val javaHome = jre.javaHome(instance.jreMajor)
            ?: return StartResult.Rejected(
                "Java ${instance.jreMajor} is not installed. Install a Java runtime before starting this server."
            )
        if (!jre.isInstalled(instance.jreMajor)) {
            return StartResult.Rejected(
                "Java ${instance.jreMajor} is not installed. Install a Java runtime before starting this server."
            )
        }
        val javaExecutable = jre.javaExecutable(instance.jreMajor)
            ?: File(javaHome, "bin/java").takeIf { it.isFile }
            ?: return StartResult.Rejected(
                "Java ${instance.jreMajor} is installed but its executable is missing. Repair the runtime."
            )
        if (!javaExecutable.isFile) {
            return StartResult.Rejected(
                "Java ${instance.jreMajor} executable not found at ${javaExecutable.absolutePath}. Reinstall the runtime."
            )
        }
        if (!javaExecutable.canExecute()) {
            runCatching { javaExecutable.setExecutable(true) }
            if (!javaExecutable.canExecute()) {
                return StartResult.Rejected(
                    "The Java ${instance.jreMajor} executable is not runnable. Use Repair on the runtime."
                )
            }
        }

        // 2. Server JAR (must be a plain file name inside the instance dir).
        val jarName = instance.jarFile.trim()
        if (jarName.isEmpty() ||
            jarName.contains('/') ||
            jarName.contains('\\') ||
            jarName == "." ||
            jarName == ".."
        ) {
            return StartResult.Rejected("Invalid server JAR file name: '${instance.jarFile}'.")
        }
        val workingDir = paths.workingDir(instanceId)
        val jarFile = paths.jarFile(instanceId, jarName)
        val canonicalWorking = runCatching { workingDir.canonicalFile }.getOrDefault(workingDir)
        val canonicalJar = runCatching { jarFile.canonicalFile }.getOrDefault(jarFile)
        if (canonicalJar.path != canonicalWorking.path &&
            !canonicalJar.path.startsWith(canonicalWorking.path + File.separator)
        ) {
            return StartResult.Rejected("The server JAR path is outside the server directory.")
        }
        if (!canonicalJar.isFile) {
            return StartResult.Rejected(
                "Server JAR '${instance.jarFile}' is missing from the server folder. Re-import it."
            )
        }

        // 3. EULA gate (Minecraft only).
        if (instance.isMinecraft) {
            if (!instance.eulaAccepted) {
                return StartResult.Rejected(
                    "Minecraft requires accepting the Mojang EULA before the server can start. " +
                        "Open the server and accept the EULA first."
                )
            }
            // Keep eula.txt in sync with the persisted flag so a restored
            // instance directory cannot bounce back to the vanilla prompt.
            runCatching { EulaManager.accept(paths, instanceId) }
        }

        // 3b. LAN bind address: a leftover loopback `server-ip` (or a server
        // record with a phone-only address) makes localhost work while every
        // other device gets "connection refused". Clear it before launch.
        normalizeMinecraftBindAddress(instance)

        // 4. Memory.
        val memoryMb = LaunchPlanBuilder.resolvedMemoryMb(
            instance,
            appSettings.defaultMemoryMb,
            memoryCeilingMb()
        )

        // 5. JNA bionic dispatcher (best effort; harmless for generic servers).
        val jnaDir = JnaCompat.prepare(appContext, workingDir)

        // 6. Launch plan.
        val plan = try {
            LaunchPlanBuilder.build(
                instance = instance,
                workingDir = workingDir,
                jarFile = canonicalJar,
                javaExecutable = javaExecutable,
                javaHome = javaHome,
                memoryMb = memoryMb,
                jnaDir = jnaDir
            )
        } catch (t: Throwable) {
            return StartResult.Rejected(
                "Could not build the Java launch command: ${t.message ?: t.javaClass.simpleName}"
            )
        }

        // 7. Log store + spawn.
        val logTail = LogTailStore(
            // App-owned transcript: the server's own logs/latest.log is written
            // by Minecraft/log4j and must not be rotated or truncated by us.
            transcriptFile = paths.transcriptLog(instanceId),
            previousTranscriptFile = paths.transcriptPreviousLog(instanceId),
            crashDir = paths.crashesDir(instanceId),
            lastCrashFile = paths.lastCrashFile(instanceId)
        )
        runCatching { logTail.startSession() }
        emitJarTypeWarning(instance, canonicalJar)
        emitMinecraftLoadDiagnostics(instance)
        emitLanOverview(instance)

        val startedAt = System.currentTimeMillis()
        val process: Process = try {
            val builder = ProcessBuilder(plan.command)
            builder.directory(plan.workingDir)
            builder.redirectErrorStream(true)
            builder.environment().putAll(plan.environment)
            builder.start()
        } catch (t: Throwable) {
            runCatching { logTail.close() }
            return StartResult.Rejected(
                "Could not start the Java process: ${t.message ?: t.javaClass.simpleName}. " +
                    "Check that the Java runtime is intact."
            )
        }

        // 8. Publish state before starting the reader so early console lines
        //    cannot race a stale runtime snapshot.
        val activeSession = Session(
            instanceId = instanceId,
            instanceName = instance.name,
            isMinecraft = instance.isMinecraft,
            plan = plan,
            process = process,
            startedAt = startedAt,
            logTail = logTail,
            pid = ChildPidResolver.resolve(process)
        )
        session = activeSession
        activeSession.serverPort = instance.serverPort
        consoleLimit = appSettings.consoleLogLimit.coerceIn(MIN_CONSOLE_LIMIT, MAX_CONSOLE_LIMIT)
        synchronized(consoleLock) {
            while (consoleBuffer.size > consoleLimit) consoleBuffer.removeFirst()
        }
        _activeInstanceId.value = instanceId
        lastCrash = null
        runCatching { settings.update { it.copy(lastActiveInstanceId = instanceId) } }
        runCatching {
            instances.update(instanceId) {
                it.copy(lastStartedAt = startedAt, lastCrashSummary = null)
            }
        }
        val (availMb, totalMb) = deviceMemoryMb()
        _runtime.value = ServerRuntimeStatus(
            state = RunState.STARTING,
            instanceId = instanceId,
            instanceName = instance.name,
            pid = activeSession.pid,
            startedAt = startedAt,
            uptimeMs = 0L,
            playersOnline = 0,
            playersMax = 0,
            heapUsedMb = null,
            heapMaxMb = memoryMb,
            processRssMb = null,
            deviceAvailMb = availMb,
            deviceTotalMb = totalMb,
            ready = false,
            lastExit = null
        )
        emitConsole(
            LogLevel.INFO,
            "Starting ${instance.name} (Java ${instance.jreMajor}, ${memoryMb} MB heap)"
        )

        activeSession.readerJob = scope.launch { readerLoop(activeSession) }
        activeSession.waitJob = scope.launch { waitLoop(activeSession) }
        activeSession.statsJob = scope.launch { statsLoop(activeSession) }
        scope.launch {
            runCatching { refreshLan(activeSession, probe = false) }
        }
        return StartResult.Ok
    }

    // =====================================================================
    // Contract: stop / restart / commands
    // =====================================================================

    override fun stop(force: Boolean) {
        val target: Session = synchronized(lock) {
            val current = session ?: return
            if (current.stopInitiated.get()) {
                current.forceRequested = current.forceRequested || force
                return
            }
            // Mark synchronously: the process can exit before the async
            // stopSession() coroutine runs, and finishSession must see this
            // user-requested stop and not classify a stale tail as a crash.
            current.stopRequested = true
            current
        }
        publishStopping(target)
        // Async on purpose: notification actions must return immediately and
        // the Activity may be gone; the process shutdown continues in service.
        scope.launch {
            lifecycleMutex.withLock { stopSession(target, force) }
        }
    }

    override fun restart() {
        synchronized(lock) {
            if (restartJob?.isActive == true) return
            val current = session ?: return // contract: no-op when nothing is active
            val instanceId = current.instanceId
            restartJob = scope.launch { performRestart(instanceId) }
        }
    }

    private suspend fun performRestart(instanceId: String) {
        lifecycleMutex.withLock {
            try {
                val current = synchronized(lock) { session }
                if (current != null && current.instanceId == instanceId) {
                    synchronized(lock) {
                        if (!current.stopping) current.stopping = true
                        current.stopRequested = true
                    }
                    publishStopping(current)
                    stopSession(current, force = false)
                }
                val result = synchronized(lock) {
                    when {
                        disposed -> StartResult.Rejected("JavaHost is shutting down.")
                        session != null -> StartResult.Rejected("The previous server is still shutting down.")
                        else -> startInternal(instanceId)
                    }
                }
                if (result is StartResult.Rejected) {
                    emitConsole(LogLevel.ERROR, "Restart failed: ${result.message}")
                    resetStoppingState(instanceId)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                emitConsole(
                    LogLevel.ERROR,
                    "Restart failed: ${t.message ?: t.javaClass.simpleName}"
                )
                resetStoppingState(instanceId)
            }
        }
    }

    private fun resetStoppingState(instanceId: String) {
        _runtime.update { status ->
            if (status.instanceId == instanceId && status.state == RunState.STOPPING) {
                status.copy(state = RunState.STOPPED)
            } else {
                status
            }
        }
    }

    override fun sendCommand(command: String) {
        val line = command.trim()
        if (line.isEmpty()) return
        val current = synchronized(lock) { session } ?: return
        if (!isProcessAlive(current.process)) {
            emitConsole(LogLevel.WARN, "The server is no longer running; command ignored.")
            return
        }
        if (writeStdin(current, line)) {
            emitConsole(LogLevel.COMMAND, line)
        } else {
            emitConsole(LogLevel.WARN, "Could not send the command to the server process.")
        }
    }

    override fun clearConsole() {
        synchronized(consoleLock) {
            consoleBuffer.clear()
            consoleDirty.set(false)
        }
        _consoleEntries.value = emptyList()
    }

    override fun exportConsole(destination: File): Result<File> = runCatching {
        publishConsoleIfDirty()
        val entries = _consoleEntries.value
        destination.parentFile?.mkdirs()
        destination.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (entry in entries) {
                writer.write("[${Instant.ofEpochMilli(entry.timestamp)}] [${entry.level}] ${entry.text}")
                writer.newLine()
            }
        }
        destination
    }

    override fun recentTail(count: Int): List<String> {
        if (count <= 0) return emptyList()
        val current = synchronized(lock) { session }
        if (current != null) return current.tailSnapshot(count)
        lastCrash?.takeIf { it.tail.isNotEmpty() }?.let { crash -> return crash.tail.takeLast(count) }
        val id = _activeInstanceId.value ?: return emptyList()
        for (file in diskTailCandidates(id)) {
            val lines = LogTailStore.tailOf(file, count)
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    override fun activeLastCrash(): ExitInfo? = lastCrash

    override fun probeLan(): Boolean {
        val current = synchronized(lock) { session } ?: return false
        if (!current.ready) {
            emitConsole(
                LogLevel.INFO,
                "The server is still starting; the LAN self-test runs automatically once it is ready."
            )
        }
        if (!lanProbeInFlight.compareAndSet(false, true)) return true
        scope.launch {
            try {
                runCatching { refreshLan(current, probe = true, forceReport = true) }
            } finally {
                lanProbeInFlight.set(false)
            }
        }
        return true
    }

    override fun forceStop(): Boolean {
        restartJob?.cancel()
        restartJob = null
        val current = synchronized(lock) {
            session.also { session = null }
        }
        if (current == null) {
            resetRuntimeAfterForcedStop(null)
            return false
        }
        current.stopRequested = true
        current.forceRequested = true
        current.stopInitiated.set(true)
        stopLanAnnouncement(current)
        forceKill(current)
        val exited = runCatching { current.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        val code = if (exited) runCatching { current.process.exitValue() }.getOrDefault(-1) else -1
        runCatching { current.exitDeferred.complete(code) }
        runCatching { finishSession(current, code) }
        resetRuntimeAfterForcedStop(current.instanceId)
        emitConsole(LogLevel.WARN, "Server session was force-cleared after a stuck stop.")
        return true
    }

    private fun resetRuntimeAfterForcedStop(instanceId: String?) {
        _runtime.update { status ->
            if (status.isActive || (instanceId != null && status.instanceId == instanceId)) {
                status.copy(
                    state = RunState.STOPPED,
                    pid = null,
                    startedAt = null,
                    uptimeMs = 0L,
                    ready = false
                )
            } else {
                status
            }
        }
    }

    override fun shutdown() {
        if (!shutdownOnce.compareAndSet(false, true)) return
        disposed = true
        val current: Session?
        synchronized(lock) {
            current = session
            session = null
        }
        if (current != null) {
            current.stopRequested = true
            current.forceRequested = true
            stopLanAnnouncement(current)
            // Prevent waitLoop from running the normal post-mortem path; this is
            // an app shutdown, not a server crash.
            current.finalized.set(true)
            runCatching { current.process.destroy() }
            runCatching { if (current.process.isAlive) current.process.destroyForcibly() }
            current.statsJob?.cancel()
            current.readerJob?.cancel()
            runCatching { current.logTail.close() }
            current.exitDeferred.complete(-1)
            current.finished.complete(Unit)
            val (availMb, totalMb) = deviceMemoryMb()
            _runtime.value = ServerRuntimeStatus(
                state = RunState.STOPPED,
                instanceId = current.instanceId,
                instanceName = current.instanceName,
                deviceAvailMb = availMb,
                deviceTotalMb = totalMb,
                lastExit = _runtime.value.lastExit
            )
        }
        consolePublisherJob.cancel()
        scope.cancel()
    }

    override fun dispose() {
        shutdown()
    }

    // =====================================================================
    // Extra convenience API used by the ViewModel (not part of the contract)
    // =====================================================================

    /**
     * Accepts the Minecraft EULA for [instanceId]: writes `eula.txt` and flips
     * the persisted flag.  Returns the updated instance, or null when unknown.
     */
    fun acceptEula(instanceId: String): ServerInstance? {
        if (instances.get(instanceId) == null) return null
        EulaManager.accept(paths, instanceId)
        return instances.update(instanceId) { it.copy(eulaAccepted = true) }
    }

    fun isEulaAccepted(instanceId: String): Boolean = EulaManager.isAccepted(paths, instanceId)

    /** Clears the "last crash" banner once the user acknowledged it. */
    override fun clearLastCrash() {
        lastCrash = null
        _runtime.update { if (it.state == RunState.CRASHED) it.copy(lastExit = null) else it }
        val id = _activeInstanceId.value ?: return
        runCatching {
            instances.update(id) { it.copy(lastCrashSummary = null) }
        }
    }

    /**
     * Tail of the log for any instance, used by the UI after a restart/crash.
     * Prefers the in-memory tail of the active session, then the last crash
     * tail, then the app transcript (`console.log` / `console.previous.log`),
     * then the server's own `logs/latest.log` / `previous.log`, and finally
     * `crashes/last-crash.txt`.
     */
    override fun readStartupTail(instanceId: String, lines: Int): List<String> {
        if (lines <= 0) return emptyList()
        val current = synchronized(lock) { session }
        if (current != null && current.instanceId == instanceId) {
            return current.tailSnapshot(lines)
        }
        val crash = lastCrash
        if (crash != null && _activeInstanceId.value == instanceId && crash.tail.isNotEmpty()) {
            return crash.tail.takeLast(lines)
        }
        for (file in diskTailCandidates(instanceId)) {
            val tail = LogTailStore.tailOf(file, lines)
            if (tail.isNotEmpty()) return tail
        }
        return LogTailStore.tailOf(paths.lastCrashFile(instanceId), lines)
    }

    /**
     * On-disk tail candidates, most useful first: the app transcript, then the
     * server's own log files (read-only for us).
     */
    private fun diskTailCandidates(instanceId: String): List<File> = listOf(
        paths.transcriptLog(instanceId),
        paths.transcriptPreviousLog(instanceId),
        paths.latestLog(instanceId),
        paths.previousLog(instanceId)
    )

    // =====================================================================
    // Console engine
    // =====================================================================

    private fun emitConsole(level: LogLevel, text: String) {
        if (text.isEmpty()) return
        val entry = ConsoleEntry(
            seq = seq.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            text = text
        )
        synchronized(consoleLock) {
            consoleBuffer.addLast(entry)
            while (consoleBuffer.size > consoleLimit) consoleBuffer.removeFirst()
        }
        consoleDirty.set(true)
        _consoleLive.tryEmit(entry)
    }

    private fun publishConsoleIfDirty() {
        if (!consoleDirty.get()) return
        val snapshot: List<ConsoleEntry>? = synchronized(consoleLock) {
            if (!consoleDirty.getAndSet(false)) null else consoleBuffer.toList()
        }
        if (snapshot != null) _consoleEntries.value = snapshot
    }

    private fun handleLine(session: Session, rawLine: String) {
        val stripped = ConsoleParser.stripAnsi(rawLine).trimEnd()
        if (stripped.isEmpty()) return
        val display = if (stripped.length > MAX_CONSOLE_LINE_CHARS) {
            stripped.take(MAX_CONSOLE_LINE_CHARS) + " …"
        } else {
            stripped
        }
        session.pushTail(display)
        runCatching { session.logTail.append(display) }

        // Parser side effects first, then publish the line last.
        if (!session.ready && ConsoleParser.isReady(stripped)) {
            markReady(session)
        }
        ConsoleParser.parsePlayers(stripped)?.let { players ->
            session.playersOnline = players.online
            session.playersMax = players.max
            publishSessionProgress(session)
        }
        ConsoleParser.parsePlayerEvent(stripped)?.let { event ->
            when (event.type) {
                PlayerEventType.JOIN -> session.playersOnline = (session.playersOnline + 1).coerceAtLeast(0)
                PlayerEventType.LEAVE -> session.playersOnline = (session.playersOnline - 1).coerceAtLeast(0)
            }
            publishSessionProgress(session)
        }
        ConsoleParser.parsePort(stripped)?.let { port ->
            if (session.serverPort != port) {
                session.serverPort = port
                runCatching {
                    instances.update(session.instanceId) {
                        if (it.serverPort == port) it else it.copy(serverPort = port)
                    }
                }
            }
        }
        if (!session.eulaPromptSeen && ConsoleParser.isEulaPrompt(stripped)) {
            session.eulaPromptSeen = true
            emitConsole(
                LogLevel.WARN,
                "The server is asking for the Minecraft EULA. Accept it in the server settings to start the server."
            )
        }
        ConsoleParser.crashSignal(stripped)?.let { signal ->
            session.observedSignal = signal
        }
        emitPluginCompatibilityHints(session, stripped)
        emitConsole(ConsoleParser.classify(stripped), display)
    }

    /**
     * Turns common plugin messages into actionable JavaHost console hints. The
     * underlying server logs are correct but easy to miss; these lines explain
     * what to install for the user's client type.
     */
    private fun emitPluginCompatibilityHints(session: Session, line: String) {
        val lower = line.lowercase()
        if (!session.viaVersionHintEmitted &&
            (lower.contains("viaversion does not have any compatible versions") ||
                lower.contains("viaversion only supports newer client versions"))
        ) {
            session.viaVersionHintEmitted = true
            emitConsole(
                LogLevel.WARN,
                "ViaVersion cannot add protocol support for this server version. " +
                    "If your Java client is OLDER than the server, install ViaBackwards " +
                    "(and ViaRewind for 1.7/1.8) in plugins/ and restart."
            )
        }
        if (!session.geyserNativeHintEmitted &&
            (lower.contains("nethernet will not start") ||
                lower.contains("cannot identify the jvm's libc") ||
                (lower.contains("libdatachannel") && lower.contains("native library")))
        ) {
            session.geyserNativeHintEmitted = true
            emitConsole(
                LogLevel.WARN,
                "Geyser's NetherNet/WebRTC transport cannot load its native library on Android/bionic. " +
                    "This is a plugin-native limitation, not a JavaHost bug: Bedrock clients may still " +
                    "connect over Geyser's RakNet/UDP 19132 path depending on their version; Java clients " +
                    "should use the Java port (25565) instead."
            )
        }
    }

    private fun markReady(session: Session) {
        if (session.ready || session.finalized.get()) return
        session.ready = true
        // The server is actually listening now: start the Minecraft LAN
        // discovery announcement and run the reachability self-test.
        scope.launch {
            runCatching { startLanAnnouncement(session) }
            delay(LAN_READY_SETTLE_MS)
            runCatching { refreshLan(session, probe = true, forceReport = true) }
        }
        _runtime.update { status ->
            if (status.instanceId != session.instanceId || !isActiveState(status.state)) return@update status
            status.copy(
                state = RunState.RUNNING,
                ready = true,
                playersOnline = session.playersOnline,
                playersMax = session.playersMax,
                uptimeMs = (System.currentTimeMillis() - session.startedAt).coerceAtLeast(0L)
            )
        }
    }

    private fun publishSessionProgress(session: Session) {
        _runtime.update { status ->
            if (status.instanceId != session.instanceId || !isActiveState(status.state)) return@update status
            status.copy(
                playersOnline = session.playersOnline,
                playersMax = session.playersMax,
                ready = session.ready,
                uptimeMs = (System.currentTimeMillis() - session.startedAt).coerceAtLeast(0L)
            )
        }
    }

    private fun publishStopping(session: Session) {
        _runtime.update { status ->
            if (status.instanceId != session.instanceId || !isActiveState(status.state)) return@update status
            status.copy(
                state = RunState.STOPPING,
                uptimeMs = (System.currentTimeMillis() - session.startedAt).coerceAtLeast(0L)
            )
        }
    }

    // =====================================================================
    // Reader / waiter / stats coroutines
    // =====================================================================

    private suspend fun readerLoop(session: Session) {
        val reader = session.process.inputStream.bufferedReader(Charsets.UTF_8)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                handleLine(session, line)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (isProcessAlive(session.process)) {
                emitConsole(
                    LogLevel.DEBUG,
                    "Console reader stopped: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        } finally {
            runCatching { reader.close() }
        }
    }

    private suspend fun waitLoop(session: Session) {
        val exitCode = try {
            withContext(Dispatchers.IO) { session.process.waitFor() }
        } catch (c: CancellationException) {
            return
        } catch (t: Throwable) {
            -1
        }
        session.exitDeferred.complete(exitCode)
        finishSession(session, exitCode)
    }

    private suspend fun statsLoop(session: Session) {
        val sampler = HeapSampler(session.plan.javaHome)
        var heap: HeapSample? = null
        var lastHeapAt = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            if (session.exitDeferred.isCompleted) break
            val now = System.currentTimeMillis()
            var pid = session.pid
            if (pid == null) {
                pid = ChildPidResolver.resolve(session.process)
                session.pid = pid
            }
            // jcmd may take a second; sample rarely and reuse for 2s ticks.
            if (pid != null && now - lastHeapAt >= HEAP_SAMPLE_INTERVAL_MS) {
                heap = runCatching { sampler.sample(pid) }.getOrNull()
                lastHeapAt = now
            }
            val (availMb, totalMb) = deviceMemoryMb()
            val rssMb = heap?.rssMb ?: pid?.let { HeapSampler.readRssMb(it) } ?: session.rssHint
            if (rssMb != null) session.rssHint = rssMb
            val snapshotHeap = heap
            val connectionCount = if (session.isMinecraft) {
                null
            } else {
                session.serverPort?.let { runCatching { PortConnectionCounter.count(it) }.getOrNull() }
            }
            _runtime.update { status ->
                if (status.instanceId != session.instanceId || !isActiveState(status.state)) {
                    return@update status
                }
                var state = status.state
                if (state == RunState.STARTING &&
                    (session.ready || now - session.startedAt >= STARTING_FALLBACK_RUNNING_MS)
                ) {
                    state = RunState.RUNNING
                }
                status.copy(
                    state = state,
                    uptimeMs = (now - session.startedAt).coerceAtLeast(0L),
                    pid = pid ?: status.pid,
                    playersOnline = session.playersOnline,
                    playersMax = session.playersMax,
                    connectionsOnline = connectionCount,
                    serverPort = session.serverPort ?: status.serverPort,
                    heapUsedMb = snapshotHeap?.usedMb ?: status.heapUsedMb,
                    heapMaxMb = session.plan.memoryMb,
                    processRssMb = rssMb,
                    deviceAvailMb = availMb,
                    deviceTotalMb = totalMb,
                    ready = session.ready
                )
            }
            if (session.ready && now - session.lanRefreshAt >= LAN_REFRESH_INTERVAL_MS) {
                runCatching { refreshLan(session, probe = false) }
                runCatching { reconcileLanAnnouncement(session) }
            }
            delay(STATS_INTERVAL_MS)
        }
    }

    // =====================================================================
    // Graceful stop
    // =====================================================================

    /**
     * Shared graceful/force shutdown for [stop] and [restart].
     *
     * Minecraft gets a "stop" command and up to 20 s; generic servers do not get
     * a fake command (most would not understand it), they simply get a short
     * 2 s grace before SIGTERM.  SIGTERM is followed by SIGKILL after 3 s.
     * Requests are idempotent and a later `stop(force = true)` escalates.
     */
    /**
     * Warns when the persisted flavor (possibly a manual override) disagrees
     * with what the JAR actually looks like. This prevents the classic
     * "plugins don't load" trap when a Vanilla JAR was mislabeled as Paper.
     */
    private fun emitJarTypeWarning(instance: ServerInstance, jarFile: File) {
        if (!instance.isMinecraft) return
        val inspection = (runCatching { JarInspector.inspect(jarFile) }.getOrNull() as? InspectionResult.Success)
            ?.inspection ?: return
        if (inspection.kind != instance.kind) {
            emitConsole(
                LogLevel.WARN,
                "The imported JAR looks like ${inspection.label}, but this instance is set to " +
                    "${instance.kind.name.lowercase()}. Check the server type; plugins/mods may not load."
            )
            return
        }
        if (inspection.flavor != instance.flavor &&
            inspection.flavor != ServerFlavor.OTHER_MINECRAFT &&
            instance.flavor != ServerFlavor.OTHER_MINECRAFT
        ) {
            emitConsole(
                LogLevel.WARN,
                "The imported JAR looks like ${inspection.label}, but this instance is set to " +
                    "${instance.flavor.name.lowercase().replace('_', ' ')}. " +
                    "If plugins/mods do not load, change the server type or import the right JAR."
            )
        }
    }

    /**
     * Tells the user, in the console, what the server will actually load. This
     * is the quickest way to explain the common "I installed Geyser/Floodgate/
     * ViaVersion but nothing happens" case: a Vanilla server ignores plugins/.
     */
    private fun emitMinecraftLoadDiagnostics(instance: ServerInstance) {
        if (!instance.isMinecraft) return
        val plugins = paths.pluginsDir(instance.id)
            .listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            .orEmpty()
        val mods = paths.modsDir(instance.id)
            .listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            .orEmpty()
        emitConsole(
            LogLevel.INFO,
            "Server type: ${instance.flavor.name.lowercase().replace('_', ' ')} · " +
                "Minecraft ${instance.minecraftVersion ?: "version unknown"} · " +
                "${plugins.size} plugin JAR(s) in plugins/, ${mods.size} mod JAR(s) in mods/"
        )
        when (instance.flavor) {
            ServerFlavor.VANILLA -> {
                if (plugins.isNotEmpty() || mods.isNotEmpty()) {
                    emitConsole(
                        LogLevel.WARN,
                        "This is a Vanilla server: it does NOT load plugins or mods. " +
                            "Geyser, Floodgate and ViaVersion need a Paper/Spigot server jar " +
                            "(plugins) or Fabric/Forge (mods). Replace the server JAR to use them."
                    )
                }
            }
            ServerFlavor.PAPER, ServerFlavor.BUKKIT, ServerFlavor.OTHER_MINECRAFT -> {
                if (plugins.isEmpty()) {
                    emitConsole(LogLevel.WARN, "No plugin JARs found in plugins/ — plugins installed in the app appear there and load on the next start.")
                } else {
                    emitConsole(
                        LogLevel.INFO,
                        "Bukkit-family server: ${plugins.size} plugin JAR(s) will be loaded from plugins/: " +
                            plugins.take(12).joinToString { it.name }
                    )
                    val names = plugins.map { it.name.lowercase() }
                    val hasGeyser = names.any { "geyser" in it }
                    val hasFloodgate = names.any { "floodgate" in it }
                    val hasViaVersion = names.any { "viaversion" in it }
                    val hasViaBackwards = names.any { "viabackwards" in it }
                    if (hasGeyser && !hasFloodgate) {
                        emitConsole(
                            LogLevel.WARN,
                            "Geyser is installed but Floodgate is not. Bedrock authentication may fail. " +
                                "Floodgate for Paper is distributed by GeyserMC (geysermc.org), not Modrinth; " +
                                "download it and import the JAR into plugins/ via Files."
                        )
                    }
                    if (hasViaVersion && !hasViaBackwards) {
                        emitConsole(
                            LogLevel.INFO,
                            "ViaVersion lets NEWER clients join; for OLDER clients also install ViaBackwards " +
                                "(and ViaRewind for 1.7/1.8) unless every client matches the server version."
                        )
                    }
                }
                if (mods.isNotEmpty()) {
                    emitConsole(LogLevel.WARN, "mods/ is not used by Paper/Spigot; use plugins/ for Bukkit plugins.")
                }
            }
            ServerFlavor.FABRIC, ServerFlavor.FORGE -> {
                if (mods.isEmpty()) {
                    emitConsole(LogLevel.WARN, "No mod JARs found in mods/ — Fabric/Forge mods load from mods/, not plugins/.")
                }
                if (plugins.isNotEmpty()) {
                    emitConsole(LogLevel.WARN, "plugins/ is ignored by Fabric/Forge; put mods in mods/ instead.")
                }
            }
            ServerFlavor.GENERIC -> Unit
        }
    }

    // =====================================================================
    // LAN access / Minecraft discovery
    // =====================================================================

    /**
     * Prints the LAN addresses clients can use *before* the server is spawned
     * and explains connectivity problems (no Wi-Fi, VPN, mobile data).
     *
     * Only Wi-Fi / ethernet / hotspot interfaces with a private IPv4 address
     * are used: cellular (`ccmni*`, `rmnet*`) and VPN (`tun*`) addresses are
     * not reachable from the user's LAN, which was the original bug.
     */
    private fun emitLanOverview(instance: ServerInstance) {
        val endpoints = runCatching { lanInfo.endpoints() }.getOrDefault(emptyList())
        val network = runCatching { lanInfo.networkState() }.getOrNull()
        val port = resolveLanPort(instance, null)
        if (endpoints.isEmpty()) {
            emitConsole(
                LogLevel.WARN,
                "LAN access: no usable LAN address. ${buildNoLanWarning(network)}"
            )
        } else {
            for (endpoint in endpoints) {
                val shown = if (instance.isMinecraft) {
                    port?.let { endpoint.hostPort(it) } ?: endpoint.address
                } else {
                    port?.let { endpoint.httpUrl(it) } ?: endpoint.address
                }
                emitConsole(
                    LogLevel.INFO,
                    "LAN address: $shown (${endpoint.label}, ${endpoint.interfaceName})"
                )
            }
            if (endpoints.size > 1) {
                emitConsole(
                    LogLevel.INFO,
                    "Multiple LAN interfaces found. Use the address of the network your client " +
                        "device is connected to; all of them are listed in the server's LAN card."
                )
            }
        }
        if (network?.vpnActive == true) {
            emitConsole(
                LogLevel.WARN,
                "A VPN is active. VPNs often block incoming LAN connections; turn the VPN off " +
                    "while hosting and try again."
            )
        }
        if (!instance.isMinecraft) return

        emitConsole(
            LogLevel.INFO,
            "Minecraft dedicated servers do not appear in the LAN list by themselves. JavaHost " +
                "announces this server over multicast once it is ready (Settings -> Minecraft LAN " +
                "discovery announcement). If it still is not listed, use Add Server manually."
        )
        if (port != null && endpoints.isNotEmpty()) {
            emitConsole(
                LogLevel.INFO,
                "Minecraft: add server ${endpoints.first().hostPort(port)} if it is not listed automatically."
            )
        }

        val hasGeyser = runCatching {
            paths.pluginsDir(instance.id).listFiles { f ->
                f.isFile && f.extension.equals("jar", true) && f.name.contains("geyser", true)
            }?.isNotEmpty() == true
        }.getOrDefault(false)
        if (hasGeyser) {
            emitConsole(
                LogLevel.INFO,
                "Geyser/Bedrock: connect Bedrock clients to ${endpoints.firstOrNull()?.address ?: "<phone-ip>"}:19132 (UDP). " +
                    "For Bedrock, install Floodgate for Paper from geysermc.org (Modrinth's Floodgate " +
                    "is Fabric/NeoForge only) and restart."
            )
        }
    }

    /**
     * Recomputes [LanStatus] for the active session (addresses, port, phone
     * connectivity, optional TCP self-test) and publishes it to the runtime so
     * the UI's LAN card stays current. Never throws.
     */
    private suspend fun refreshLan(session: Session, probe: Boolean, forceReport: Boolean = false) {
        if (session.finalized.get()) return
        val instance = instances.get(session.instanceId) ?: return
        val network = withContext(Dispatchers.IO) {
            runCatching { lanInfo.networkState() }.getOrNull()
        }
        val endpoints = withContext(Dispatchers.IO) {
            runCatching { lanInfo.endpoints() }.getOrDefault(emptyList())
        }
        val port = resolveLanPort(instance, session)
        var warning: String? = if (endpoints.isEmpty()) buildNoLanWarning(network) else null
        if (network?.vpnActive == true) {
            warning = listOfNotNull(
                warning,
                "A VPN is active; VPNs commonly block incoming LAN connections."
            ).joinToString(" ")
        }
        if (warning == null && endpoints.size > 1) {
            warning = "Several LAN addresses exist. Pick the one on the same network as the client device."
        }
        val appSettings = settings.current()
        val announcementEnabled = instance.isMinecraft &&
            appSettings.minecraftLanDiscovery &&
            session.announcerRunning
        val probes: List<LanProbeInfo> = if (probe && port != null && session.ready) {
            withContext(Dispatchers.IO) { endpoints.map { probeEndpoint(it, port) } }
        } else {
            emptyList()
        }
        // If none of the LAN addresses accept the connection but 127.0.0.1 does,
        // the server is bound to this phone only (the classic "works on localhost"
        // report). Say exactly that instead of the generic "not reachable".
        val bindWarning = if (probes.isNotEmpty() && probes.none { it.ok } && session.ready && port != null) {
            val loopbackOk = withContext(Dispatchers.IO) {
                tcpProbe.probe(LOOPBACK_HOST, port, LAN_PROBE_TIMEOUT_MS).ok
            }
            if (loopbackOk) {
                "The server accepts connections on 127.0.0.1 but not on its LAN address, so it " +
                    "is bound to this phone only. Clear server-ip in server.properties (or the " +
                    "server's own bind setting) and restart."
            } else {
                null
            }
        } else {
            null
        }
        warning = appendWarning(warning, bindWarning)
        warning = appendWarning(warning, dozeWarning())
        val lan = LanStatus(
            endpoints = endpoints.map { it.toInfo(instance, port) },
            port = port,
            wifiConnected = network?.wifiConnected == true,
            ethernetConnected = network?.ethernetConnected == true,
            mobileDataConnected = network?.mobileDataConnected == true,
            vpnActive = network?.vpnActive == true,
            announcementEnabled = announcementEnabled,
            probes = probes,
            warning = warning,
            summary = buildLanSummary(instance, endpoints, port, announcementEnabled, session.ready)
        )
        session.lanRefreshAt = System.currentTimeMillis()
        val stillCurrent = synchronized(lock) { this.session } === session
        if (!stillCurrent) return
        _runtime.update { status ->
            if (status.instanceId == session.instanceId && isActiveState(status.state)) {
                status.copy(lan = lan)
            } else {
                status
            }
        }
        if (forceReport || !session.lanReported) {
            session.lanReported = true
            emitLanRefreshReport(lan)
        } else if (probes.isNotEmpty()) {
            for (info in probes) emitProbeConsole(info)
        }
    }

    /** One self-test with a retry: slow phones/GC pauses produce spurious timeouts. */
    private fun probeEndpoint(endpoint: LanEndpoint, port: Int): LanProbeInfo {
        var outcome = tcpProbe.probe(endpoint.address, port, LAN_PROBE_TIMEOUT_MS)
        if (outcome.result == LanProbeResult.TIMEOUT) {
            outcome = tcpProbe.probe(endpoint.address, port, LAN_PROBE_TIMEOUT_MS)
        }
        return LanProbeInfo(
            address = endpoint.address,
            port = port,
            result = outcome.result.name.lowercase(Locale.US),
            ok = outcome.ok,
            latencyMs = outcome.latencyMs,
            detail = outcome.detail
        )
    }

    private fun appendWarning(current: String?, extra: String?): String? = when {
        extra.isNullOrBlank() -> current
        current.isNullOrBlank() -> extra
        else -> "$current $extra"
    }

    /**
     * Android suspends app network access in Doze even for a foreground service;
     * LAN clients then time out while the phone is idle. Surface it as a warning
     * with the fix the app already documents in the power guide.
     */
    private fun dozeWarning(): String? {
        val powerManager = runCatching {
            appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return null
        val idle = runCatching { powerManager.isDeviceIdleMode }.getOrDefault(false)
        val ignoring = runCatching {
            powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        }.getOrDefault(true)
        return when {
            idle ->
                "The phone is in Doze (screen off): Android suspends network access, so LAN " +
                    "clients may time out until the screen is on. Exempt JavaHost from battery optimization."
            !ignoring ->
                "Battery optimization is on; Android may suspend JavaHost's network access while " +
                    "the screen is off. Exempt it from battery optimization to keep LAN clients connected."
            else -> null
        }
    }

    /**
     * Applies the live discovery setting and interface changes to the running
     * server: toggling "Minecraft LAN discovery announcement" takes effect
     * immediately, and a Wi-Fi/hotspot change re-binds the announcer to the new
     * interface (a datagram sent on the wrong interface advertises the wrong IP).
     */
    private fun reconcileLanAnnouncement(session: Session) {
        if (!session.isMinecraft || !session.ready) return
        if (!settings.current().minecraftLanDiscovery) {
            if (session.announcerRunning) {
                stopLanAnnouncement(session)
                emitConsole(
                    LogLevel.INFO,
                    "Minecraft LAN discovery announcement turned off. Clients can still use the " +
                        "LAN address above with Add Server."
                )
            }
            return
        }
        if (!session.announcerRunning) {
            if (System.currentTimeMillis() - session.lastAnnounceAttemptAt < ANNOUNCE_RETRY_MS) return
            startLanAnnouncement(session)
            return
        }
        val currentInterface = runCatching { lanInfo.preferredNetworkInterface()?.name }.getOrNull()
        if (currentInterface != null && currentInterface != session.announcedInterface) {
            stopLanAnnouncement(session)
            startLanAnnouncement(session)
        }
    }

    private fun emitLanRefreshReport(lan: LanStatus) {
        lan.warning?.let { emitConsole(LogLevel.WARN, "LAN: $it") }
        lan.summary?.let { emitConsole(LogLevel.INFO, it) }
        for (info in lan.probes) emitProbeConsole(info)
    }

    private fun emitProbeConsole(info: LanProbeInfo) {
        val prefix = "LAN self-test ${info.address}:${info.port}"
        when {
            info.ok -> emitConsole(
                LogLevel.INFO,
                "$prefix reachable" +
                    (info.latencyMs?.let { " ($it ms)" } ?: "") +
                    " - the server accepts connections on this address. (The test runs from the " +
                    "phone itself; if another device still fails, check router/client isolation " +
                    "or its firewall.)"
            )
            info.result.equals("refused", true) || info.result.equals("timeout", true) -> emitConsole(
                LogLevel.WARN,
                "$prefix could not connect (${info.result}). The server may be listening on " +
                    "127.0.0.1 only, or a firewall / VPN / router isolation is blocking it. " +
                    "Clear server-ip in server.properties and see the troubleshooting guide."
            )
            else -> emitConsole(
                LogLevel.WARN,
                "$prefix failed (${info.result}${info.detail?.let { ": $it" } ?: ""})."
            )
        }
    }

    private fun buildNoLanWarning(network: LanNetworkState?): String = when {
        network == null ->
            "The app could not read the phone's network interfaces. Open Android settings and " +
                "check the Wi-Fi connection."
        network.vpnActive && !network.wifiConnected && !network.ethernetConnected ->
            "Only a VPN network is active. Connect to the same Wi-Fi/hotspot as the client " +
                "devices, or turn the VPN off."
        network.mobileDataConnected && !network.wifiConnected && !network.ethernetConnected ->
            "The phone is using mobile data only, so other devices cannot reach it over the LAN. " +
                "Connect the phone to the same Wi-Fi/hotspot as your clients first."
        else ->
            "No private IPv4 LAN address was found. Connect the phone to the same Wi-Fi/hotspot " +
                "as the client devices (or turn on the phone's hotspot and join it from the client)."
    }

    private fun buildLanSummary(
        instance: ServerInstance,
        endpoints: List<LanEndpoint>,
        port: Int?,
        announcementEnabled: Boolean,
        ready: Boolean
    ): String? {
        if (endpoints.isEmpty()) return null
        val first = endpoints.first()
        if (port == null) {
            return "Start the server so JavaHost can report which port clients should use."
        }
        val target = if (instance.isMinecraft) first.hostPort(port) else first.httpUrl(port)
        return if (instance.isMinecraft) {
            val discovery = when {
                !ready -> "the LAN announcement starts when the server is ready"
                announcementEnabled -> "LAN auto-discovery is ON"
                else -> "LAN auto-discovery is OFF (Settings -> Minecraft LAN discovery announcement)"
            }
            "Minecraft: add server $target (${first.label}) in the game; $discovery."
        } else {
            "From another device on the same Wi-Fi/hotspot open $target (${first.label})."
        }
    }

    private fun LanEndpoint.toInfo(instance: ServerInstance, port: Int?): LanEndpointInfo {
        val shownPort = port?.let { hostPort(it) }
        val url = if (!instance.isMinecraft && port != null) httpUrl(port) else null
        return LanEndpointInfo(
            address = address,
            interfaceName = interfaceName,
            label = label,
            hostPort = shownPort,
            url = url
        )
    }

    /** Minecraft: the advertised port (server.properties wins, then runtime default). */
    private fun resolveLanPort(instance: ServerInstance, session: Session?): Int? {
        if (instance.isMinecraft) {
            val propertyPort = runCatching {
                ServerPropertiesCodec.port(ServerPropertiesCodec.read(paths.serverProperties(instance.id)))
            }.getOrNull()
            return propertyPort ?: session?.serverPort ?: instance.serverPort ?: 25565
        }
        return session?.serverPort ?: instance.serverPort
    }

    /**
     * Starts the MC multicast announcement for a ready server (idempotent).
     * Best effort: failure is reported but never blocks the server.
     */
    private fun startLanAnnouncement(session: Session) {
        if (!session.isMinecraft || session.announcerRunning) return
        if (session.finalized.get() || synchronized(lock) { this.session } !== session) return
        session.lastAnnounceAttemptAt = System.currentTimeMillis()
        if (!settings.current().minecraftLanDiscovery) {
            emitConsole(
                LogLevel.INFO,
                "Minecraft LAN discovery announcement is OFF. Clients can still use the LAN " +
                    "address above with Add Server, or enable it in Settings."
            )
            return
        }
        val instance = instances.get(session.instanceId) ?: return
        val port = resolveLanPort(instance, session) ?: return
        val motdProperty = runCatching {
            ServerPropertiesCodec.read(paths.serverProperties(instance.id))[ServerPropertiesCodec.MOTD]
        }.getOrNull()
        val motd = MinecraftLanAnnouncement.sanitizeMotd(motdProperty, instance.name)
        val announcer = lanAnnouncer ?: MinecraftLanAnnouncer(lanInfo).also { lanAnnouncer = it }
        session.announcedInterface =
            runCatching { lanInfo.preferredNetworkInterface()?.name }.getOrNull()
        val started = runCatching {
            announcer.start(MinecraftLanAnnouncer.Announcement(motd, port))
        }.getOrDefault(false)
        // Re-check after the slow disk/interface work above: a stop/finish that
        // landed in that window already called stopLanAnnouncement(), so this
        // late start would otherwise become a ghost announcement for a dead run.
        if (started && (session.finalized.get() || synchronized(lock) { this.session } !== session)) {
            stopLanAnnouncement(session)
            return
        }
        session.announcerRunning = started
        if (started) {
            emitConsole(
                LogLevel.INFO,
                "Minecraft LAN discovery: announcing \"$motd\" on UDP 224.0.2.60:4445 " +
                    "(port $port). The server should appear under \"Scanning for games on your " +
                    "local network\" in Minecraft."
            )
        } else {
            emitConsole(
                LogLevel.WARN,
                "Could not start the Minecraft LAN discovery announcement. Clients can still " +
                    "connect by adding the LAN address above manually."
            )
        }
    }

    private fun stopLanAnnouncement(session: Session) {
        session.announcerRunning = false
        runCatching { lanAnnouncer?.stop() }
    }

    /**
     * A stale `server-ip=127.0.0.1` (or an address of the phone only) is the
     * classic "works on localhost, unreachable on the LAN" trap. Clear loopback
     * binds before the JVM starts and tell the user.
     */
    private fun normalizeMinecraftBindAddress(instance: ServerInstance) {
        if (!instance.isMinecraft) return
        val file = paths.serverProperties(instance.id)
        if (!file.isFile) return
        val raw = runCatching { ServerPropertiesCodec.readRaw(file) }.getOrNull() ?: return
        val entries = runCatching { ServerPropertiesCodec.parse(raw) }.getOrNull() ?: return
        val current = ServerPropertiesCodec.serverIp(entries)
        val normalized = ServerPropertiesCodec.normalizeBindAddress(current)
        if (normalized == (current ?: "")) return
        val updated = entries.toMutableMap()
        runCatching { ServerPropertiesCodec.setServerIp(updated, normalized) }
        runCatching { ServerPropertiesCodec.writeText(file, updated, raw) }
            .onSuccess {
                emitConsole(
                    LogLevel.WARN,
                    "server-ip was \"${current ?: ""}\", which only accepts connections on this " +
                        "phone. Cleared it before launch so other devices can connect (server.properties)."
                )
            }
            .onFailure {
                emitConsole(
                    LogLevel.WARN,
                    "server-ip is set to a phone-only address ($current), so other devices cannot " +
                        "connect. Remove it from server.properties and restart."
                )
            }
    }

    private suspend fun stopSession(session: Session, force: Boolean) {
        if (!session.stopInitiated.compareAndSet(false, true)) {
            withTimeoutOrNull(GRACEFUL_STOP_MAX_MS + 10_000L) { session.finished.await() }
            return
        }
        session.stopping = true
        session.stopRequested = true
        if (force) session.forceRequested = true
        stopLanAnnouncement(session)

        val alive = isProcessAlive(session.process)
        if (alive) {
            if (force) {
                emitConsole(LogLevel.INFO, "Force stopping the server…")
                runCatching { session.process.destroy() }
            } else {
                if (session.isMinecraft) {
                    if (writeStdin(session, "stop")) {
                        emitConsole(LogLevel.INFO, "Sent stop command; waiting for the server to shut down…")
                    } else {
                        emitConsole(LogLevel.WARN, "Could not send the stop command; terminating the process.")
                    }
                } else {
                    emitConsole(LogLevel.INFO, "Stopping the server…")
                }
                val graceMs = if (session.isMinecraft) GRACEFUL_STOP_MAX_MS else GENERIC_STOP_GRACE_MS
                val deadline = System.currentTimeMillis() + graceMs
                while (System.currentTimeMillis() < deadline) {
                    if (session.exitDeferred.isCompleted) break
                    if (session.forceRequested) break
                    delay(WAIT_POLL_MS)
                }
            }
            if (!session.exitDeferred.isCompleted) {
                if (!force) {
                    emitConsole(LogLevel.WARN, "The server did not stop in time; terminating the process.")
                }
                runCatching { session.process.destroy() }
                val deadline = System.currentTimeMillis() + KILL_GRACE_MS
                while (System.currentTimeMillis() < deadline && !session.exitDeferred.isCompleted) {
                    delay(WAIT_POLL_MS)
                }
                if (!session.exitDeferred.isCompleted) {
                    emitConsole(LogLevel.WARN, "The process ignored termination; killing it.")
                    forceKill(session)
                }
            }
        }
        withTimeoutOrNull(10_000L) { session.finished.await() }

        // Last-resort safety net: a child that never reports exit (e.g. an
        // OEM-killed but still-visible process or a stuck exit hook) must not
        // leave the UI/service in STOPPING forever. Kill by pid directly, then
        // finalize the session so the notification and restart path recover.
        if (!session.finished.isCompleted) {
            emitConsole(LogLevel.WARN, "The server process did not confirm exit; forcing it down.")
            forceKill(session)
            withTimeoutOrNull(KILL_GRACE_MS) { session.finished.await() }
        }
        if (!session.finished.isCompleted) {
            emitConsole(LogLevel.ERROR, "Could not reap the server process; resetting its session state.")
            runCatching { session.exitDeferred.complete(-1) }
            runCatching { finishSession(session, -1) }
            resetRuntimeAfterForcedStop(session.instanceId)
        }
    }

    /** SIGKILL by resolved pid plus the platform Process API, belt and braces. */
    private fun forceKill(session: Session) {
        val pid = session.pid ?: runCatching { ChildPidResolver.resolve(session.process) }.getOrNull()
        if (pid != null && pid > 0L) {
            runCatching { Os.kill(pid.toInt(), OsConstants.SIGKILL) }
        }
        runCatching { session.process.destroyForcibly() }
    }

    private fun writeStdin(session: Session, line: String): Boolean {
        synchronized(session.stdinLock) {
            return try {
                val stream = session.process.outputStream
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    // =====================================================================
    // Exit handling / crash post-mortem
    // =====================================================================

    private fun finishSession(session: Session, exitCode: Int) {
        if (!session.finalized.compareAndSet(false, true)) return
        stopLanAnnouncement(session)
        session.statsJob?.cancel()
        val now = System.currentTimeMillis()
        val duration = (now - session.startedAt).coerceAtLeast(0L)
        val tail = session.tailSnapshot(CRASH_TAIL_LINES)
        val userStop = session.stopRequested ||
            session.stopInitiated.get() ||
            session.stopping
        val classified = ExitClassifier.classify(
            exitCode = exitCode,
            stopRequested = userStop,
            force = session.forceRequested,
            tail = tail,
            observedSignal = session.observedSignal
        )
        val expected = classified.expected || userStop
        var exitInfo = ExitInfo(
            exitCode = exitCode,
            signal = classified.signal,
            timestamp = now,
            expected = expected,
            reason = classified.reason,
            summary = classified.summary,
            tail = tail,
            reportPath = null
        )
        var reportPath: String? = null
        if (!expected) {
            reportPath = runCatching { writeCrashReport(session, exitInfo, duration) }
                .getOrNull()?.absolutePath
            if (reportPath != null) exitInfo = exitInfo.copy(reportPath = reportPath)
        }
        runCatching { session.logTail.close() }
        runCatching {
            instances.update(session.instanceId) { instance ->
                instance.copy(
                    lastExitCode = exitCode,
                    lastExitAt = now,
                    lastCrashSummary = if (expected) null else classified.summary,
                    totalRuntimeMs = instance.totalRuntimeMs + duration,
                    serverPort = session.serverPort ?: instance.serverPort
                )
            }
        }
        if (expected) {
            lastCrash = null
            emitConsole(LogLevel.INFO, "Server stopped (exit code $exitCode).")
        } else {
            lastCrash = exitInfo
            emitConsole(
                LogLevel.ERROR,
                "Server exited unexpectedly: ${classified.summary} (exit code $exitCode)"
            )
            reportPath?.let { emitConsole(LogLevel.ERROR, "Crash report saved to $it") }
        }
        val (availMb, totalMb) = deviceMemoryMb()
        val finalStatus = ServerRuntimeStatus(
            state = if (expected) RunState.STOPPED else RunState.CRASHED,
            instanceId = session.instanceId,
            instanceName = session.instanceName,
            pid = null,
            startedAt = null,
            uptimeMs = 0L,
            playersOnline = 0,
            playersMax = 0,
            heapUsedMb = null,
            heapMaxMb = session.plan.memoryMb,
            processRssMb = null,
            deviceAvailMb = availMb,
            deviceTotalMb = totalMb,
            ready = false,
            lastExit = exitInfo
        )
        // Clear + publish atomically with respect to start(): a new session must
        // never be overwritten by the previous run's final status.
        synchronized(lock) {
            if (session === this.session) this.session = null
            _runtime.value = finalStatus
        }
        session.finished.complete(Unit)
    }

    private fun writeCrashReport(session: Session, exitInfo: ExitInfo, durationMs: Long): File? {
        val reportTime = Instant.ofEpochMilli(exitInfo.timestamp)
        val header = buildList {
            add("JavaHost crash report")
            add("====================")
            add("Instance: ${session.instanceName} (${session.instanceId})")
            add("Time: $reportTime")
            add("Exit code: ${exitInfo.exitCode ?: "unknown"}${exitInfo.signal?.let { " (signal $it)" } ?: ""}")
            add("Reason: ${exitInfo.reason}")
            add("Summary: ${exitInfo.summary}")
            add("Uptime: ${String.format(Locale.US, "%.1f", durationMs / 1000.0)} s")
            add("Memory: ${session.plan.memoryMb} MB (-Xms=Xmx)")
            add("Java: ${session.plan.javaExecutable.absolutePath}")
            add("Working dir: ${session.plan.workingDir.absolutePath}")
            add("")
            add("Launch command:")
            add("  ${session.plan.commandLine()}")
            add("")
            add("Environment additions:")
            for ((key, value) in session.plan.environment) add("  $key=$value")
            add("")
            add("Server state: players=${session.playersOnline}/${session.playersMax}, ready=${session.ready}")
        }
        return session.logTail.writeCrashReport(exitInfo.timestamp, header, exitInfo.tail)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun recoverInitialStatus(): ServerRuntimeStatus {
        val (availMb, totalMb) = deviceMemoryMb()
        val lastId = settings.current().lastActiveInstanceId
            ?: return ServerRuntimeStatus(deviceAvailMb = availMb, deviceTotalMb = totalMb)
        val instance = instances.get(lastId)
        if (instance == null) {
            return ServerRuntimeStatus(deviceAvailMb = availMb, deviceTotalMb = totalMb)
        }
        val base = ServerRuntimeStatus(
            state = RunState.STOPPED,
            instanceId = lastId,
            instanceName = instance.name,
            deviceAvailMb = availMb,
            deviceTotalMb = totalMb
        )
        val crashSummary = instance.lastCrashSummary
        val crashAt = instance.lastExitAt
        if (crashSummary != null && crashAt != null) {
            val exit = ExitInfo(
                exitCode = instance.lastExitCode,
                signal = ExitClassifier.signalOf(instance.lastExitCode),
                timestamp = crashAt,
                expected = false,
                reason = ExitReason.CRASH_UNKNOWN,
                summary = crashSummary,
                tail = emptyList(),
                reportPath = null
            )
            lastCrash = exit
            return base.copy(state = RunState.CRASHED, lastExit = exit)
        }
        return base
    }

    private fun isProcessAlive(process: Process): Boolean = try {
        process.isAlive
    } catch (t: Throwable) {
        false
    }

    private fun deviceMemoryMb(): Pair<Long, Long> {
        return try {
            val activityManager =
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return 0L to 0L
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            val mb = 1024L * 1024L
            (info.availMem / mb) to (info.totalMem / mb)
        } catch (t: Throwable) {
            0L to 0L
        }
    }

    private fun memoryCeilingMb(): Int {
        val total = deviceMemoryMb().second
        if (total <= 0L) return LaunchPlanBuilder.MAX_HEAP_MB
        val ceiling = (total - 1024L).coerceIn(
            512L,
            LaunchPlanBuilder.MAX_HEAP_MB.toLong()
        )
        return ceiling.toInt()
    }

    private fun isActiveState(state: RunState): Boolean =
        state == RunState.STARTING || state == RunState.RUNNING || state == RunState.STOPPING

    // =====================================================================
    // Session
    // =====================================================================

    private class Session(
        val instanceId: String,
        val instanceName: String,
        val isMinecraft: Boolean,
        val plan: LaunchPlan,
        val process: Process,
        val startedAt: Long,
        val logTail: LogTailStore,
        @Volatile var pid: Long?
    ) {
        val exitDeferred = CompletableDeferred<Int>()
        val finished = CompletableDeferred<Unit>()
        val stopInitiated = AtomicBoolean(false)
        val finalized = AtomicBoolean(false)
        val stdinLock = Any()

        private val tailLock = Any()
        private val rawTail = ArrayDeque<String>()

        @Volatile var stopRequested = false
        @Volatile var forceRequested = false
        @Volatile var stopping = false
        @Volatile var viaVersionHintEmitted = false
        @Volatile var geyserNativeHintEmitted = false
        @Volatile var ready = false
        @Volatile var playersOnline = 0
        @Volatile var playersMax = 0
        @Volatile var serverPort: Int? = null
        @Volatile var observedSignal: CrashSignal? = null
        @Volatile var eulaPromptSeen = false
        @Volatile var rssHint: Long? = null
        @Volatile var readerJob: Job? = null
        @Volatile var waitJob: Job? = null
        @Volatile var statsJob: Job? = null
        @Volatile var lanReported = false
        @Volatile var lanRefreshAt = 0L
        @Volatile var announcerRunning = false
        @Volatile var announcedInterface: String? = null
        @Volatile var lastAnnounceAttemptAt = 0L

        fun pushTail(line: String) {
            synchronized(tailLock) {
                rawTail.addLast(line)
                while (rawTail.size > ServerManager.RAW_TAIL_LINES) rawTail.removeFirst()
            }
        }

        fun tailSnapshot(count: Int): List<String> {
            if (count <= 0) return emptyList()
            synchronized(tailLock) { return rawTail.takeLast(count) }
        }
    }

    private companion object {
        const val STATS_INTERVAL_MS = 2_000L
        const val HEAP_SAMPLE_INTERVAL_MS = 15_000L
        const val STARTING_FALLBACK_RUNNING_MS = 60_000L
        const val CRASH_TAIL_LINES = 200
        const val RAW_TAIL_LINES = 400
        const val MIN_CONSOLE_LIMIT = 200
        const val MAX_CONSOLE_LIMIT = 20_000
        const val CONSOLE_PUBLISH_INTERVAL_MS = 100L
        const val MAX_CONSOLE_LINE_CHARS = 8_000
        const val LAN_REFRESH_INTERVAL_MS = 10_000L
        const val LAN_READY_SETTLE_MS = 750L
        const val LAN_PROBE_TIMEOUT_MS = 2_500
        const val ANNOUNCE_RETRY_MS = 30_000L
        const val LOOPBACK_HOST = "127.0.0.1"
        const val GRACEFUL_STOP_MAX_MS = 20_000L
        const val GENERIC_STOP_GRACE_MS = 2_000L
        const val KILL_GRACE_MS = 3_000L
        const val WAIT_POLL_MS = 100L
    }
}
