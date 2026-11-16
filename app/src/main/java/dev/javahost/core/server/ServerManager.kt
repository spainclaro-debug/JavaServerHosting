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
import dev.javahost.core.model.BedrockLanStatus
import dev.javahost.core.model.ConsoleEntry
import dev.javahost.core.model.ExitInfo
import dev.javahost.core.model.ExitReason
import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.LanEndpointInfo
import dev.javahost.core.model.LanProbeInfo
import dev.javahost.core.model.LanStatus
import dev.javahost.core.model.LogLevel
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.model.ServerRuntimeStatus
import dev.javahost.core.model.StartResult
import dev.javahost.core.net.AndroidLanInfo
import dev.javahost.core.net.BedrockGeyserConfig
import dev.javahost.core.net.BedrockLanRelay
import dev.javahost.core.net.BedrockRakNet
import dev.javahost.core.net.LanAddressSelector
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hosts up to [ServerController.MAX_RUNNING_SERVERS] child JVMs at the same
 * time, one per server instance.
 *
 * Each instance gets its own session (process, stdin/stdout reader, on-disk log
 * tail, crash post-mortem, LAN announcement and — for the single Bedrock bridge
 * owner — UDP 19132 relay). The focused instance is what the console screen and
 * the ongoing notification show; [runtimes] and [consoleEntries] are always
 * scoped to it while [runtimes] carries every instance's live/terminal status.
 *
 * Lifecycle rules:
 *  - [start] is synchronous (spawns immediately), validates JRE/EULA/JAR/Port
 *    before spawning and rejects when 5 sessions are already active, when the
 *    instance is already active, or when another instance (running or merely
 *    configured) resolves to the same port;
 *  - [stop] is asynchronous and per-instance: the other servers keep running;
 *  - [restart] is asynchronous, serialized per instance, and never touches the
 *    other sessions;
 *  - [shutdown] kills every child process and cancels all coroutines.
 *
 * The Bedrock LAN bridge (UDP 19132 -> Geyser on UDP 19133) can only have one
 * owner because the app can bind UDP 19132 once. The first eligible Minecraft
 * session owns it; other Minecraft+Geyser sessions get a unique internal Geyser
 * port (19140+slot / 19145+slot) and a console warning telling the user to add
 * that address manually in Bedrock.
 *
 * "Unexpected" exits go through the crash post-mortem per session: classify,
 * save `crashes/crash-<ts>.txt` + `crashes/last-crash.txt`, persist exit fields
 * via [InstanceStore] and publish a [RunState.CRASHED] entry for that instance.
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

    /**
     * Coroutine scope for the controller's background jobs. It is recreated by
     * [ensureOperational] after [shutdown] because the foreground service is
     * destroyed after every normal stop and a later Start must work again
     * without restarting the app process.
     */
    @Volatile
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var disposed = false
    private val shutdownOnce = AtomicBoolean(false)

    // ---- multi-session lifecycle state ----------------------------------
    /** Active sessions keyed by instance id; guarded by [lock]. */
    private val sessions = LinkedHashMap<String, Session>()

    /** In-flight restart jobs keyed by instance id; guarded by [lock]. */
    private val restartJobs = LinkedHashMap<String, Job>()

    /** Instance that owns the single Bedrock LAN bridge (UDP 19132); guarded by [lock]. */
    private var bedrockOwnerId: String? = null

    /** Instances with an in-flight manual LAN probe. */
    private val probeInFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // ---- per-instance runtime status ------------------------------------
    private val initialRuntimes: Map<String, ServerRuntimeStatus> = recoverInitialRuntimes()
    private val initialFocusedId: String? = settings.current().lastActiveInstanceId

    private val _runtimes = MutableStateFlow(initialRuntimes)
    override val runtimes: StateFlow<Map<String, ServerRuntimeStatus>> = _runtimes.asStateFlow()

    private val _focusedId = MutableStateFlow(initialFocusedId)
    override val activeInstanceId: StateFlow<String?> = _focusedId.asStateFlow()

    /** Focused instance status (back-compat for "the" console/notification). */
    private val _runtime = MutableStateFlow(
        initialFocusedId?.let { initialRuntimes[it] } ?: ServerRuntimeStatus()
    )
    override val runtime: StateFlow<ServerRuntimeStatus> = _runtime.asStateFlow()

    // ---- console -------------------------------------------------------
    private val consoleLock = Any()
    private val consoleBuffers = ConcurrentHashMap<String, ArrayDeque<ConsoleEntry>>()
    private val seq = AtomicLong(0L)
    private val consoleDirty = AtomicBoolean(false)

    @Volatile
    private var consoleLimit: Int =
        settings.current().consoleLogLimit.coerceIn(MIN_CONSOLE_LIMIT, MAX_CONSOLE_LIMIT)

    private val _consoleEntries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    override val consoleEntries: StateFlow<List<ConsoleEntry>> = _consoleEntries.asStateFlow()

    /** Live console stream of the focused instance for consumers that prefer deltas. */
    private val _consoleLive = MutableSharedFlow<ConsoleEntry>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val consoleLive: SharedFlow<ConsoleEntry> = _consoleLive.asSharedFlow()

    private var consolePublisherJob: Job? = null

    init {
        startConsolePublisher()
    }

    private fun startConsolePublisher() {
        consolePublisherJob?.cancel()
        consolePublisherJob = scope.launch {
            while (isActive) {
                delay(CONSOLE_PUBLISH_INTERVAL_MS)
                publishConsoleIfDirty()
            }
        }
    }

    // ---- LAN access / Minecraft discovery -------------------------------
    private val lanInfo = AndroidLanInfo(appContext)
    private val tcpProbe: TcpProbe = SocketTcpProbe()
    private val bedrockClientGuid = kotlin.random.Random.nextLong()

    /**
     * What the Bedrock bridge/relocation needs for one launch: whether this
     * session owns the LAN bridge, whether Geyser was detected, the config file
     * that may be patched, the environment overrides passed to the child JVM and
     * the internal RakNet/WebRTC ports Geyser will use.
     */
    private data class BedrockBridgePlan(
        val requested: Boolean,
        val geyserDetected: Boolean,
        val geyserConfig: File?,
        val environment: Map<String, String>,
        val geyserInternalPort: Int,
        val webrtcPort: Int
    )

    /** Geyser detection result for one instance. */
    private data class GeyserDetection(val config: File?, val detected: Boolean)

    /** Unique internal ports reserved for a non-owner Minecraft+Geyser session. */
    private data class BedrockPorts(val raknetPort: Int, val webrtcPort: Int)

    // =====================================================================
    // Contract: start
    // =====================================================================

    /**
     * Revives the controller after a previous [shutdown] (the foreground service
     * calls it from onDestroy). Historically every normal Stop destroyed the
     * service, which permanently set [disposed] and made the next Start return
     * "JavaHost is shutting down." until the app process was killed. This makes
     * the process-wide singleton usable again.
     */
    private fun ensureOperational() {
        synchronized(lock) {
            if (!disposed) return
            disposed = false
            shutdownOnce.set(false)
            for (job in restartJobs.values) job.cancel()
            restartJobs.clear()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        startConsolePublisher()
    }

    override fun start(instanceId: String): StartResult {
        ensureOperational()
        synchronized(lock) {
            if (disposed) return StartResult.Rejected("JavaHost is shutting down.")
            if (sessions.size >= ServerController.MAX_RUNNING_SERVERS) {
                return StartResult.Rejected(
                    "JavaHost can run at most ${ServerController.MAX_RUNNING_SERVERS} servers at the " +
                        "same time. Stop one before starting another."
                )
            }
            val existing = sessions[instanceId]
            if (existing != null) {
                val name = existing.instanceName
                return StartResult.Rejected(
                    if (existing.stopInitiated.get()) {
                        "$name is still stopping. Try again in a moment."
                    } else {
                        "$name is already running. Stop it before starting it again."
                    }
                )
            }
            return runCatching { startInternal(instanceId) }
                .getOrElse { error ->
                    StartResult.Rejected(
                        "Could not start the server: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
        }
    }

    /**
     * Resolve + spawn one session. Caller must hold [lock]; all validation
     * happens before any resource is created.
     */
    private fun startInternal(instanceId: String): StartResult {
        val instance = instances.get(instanceId)
            ?: return StartResult.Rejected("Server instance not found. It may have been deleted.")
        val appSettings = settings.current()

        // 0. Port conflict: a start whose port is already used by another running
        //    session or merely configured instance would just fail inside the
        //    child JVM (or worse, bind 127.0.0.1 only). Reject with the owner name.
        val configuredPort = resolveConfiguredPort(instance)
        if (configuredPort != null) {
            val owner = portOwnerLocked(configuredPort, exceptInstanceId = instanceId)
            if (owner != null) {
                return StartResult.Rejected(portConflictMessage(configuredPort, owner))
            }
        }

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

        // Clear this instance's old console history before the launch diagnostics.
        prepareConsoleForStart(instanceId)

        // 3b. LAN bind address: a leftover loopback `server-ip` (or a server
        // record with a phone-only address) makes localhost work while every
        // other device gets "connection refused". Clear it before launch.
        normalizeMinecraftBindAddress(instance)

        // 3c. Bedrock Edition: decide whether this session owns the single LAN
        // bridge and which internal port its Geyser should use.
        val geyser = detectGeyser(instance)
        var currentOwnerId = bedrockOwnerId
        if (currentOwnerId != null && !sessions.containsKey(currentOwnerId)) {
            // The previous owner already finished; do not keep the bridge
            // permanently claimed by a dead session.
            bedrockOwnerId = null
            currentOwnerId = null
        }
        val bridgeWanted = appSettings.bedrockLanDiscovery && geyser.detected
        val bridgeEnabled = bridgeWanted && currentOwnerId == null
        val busyOwnerName = if (bridgeWanted && !bridgeEnabled) {
            currentOwnerId?.let { owner ->
                sessions[owner]?.instanceName ?: instances.get(owner)?.name
            } ?: "another server"
        } else {
            null
        }
        val nonOwnerPorts = if (bridgeWanted && !bridgeEnabled) pickBedrockNonOwnerPorts() else null
        val geyserPort = when {
            bridgeEnabled -> BEDROCK_GEYSER_PORT
            nonOwnerPorts != null -> nonOwnerPorts.raknetPort
            else -> BEDROCK_GEYSER_PORT
        }
        val webrtcPort = when {
            bridgeEnabled -> BEDROCK_GEYSER_WEBRTC_PORT
            nonOwnerPorts != null -> nonOwnerPorts.webrtcPort
            else -> BEDROCK_GEYSER_WEBRTC_PORT
        }
        val bedrockPlan = prepareBedrockBridge(
            instance = instance,
            bridgeEnabled = bridgeEnabled,
            geyserPort = geyserPort,
            webrtcPort = webrtcPort,
            geyserDetected = geyser.detected,
            geyserConfig = geyser.config,
            busyOwnerName = busyOwnerName
        )

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
        emitLanOverview(instance, bedrockPlan, busyOwnerName)

        val startedAt = System.currentTimeMillis()
        val process: Process = try {
            val builder = ProcessBuilder(plan.command)
            builder.directory(plan.workingDir)
            builder.redirectErrorStream(true)
            builder.environment().putAll(plan.environment)
            if (bedrockPlan.environment.isNotEmpty()) {
                builder.environment().putAll(bedrockPlan.environment)
            }
            builder.start()
        } catch (t: Throwable) {
            runCatching { logTail.close() }
            return StartResult.Rejected(
                "Could not start the Java process: ${t.message ?: t.javaClass.simpleName}. " +
                    "Check that the Java runtime is intact."
            )
        }

        // Ownership of the single Bedrock bridge is claimed only after the JVM
        // actually spawned, so a failed launch never blocks the bridge.
        if (bedrockPlan.requested) {
            bedrockOwnerId = instanceId
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
        activeSession.serverPort = instance.serverPort
        activeSession.resolvedPort = configuredPort
        activeSession.bedrockPlan = bedrockPlan
        activeSession.bedrockStatus = initialBedrockStatus(bedrockPlan, busyOwnerName)
        sessions[instanceId] = activeSession
        _focusedId.value = instanceId
        runCatching { settings.update { it.copy(lastActiveInstanceId = instanceId) } }
        runCatching {
            instances.update(instanceId) {
                it.copy(lastStartedAt = startedAt, lastCrashSummary = null)
            }
        }
        val (availMb, totalMb) = deviceMemoryMb()
        setRuntime(
            ServerRuntimeStatus(
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
        )
        publishFocusedConsole()
        emitConsole(
            instanceId,
            LogLevel.INFO,
            "Starting ${instance.name} (Java ${instance.jreMajor}, ${memoryMb} MB heap)"
        )
        if (busyOwnerName != null && bedrockPlan.geyserDetected && !bedrockPlan.requested) {
            emitConsole(
                instanceId,
                LogLevel.WARN,
                "Bedrock LAN bridge is in use by $busyOwnerName; this server's Geyser uses UDP " +
                    "${bedrockPlan.geyserInternalPort} — add it manually in Bedrock for that server."
            )
        }

        activeSession.readerJob = scope.launch { readerLoop(activeSession) }
        activeSession.waitJob = scope.launch { waitLoop(activeSession) }
        activeSession.statsJob = scope.launch { statsLoop(activeSession) }
        scope.launch {
            runCatching { refreshLan(activeSession, probe = false) }
        }
        return StartResult.Ok
    }

    // =====================================================================
    // Contract: stop / restart / focus / commands
    // =====================================================================

    override fun stop(instanceId: String, force: Boolean) {
        val target: Session = synchronized(lock) {
            val current = sessions[instanceId] ?: return
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
        // the Activity may be gone; each session stops independently.
        scope.launch { stopSession(target, force) }
    }

    override fun restart(instanceId: String) {
        ensureOperational()
        synchronized(lock) {
            if (restartJobs[instanceId]?.isActive == true) return
            if (!sessions.containsKey(instanceId)) return // contract: no-op when nothing is active
            val job = scope.launch { performRestart(instanceId) }
            restartJobs[instanceId] = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (restartJobs[instanceId] === job) restartJobs.remove(instanceId)
                }
            }
        }
    }

    private suspend fun performRestart(instanceId: String) {
        try {
            ensureOperational()
            val current = synchronized(lock) { sessions[instanceId] }
            if (current != null) {
                synchronized(lock) {
                    if (!current.stopping) current.stopping = true
                    current.stopRequested = true
                }
                publishStopping(current)
                stopSession(current, force = false)
                withTimeoutOrNull(GRACEFUL_STOP_MAX_MS + 30_000L) { current.finished.await() }
            }
            val result = synchronized(lock) {
                when {
                    disposed -> StartResult.Rejected("JavaHost is shutting down.")
                    sessions.containsKey(instanceId) ->
                        StartResult.Rejected("The previous server is still shutting down.")
                    sessions.size >= ServerController.MAX_RUNNING_SERVERS ->
                        StartResult.Rejected(
                            "JavaHost can run at most ${ServerController.MAX_RUNNING_SERVERS} " +
                                "servers at the same time. Stop one before restarting this one."
                        )
                    else -> runCatching { startInternal(instanceId) }
                        .getOrElse { error ->
                            StartResult.Rejected(
                                "Could not start the server: ${error.message ?: error.javaClass.simpleName}"
                            )
                        }
                }
            }
            if (result is StartResult.Rejected) {
                emitConsole(instanceId, LogLevel.ERROR, "Restart failed: ${result.message}")
                resetStoppingState(instanceId)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emitConsole(
                instanceId,
                LogLevel.ERROR,
                "Restart failed: ${t.message ?: t.javaClass.simpleName}"
            )
            resetStoppingState(instanceId)
        }
    }

    private fun resetStoppingState(instanceId: String) {
        updateRuntime(instanceId) { status ->
            if (status.state == RunState.STOPPING) status.copy(state = RunState.STOPPED) else status
        }
    }

    override fun focus(instanceId: String) {
        val existing = synchronized(lock) { _runtimes.value[instanceId] }
        val status = existing ?: run {
            val instance = instances.get(instanceId)
            val (availMb, totalMb) = deviceMemoryMb()
            ServerRuntimeStatus(
                state = RunState.STOPPED,
                instanceId = instanceId,
                instanceName = instance?.name,
                deviceAvailMb = availMb,
                deviceTotalMb = totalMb
            )
        }
        synchronized(lock) {
            _focusedId.value = instanceId
            if (existing == null) {
                _runtimes.value = _runtimes.value.toMutableMap().apply { put(instanceId, status) }
            }
            _runtime.value = status
        }
        publishFocusedConsole()
    }

    override fun sendCommand(instanceId: String, command: String) {
        val line = command.trim()
        if (line.isEmpty()) return
        val current = synchronized(lock) { sessions[instanceId] ?: return }
        if (!isProcessAlive(current.process)) {
            emitConsole(
                instanceId,
                LogLevel.WARN,
                "The server is no longer running; command ignored."
            )
            return
        }
        if (writeStdin(current, line)) {
            emitConsole(instanceId, LogLevel.COMMAND, line)
        } else {
            emitConsole(
                instanceId,
                LogLevel.WARN,
                "Could not send the command to the server process."
            )
        }
    }

    override fun clearConsole(instanceId: String) {
        synchronized(consoleLock) {
            consoleBuffers[instanceId]?.clear()
        }
        if (_focusedId.value == instanceId) {
            consoleDirty.set(false)
            _consoleEntries.value = emptyList()
        }
    }

    override fun exportConsole(instanceId: String, destination: File): Result<File> = runCatching {
        val entries = synchronized(consoleLock) {
            consoleBuffers[instanceId]?.toList() ?: emptyList()
        }
        destination.parentFile?.mkdirs()
        destination.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (entry in entries) {
                writer.write("[${Instant.ofEpochMilli(entry.timestamp)}] [${entry.level}] ${entry.text}")
                writer.newLine()
            }
        }
        destination
    }

    override fun recentTail(instanceId: String, count: Int): List<String> {
        if (count <= 0) return emptyList()
        val current = synchronized(lock) { sessions[instanceId] }
        if (current != null) return current.tailSnapshot(count)
        val crash = synchronized(lock) { _runtimes.value[instanceId]?.lastExit }
        crash?.takeIf { it.tail.isNotEmpty() }?.let { return it.tail.takeLast(count) }
        for (file in diskTailCandidates(instanceId)) {
            val lines = LogTailStore.tailOf(file, count)
            if (lines.isNotEmpty()) return lines
        }
        return emptyList()
    }

    override fun activeLastCrash(instanceId: String): ExitInfo? =
        synchronized(lock) { _runtimes.value[instanceId]?.lastExit }

    override fun clearLastCrash(instanceId: String) {
        updateRuntime(instanceId) { status ->
            if (status.state == RunState.CRASHED) {
                status.copy(state = RunState.STOPPED, lastExit = null)
            } else {
                status.copy(lastExit = null)
            }
        }
        runCatching {
            instances.update(instanceId) { it.copy(lastCrashSummary = null) }
        }
    }

    override fun probeLan(instanceId: String): Boolean {
        val current = synchronized(lock) { sessions[instanceId] } ?: return false
        if (!current.ready) {
            emitConsole(
                instanceId,
                LogLevel.INFO,
                "The server is still starting; the LAN self-test runs automatically once it is ready."
            )
        }
        if (!probeInFlight.add(instanceId)) return true
        scope.launch {
            try {
                runCatching { refreshLan(current, probe = true, forceReport = true) }
                if (current.bedrockRelayStarted) runCatching { refreshBedrock(current) }
            } finally {
                probeInFlight.remove(instanceId)
            }
        }
        return true
    }

    override fun hasActiveSession(instanceId: String): Boolean =
        synchronized(lock) { sessions.containsKey(instanceId) }

    override fun hasAnyActiveSession(): Boolean =
        synchronized(lock) { sessions.isNotEmpty() }

    override fun forceStop(instanceId: String): Boolean {
        val current: Session = synchronized(lock) {
            restartJobs.remove(instanceId)?.cancel()
            sessions.remove(instanceId)
        } ?: run {
            resetRuntimeAfterForcedStop(instanceId)
            return false
        }
        clearSessionNow(current)
        return true
    }

    override fun forceStopAll(): Int {
        val snapshot: List<Session> = synchronized(lock) {
            for (job in restartJobs.values) job.cancel()
            restartJobs.clear()
            val all = sessions.values.toList()
            sessions.clear()
            all
        }
        for (session in snapshot) clearSessionNow(session)
        return snapshot.size
    }

    /** Kills a (possibly wedged) session, finalizes it and publishes its terminal state. */
    private fun clearSessionNow(current: Session) {
        current.stopRequested = true
        current.forceRequested = true
        current.stopInitiated.set(true)
        stopLanAnnouncement(current)
        stopBedrockBridge(current)
        forceKill(current)
        val exited = runCatching { current.process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        val code = if (exited) runCatching { current.process.exitValue() }.getOrDefault(-1) else -1
        runCatching { current.exitDeferred.complete(code) }
        runCatching { finishSession(current, code) }
        resetRuntimeAfterForcedStop(current.instanceId)
        emitConsole(
            current.instanceId,
            LogLevel.WARN,
            "Server session was force-cleared after a stuck stop."
        )
    }

    private fun resetRuntimeAfterForcedStop(instanceId: String?) {
        val id = instanceId ?: _focusedId.value ?: return
        // A replacement session for the same instance may already be starting;
        // never clobber its runtime entry with a forced-stop reset.
        if (synchronized(lock) { sessions.containsKey(id) }) return
        updateRuntime(id) { status ->
            if (status.isActive) {
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
        val active: List<Session>
        val oldScope: CoroutineScope
        val oldPublisher: Job?
        synchronized(lock) {
            // Capture the generation we are tearing down before anything else:
            // a concurrent ensureOperational() may already be creating the next
            // scope/publisher, and shutdown() must only cancel what it captured.
            disposed = true
            oldScope = scope
            oldPublisher = consolePublisherJob
            active = sessions.values.toList()
            sessions.clear()
            for (job in restartJobs.values) job.cancel()
            restartJobs.clear()
            bedrockOwnerId = null
        }
        for (current in active) {
            current.stopRequested = true
            current.forceRequested = true
            stopLanAnnouncement(current)
            stopBedrockBridge(current)
            // Prevent waitLoop from running the normal post-mortem path; this is
            // an app shutdown, not a server crash.
            current.finalized.set(true)
            runCatching { current.process.destroy() }
            runCatching { if (current.process.isAlive) current.process.destroyForcibly() }
            current.statsJob?.cancel()
            current.readerJob?.cancel()
            current.waitJob?.cancel()
            runCatching { current.logTail.close() }
            current.exitDeferred.complete(-1)
            current.finished.complete(Unit)
            val previous = synchronized(lock) { _runtimes.value[current.instanceId] }
            val (availMb, totalMb) = deviceMemoryMb()
            setRuntime(
                ServerRuntimeStatus(
                    state = RunState.STOPPED,
                    instanceId = current.instanceId,
                    instanceName = current.instanceName,
                    deviceAvailMb = availMb,
                    deviceTotalMb = totalMb,
                    lastExit = previous?.lastExit
                )
            )
        }
        // Any stale "active" runtime without a session is a ghost snapshot: clear
        // it so the UI does not keep showing a running card after teardown.
        synchronized(lock) {
            val cleaned = _runtimes.value.mapValues { (_, status) ->
                if (status.isActive) {
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
            if (cleaned != _runtimes.value) {
                _runtimes.value = cleaned
                _runtime.value = cleaned[_focusedId.value] ?: _runtime.value
            }
        }
        oldPublisher?.cancel()
        if (consolePublisherJob === oldPublisher) consolePublisherJob = null
        oldScope.cancel()
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

    /**
     * Tail of the log for any instance, used by the UI after a restart/crash.
     * Prefers the in-memory tail of the active session, then the last crash
     * tail, then the app transcript (`console.log` / `console.previous.log`),
     * then the server's own `logs/latest.log` / `previous.log`, and finally
     * `crashes/last-crash.txt`.
     */
    override fun readStartupTail(instanceId: String, lines: Int): List<String> {
        if (lines <= 0) return emptyList()
        val current = synchronized(lock) { sessions[instanceId] }
        if (current != null) return current.tailSnapshot(lines)
        val crash = synchronized(lock) { _runtimes.value[instanceId]?.lastExit }
        if (crash != null && crash.tail.isNotEmpty()) return crash.tail.takeLast(lines)
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
    // Runtime publishing
    // =====================================================================

    /** Applies [transform] to one instance's runtime and mirrors it when focused. */
    private fun updateRuntime(
        instanceId: String,
        transform: (ServerRuntimeStatus) -> ServerRuntimeStatus
    ) {
        synchronized(lock) {
            val current = _runtimes.value[instanceId]
                ?: ServerRuntimeStatus(
                    instanceId = instanceId,
                    instanceName = instances.get(instanceId)?.name
                )
            val next = transform(current)
            _runtimes.value = _runtimes.value.toMutableMap().apply { put(instanceId, next) }
            if (_focusedId.value == instanceId) _runtime.value = next
        }
    }

    /** Replaces one instance's full runtime status and mirrors it when focused. */
    private fun setRuntime(status: ServerRuntimeStatus) {
        val id = status.instanceId
        synchronized(lock) {
            if (id != null) {
                _runtimes.value = _runtimes.value.toMutableMap().apply { put(id, status) }
            }
            if (id == null || _focusedId.value == id) _runtime.value = status
        }
    }

    private fun isCurrentSession(session: Session): Boolean =
        synchronized(lock) { sessions[session.instanceId] === session }

    // =====================================================================
    // Console engine (one buffer per instance, only the focused one published)
    // =====================================================================

    /**
     * Appends one line to [instanceId]'s console buffer. When that instance is
     * focused the live stream and the published snapshot follow immediately;
     * otherwise the line waits in the buffer until the user focuses it.
     */
    private fun emitConsole(instanceId: String, level: LogLevel, text: String) {
        if (text.isEmpty()) return
        val id = instanceId.ifBlank { _focusedId.value ?: "" }
        if (id.isEmpty()) return
        val entry = ConsoleEntry(
            seq = seq.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            text = text
        )
        synchronized(consoleLock) {
            val buffer = consoleBuffers.getOrPut(id) { ArrayDeque() }
            buffer.addLast(entry)
            while (buffer.size > consoleLimit) buffer.removeFirst()
            if (_focusedId.value == id) {
                consoleDirty.set(true)
                _consoleLive.tryEmit(entry)
            }
        }
    }

    private fun publishConsoleIfDirty() {
        if (!consoleDirty.get()) return
        val focusedId = _focusedId.value
        val snapshot: List<ConsoleEntry> = synchronized(consoleLock) {
            consoleDirty.set(false)
            focusedId?.let { consoleBuffers[it]?.toList() } ?: emptyList()
        }
        _consoleEntries.value = snapshot
    }

    /** Publishes the focused instance's console buffer immediately. */
    private fun publishFocusedConsole() {
        val focusedId = _focusedId.value
        val snapshot: List<ConsoleEntry> = synchronized(consoleLock) {
            consoleDirty.set(false)
            focusedId?.let { consoleBuffers[it]?.toList() } ?: emptyList()
        }
        _consoleEntries.value = snapshot
    }

    /** Clears [instanceId]'s console buffer and applies the current limit to all buffers. */
    private fun prepareConsoleForStart(instanceId: String) {
        val limit = settings.current().consoleLogLimit.coerceIn(MIN_CONSOLE_LIMIT, MAX_CONSOLE_LIMIT)
        consoleLimit = limit
        synchronized(consoleLock) {
            consoleBuffers[instanceId] = ArrayDeque()
            for (buffer in consoleBuffers.values) {
                while (buffer.size > limit) buffer.removeFirst()
            }
        }
        if (_focusedId.value == instanceId) {
            consoleDirty.set(false)
            _consoleEntries.value = emptyList()
        }
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
            if (session.serverPort != port || session.resolvedPort != port) {
                session.serverPort = port
                session.resolvedPort = port
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
                session.instanceId,
                LogLevel.WARN,
                "The server is asking for the Minecraft EULA. Accept it in the server settings to start the server."
            )
        }
        ConsoleParser.crashSignal(stripped)?.let { signal ->
            session.observedSignal = signal
        }
        emitPluginCompatibilityHints(session, stripped)
        emitConsole(session.instanceId, ConsoleParser.classify(stripped), display)
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
                session.instanceId,
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
                session.instanceId,
                LogLevel.WARN,
                "Geyser's NetherNet/WebRTC transport cannot load its native library on Android/bionic. " +
                    "This is a plugin-native limitation, not a JavaHost bug: Bedrock clients may still " +
                    "connect over Geyser's RakNet/UDP path depending on their version; Java clients " +
                    "should use the Java port instead."
            )
        }
    }

    private fun markReady(session: Session) {
        if (session.ready || session.finalized.get()) return
        if (!isCurrentSession(session)) return
        session.ready = true
        // The server is actually listening now: start the Minecraft LAN
        // discovery announcement and run the reachability self-test.
        scope.launch {
            runCatching { startLanAnnouncement(session) }
            delay(LAN_READY_SETTLE_MS)
            runCatching { refreshLan(session, probe = true, forceReport = true) }
        }
        updateRuntime(session.instanceId) { status ->
            if (!isActiveState(status.state)) return@updateRuntime status
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
        if (!isCurrentSession(session)) return
        updateRuntime(session.instanceId) { status ->
            if (!isActiveState(status.state)) return@updateRuntime status
            status.copy(
                playersOnline = session.playersOnline,
                playersMax = session.playersMax,
                ready = session.ready,
                uptimeMs = (System.currentTimeMillis() - session.startedAt).coerceAtLeast(0L)
            )
        }
    }

    private fun publishStopping(session: Session) {
        updateRuntime(session.instanceId) { status ->
            if (!isActiveState(status.state)) return@updateRuntime status
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
                    session.instanceId,
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
            if (!isCurrentSession(session)) break
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
            updateRuntime(session.instanceId) { status ->
                if (!isActiveState(status.state)) return@updateRuntime status
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
            if (session.isMinecraft && session.bedrockPlan.requested) {
                val ownsBridge = synchronized(lock) { bedrockOwnerId == session.instanceId }
                if (ownsBridge) {
                    val started = session.bedrockRelayStarted
                    if (!started &&
                        (session.ready || now - session.startedAt >= STARTING_FALLBACK_RUNNING_MS) &&
                        now - session.bedrockLastAttemptAt >= BEDROCK_START_RETRY_MS
                    ) {
                        session.bedrockLastAttemptAt = now
                        runCatching { startBedrockBridge(session) }
                    } else if (started &&
                        now - session.bedrockLastRefreshAt >= BEDROCK_STATUS_INTERVAL_MS
                    ) {
                        session.bedrockLastRefreshAt = now
                        runCatching { refreshBedrock(session) }
                    }
                }
            }
            delay(STATS_INTERVAL_MS)
        }
    }

    /**
     * Warns when the persisted flavor (possibly a manual override) disagrees
     * with what the JAR actually looks like. This prevents the classic
     * "plugins don't load" trap when a Vanilla JAR was mislabeled as Paper.
     */
    private fun emitJarTypeWarning(instance: ServerInstance, jarFile: File) {
        val instanceId = instance.id
        if (!instance.isMinecraft) return
        val inspection = (runCatching { JarInspector.inspect(jarFile) }.getOrNull() as? InspectionResult.Success)
            ?.inspection ?: return
        if (inspection.kind != instance.kind) {
            emitConsole(
                instanceId,
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
                instanceId,
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
        val instanceId = instance.id
        val plugins = paths.pluginsDir(instance.id)
            .listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            .orEmpty()
        val mods = paths.modsDir(instance.id)
            .listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            .orEmpty()
        emitConsole(
            instanceId,
            LogLevel.INFO,
            "Server type: ${instance.flavor.name.lowercase().replace('_', ' ')} · " +
                "Minecraft ${instance.minecraftVersion ?: "version unknown"} · " +
                "${plugins.size} plugin JAR(s) in plugins/, ${mods.size} mod JAR(s) in mods/"
        )
        when (instance.flavor) {
            ServerFlavor.VANILLA -> {
                if (plugins.isNotEmpty() || mods.isNotEmpty()) {
                    emitConsole(
                        instanceId,
                        LogLevel.WARN,
                        "This is a Vanilla server: it does NOT load plugins or mods. " +
                            "Geyser, Floodgate and ViaVersion need a Paper/Spigot server jar " +
                            "(plugins) or Fabric/Forge (mods). Replace the server JAR to use them."
                    )
                }
            }
            ServerFlavor.PAPER, ServerFlavor.BUKKIT, ServerFlavor.OTHER_MINECRAFT -> {
                if (plugins.isEmpty()) {
                    emitConsole(
                        instanceId,
                        LogLevel.WARN,
                        "No plugin JARs found in plugins/ — plugins installed in the app appear there and load on the next start."
                    )
                } else {
                    emitConsole(
                        instanceId,
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
                            instanceId,
                            LogLevel.WARN,
                            "Geyser is installed but Floodgate is not. Bedrock authentication may fail. " +
                                "Floodgate for Paper is distributed by GeyserMC (geysermc.org), not Modrinth; " +
                                "download it and import the JAR into plugins/ via Files."
                        )
                    }
                    if (hasViaVersion && !hasViaBackwards) {
                        emitConsole(
                            instanceId,
                            LogLevel.INFO,
                            "ViaVersion lets NEWER clients join; for OLDER clients also install ViaBackwards " +
                                "(and ViaRewind for 1.7/1.8) unless every client matches the server version."
                        )
                    }
                }
                if (mods.isNotEmpty()) {
                    emitConsole(
                        instanceId,
                        LogLevel.WARN,
                        "mods/ is not used by Paper/Spigot; use plugins/ for Bukkit plugins."
                    )
                }
            }
            ServerFlavor.FABRIC, ServerFlavor.FORGE -> {
                if (mods.isEmpty()) {
                    emitConsole(
                        instanceId,
                        LogLevel.WARN,
                        "No mod JARs found in mods/ — Fabric/Forge mods load from mods/, not plugins/."
                    )
                }
                if (plugins.isNotEmpty()) {
                    emitConsole(
                        instanceId,
                        LogLevel.WARN,
                        "plugins/ is ignored by Fabric/Forge; put mods in mods/ instead."
                    )
                }
            }
            ServerFlavor.GENERIC -> Unit
        }
    }

    // =====================================================================
    // LAN access / Minecraft discovery (per session)
    // =====================================================================

    /**
     * Prints the LAN addresses clients can use *before* the server is spawned
     * and explains connectivity problems (no Wi-Fi, VPN, mobile data).
     *
     * Only Wi-Fi / ethernet / hotspot interfaces with a private IPv4 address
     * are used: cellular (`ccmni*`, `rmnet*`) and VPN (`tun*`) addresses are
     * not reachable from the user's LAN, which was the original bug.
     */
    private fun emitLanOverview(
        instance: ServerInstance,
        bedrockPlan: BedrockBridgePlan,
        bridgeBusyOwner: String?
    ) {
        val instanceId = instance.id
        val endpoints = runCatching { lanInfo.endpoints() }.getOrDefault(emptyList())
        val network = runCatching { lanInfo.networkState() }.getOrNull()
        val port = resolveLanPort(instance, null)
        if (endpoints.isEmpty()) {
            emitConsole(
                instanceId,
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
                    instanceId,
                    LogLevel.INFO,
                    "LAN address: $shown (${endpoint.label}, ${endpoint.interfaceName})"
                )
            }
            if (endpoints.size > 1) {
                emitConsole(
                    instanceId,
                    LogLevel.INFO,
                    "Multiple LAN interfaces found. Use the address of the network your client " +
                        "device is connected to; all of them are listed in the server's LAN card."
                )
            }
        }
        if (network?.vpnActive == true) {
            emitConsole(
                instanceId,
                LogLevel.WARN,
                "A VPN is active. VPNs often block incoming LAN connections; turn the VPN off " +
                    "while hosting and try again."
            )
        }
        if (!instance.isMinecraft) return

        emitConsole(
            instanceId,
            LogLevel.INFO,
            "Minecraft dedicated servers do not appear in the LAN list by themselves. JavaHost " +
                "announces this server over multicast once it is ready (Settings -> Minecraft LAN " +
                "discovery announcement). If it still is not listed, use Add Server manually."
        )
        if (port != null && endpoints.isNotEmpty()) {
            emitConsole(
                instanceId,
                LogLevel.INFO,
                "Minecraft: add server ${endpoints.first().hostPort(port)} if it is not listed automatically."
            )
        }

        val firstAddress = endpoints.firstOrNull()?.address ?: "<phone-ip>"
        val bridgeFeatureOn = settings.current().bedrockLanDiscovery
        when {
            bedrockPlan.requested -> emitConsole(
                instanceId,
                LogLevel.INFO,
                "Bedrock Edition: JavaHost's Bedrock LAN bridge listens on UDP $BEDROCK_RELAY_PORT " +
                    "(Geyser is moved to UDP ${bedrockPlan.geyserInternalPort}). Once the server is ready, " +
                    "Bedrock clients on the same Wi-Fi/hotspot should see it in LAN/Friends. Manual " +
                    "fallback: Add Server $firstAddress:$BEDROCK_RELAY_PORT."
            )
            bedrockPlan.geyserDetected && bridgeFeatureOn && bridgeBusyOwner != null -> emitConsole(
                instanceId,
                LogLevel.INFO,
                "Bedrock Edition: the LAN bridge is used by $bridgeBusyOwner. This server's Geyser " +
                    "listens on UDP ${bedrockPlan.geyserInternalPort}; add " +
                    "$firstAddress:${bedrockPlan.geyserInternalPort} manually in Bedrock for this server."
            )
            bedrockPlan.geyserDetected -> emitConsole(
                instanceId,
                LogLevel.INFO,
                "Bedrock Edition: the Bedrock LAN bridge is disabled in Settings. Bedrock clients can " +
                    "add the server manually with $firstAddress:${bedrockPlan.geyserInternalPort} " +
                    "(LAN/Friends discovery may not list it)."
            )
            instance.flavor == ServerFlavor.PAPER || instance.flavor == ServerFlavor.BUKKIT -> emitConsole(
                instanceId,
                LogLevel.INFO,
                "Bedrock Edition: install Geyser + Floodgate in plugins/ to let Bedrock clients join " +
                    "this Paper/Spigot server. JavaHost then bridges Bedrock LAN discovery on UDP " +
                    "$BEDROCK_RELAY_PORT."
            )
        }
    }

    /**
     * Recomputes [LanStatus] for one session (addresses, port, phone
     * connectivity, optional TCP self-test) and publishes it to that instance's
     * runtime so the UI's LAN card stays current. Never throws.
     */
    private suspend fun refreshLan(session: Session, probe: Boolean, forceReport: Boolean = false) {
        if (session.finalized.get()) return
        if (!isCurrentSession(session)) return
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
            summary = buildLanSummary(instance, endpoints, port, announcementEnabled, session.ready),
            bedrock = currentBedrockStatus(session)
        )
        session.lanRefreshAt = System.currentTimeMillis()
        if (!isCurrentSession(session)) return
        updateRuntime(session.instanceId) { status ->
            if (isActiveState(status.state)) status.copy(lan = lan) else status
        }
        if (forceReport || !session.lanReported) {
            session.lanReported = true
            emitLanRefreshReport(session.instanceId, lan)
        } else if (probes.isNotEmpty()) {
            for (info in probes) emitProbeConsole(session.instanceId, info)
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
     * Applies the live discovery setting and interface changes to one running
     * server: toggling "Minecraft LAN discovery announcement" takes effect
     * immediately, and a Wi-Fi/hotspot change re-binds that server's announcer
     * to the new interface (a datagram sent on the wrong interface advertises
     * the wrong IP).
     */
    private fun reconcileLanAnnouncement(session: Session) {
        if (!session.isMinecraft || !session.ready) return
        if (!isCurrentSession(session)) return
        if (!settings.current().minecraftLanDiscovery) {
            if (session.announcerRunning) {
                stopLanAnnouncement(session)
                emitConsole(
                    session.instanceId,
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

    private fun emitLanRefreshReport(instanceId: String, lan: LanStatus) {
        lan.warning?.let { emitConsole(instanceId, LogLevel.WARN, "LAN: $it") }
        lan.summary?.let { emitConsole(instanceId, LogLevel.INFO, it) }
        for (info in lan.probes) emitProbeConsole(instanceId, info)
    }

    private fun emitProbeConsole(instanceId: String, info: LanProbeInfo) {
        val prefix = "LAN self-test ${info.address}:${info.port}"
        when {
            info.ok -> emitConsole(
                instanceId,
                LogLevel.INFO,
                "$prefix reachable" +
                    (info.latencyMs?.let { " ($it ms)" } ?: "") +
                    " - the server accepts connections on this address. (The test runs from the " +
                    "phone itself; if another device still fails, check router/client isolation " +
                    "or its firewall.)"
            )
            info.result.equals("refused", true) || info.result.equals("timeout", true) -> emitConsole(
                instanceId,
                LogLevel.WARN,
                "$prefix could not connect (${info.result}). The server may be listening on " +
                    "127.0.0.1 only, or a firewall / VPN / router isolation is blocking it. " +
                    "Clear server-ip in server.properties and see the troubleshooting guide."
            )
            else -> emitConsole(
                instanceId,
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
        if (session.finalized.get() || !isCurrentSession(session)) return
        session.lastAnnounceAttemptAt = System.currentTimeMillis()
        if (!settings.current().minecraftLanDiscovery) {
            emitConsole(
                session.instanceId,
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
        val announcer = session.lanAnnouncer
            ?: MinecraftLanAnnouncer(lanInfo).also { session.lanAnnouncer = it }
        session.announcedInterface =
            runCatching { lanInfo.preferredNetworkInterface()?.name }.getOrNull()
        val started = runCatching {
            announcer.start(MinecraftLanAnnouncer.Announcement(motd, port))
        }.getOrDefault(false)
        // Re-check after the slow disk/interface work above: a stop/finish that
        // landed in that window already called stopLanAnnouncement(), so this
        // late start would otherwise become a ghost announcement for a dead run.
        if (started && (session.finalized.get() || !isCurrentSession(session))) {
            stopLanAnnouncement(session)
            return
        }
        session.announcerRunning = started
        if (started) {
            emitConsole(
                session.instanceId,
                LogLevel.INFO,
                "Minecraft LAN discovery: announcing \"$motd\" on UDP 224.0.2.60:4445 " +
                    "(port $port). The server should appear under \"Scanning for games on your " +
                    "local network\" in Minecraft."
            )
        } else {
            emitConsole(
                session.instanceId,
                LogLevel.WARN,
                "Could not start the Minecraft LAN discovery announcement. Clients can still " +
                    "connect by adding the LAN address above manually."
            )
        }
    }

    private fun stopLanAnnouncement(session: Session) {
        session.announcerRunning = false
        val announcer = session.lanAnnouncer
        session.lanAnnouncer = null
        runCatching { announcer?.stop() }
    }

    /**
     * A stale `server-ip=127.0.0.1` (or an address of the phone only) is the
     * classic "works on localhost, unreachable on the LAN" trap. Clear loopback
     * binds before the JVM starts and tell the user.
     */
    private fun normalizeMinecraftBindAddress(instance: ServerInstance) {
        val instanceId = instance.id
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
                    instanceId,
                    LogLevel.WARN,
                    "server-ip was \"${current ?: ""}\", which only accepts connections on this " +
                        "phone. Cleared it before launch so other devices can connect (server.properties)."
                )
            }
            .onFailure {
                emitConsole(
                    instanceId,
                    LogLevel.WARN,
                    "server-ip is set to a phone-only address ($current), so other devices cannot " +
                        "connect. Remove it from server.properties and restart."
                )
            }
    }

    // =====================================================================
    // Bedrock Edition bridge (single owner on 19132 -> Geyser on 19133)
    // =====================================================================

    /** Finds Geyser in `plugins/`/`mods/` and its generated config, if any. */
    private fun detectGeyser(instance: ServerInstance): GeyserDetection {
        if (!instance.isMinecraft) return GeyserDetection(null, false)
        val pluginsDir = paths.pluginsDir(instance.id)
        val config = runCatching { BedrockGeyserConfig.findConfigFile(pluginsDir) }.getOrNull()
        val jar = findPluginJar(pluginsDir, "geyser")
            ?: findPluginJar(paths.modsDir(instance.id), "geyser")
        return GeyserDetection(config, config != null || jar != null)
    }

    /**
     * Picks the unique internal Geyser ports for a Minecraft+Geyser session that
     * does NOT own the Bedrock LAN bridge. Slots 0..4 are checked against the
     * other active sessions so two Geysers never share a UDP port.
     */
    private fun pickBedrockNonOwnerPorts(): BedrockPorts {
        val used = HashSet<Int>()
        for (session in sessions.values) {
            used.add(session.bedrockPlan.geyserInternalPort)
            used.add(session.bedrockPlan.webrtcPort)
            used.add(BEDROCK_GEYSER_PORT)
            used.add(BEDROCK_GEYSER_WEBRTC_PORT)
        }
        for (slot in 0 until ServerController.MAX_RUNNING_SERVERS) {
            val raknet = BEDROCK_NON_OWNER_PORT_BASE + slot
            val webrtc = BEDROCK_NON_OWNER_WEBRTC_BASE + slot
            if (raknet !in used && webrtc !in used) return BedrockPorts(raknet, webrtc)
        }
        val overflow = ServerController.MAX_RUNNING_SERVERS
        return BedrockPorts(
            BEDROCK_NON_OWNER_PORT_BASE + overflow,
            BEDROCK_NON_OWNER_WEBRTC_BASE + overflow
        )
    }

    /**
     * Decides how Geyser is configured before [instance] is launched:
     *
     *  - no Geyser or the feature is disabled: restore a previously patched port
     *    (recorded in a sidecar file) and launch untouched;
     *  - this session owns the LAN bridge: move Geyser to [BEDROCK_GEYSER_PORT]
     *    and pass the environment overrides the bridge expects;
     *  - another session owns the bridge: move Geyser to a unique internal port
     *    so two Geyser listeners cannot collide, and warn the user.
     *
     * The config edit is byte-preserving and atomic; the environment overrides
     * work even for Geyser builds that never read the config value. Never throws:
     * a failed patch only produces a console warning.
     */
    private fun prepareBedrockBridge(
        instance: ServerInstance,
        bridgeEnabled: Boolean,
        geyserPort: Int,
        webrtcPort: Int,
        geyserDetected: Boolean,
        geyserConfig: File?,
        busyOwnerName: String?
    ): BedrockBridgePlan {
        val instanceId = instance.id
        if (!instance.isMinecraft) {
            return BedrockBridgePlan(
                requested = false,
                geyserDetected = false,
                geyserConfig = null,
                environment = emptyMap(),
                geyserInternalPort = BEDROCK_GEYSER_PORT,
                webrtcPort = BEDROCK_GEYSER_WEBRTC_PORT
            )
        }
        val sidecar = bedrockSidecarFile(instance)
        val featureOn = settings.current().bedrockLanDiscovery
        val wanted = featureOn && geyserDetected
        if (!wanted) {
            restoreBedrockPort(geyserConfig, sidecar, instanceId)
            val restoredPort = geyserConfig?.let {
                runCatching { BedrockGeyserConfig.readBedrockPort(it) }.getOrNull()
            }
            return BedrockBridgePlan(
                requested = false,
                geyserDetected = geyserDetected,
                geyserConfig = geyserConfig,
                environment = emptyMap(),
                geyserInternalPort = restoredPort ?: BEDROCK_GEYSER_PORT,
                webrtcPort = BEDROCK_GEYSER_WEBRTC_PORT
            )
        }

        val targetPort = geyserPort
        val targetWebrtcPort = webrtcPort
        if (geyserConfig == null) {
            emitConsole(
                instanceId,
                LogLevel.INFO,
                "Bedrock: Geyser has not generated its config yet. JavaHost passes " +
                    "GEYSER_BEDROCK_PORT=$targetPort to Geyser; if Bedrock LAN discovery does not " +
                    "work, restart the server once more so JavaHost can also update " +
                    "plugins/Geyser-*/config.yml."
            )
        } else {
            val current = runCatching { BedrockGeyserConfig.readBedrockPort(geyserConfig) }.getOrNull()
            val outcome = runCatching {
                BedrockGeyserConfig.prepareForBridge(geyserConfig, targetPort, targetWebrtcPort)
            }.getOrNull()
            when {
                current == null -> emitConsole(
                    instanceId,
                    LogLevel.WARN,
                    "Bedrock: could not find bedrock.port in " +
                        "${BedrockGeyserConfig.describe(geyserConfig)}. JavaHost passes " +
                        "GEYSER_BEDROCK_PORT=$targetPort (Geyser 2.12+); if Bedrock clients cannot " +
                        "join, set bedrock.port: $targetPort manually and restart."
                )
                outcome == null -> emitConsole(
                    instanceId,
                    LogLevel.WARN,
                    "Bedrock: could not update Geyser's UDP port/transport in " +
                        "${BedrockGeyserConfig.describe(geyserConfig)}. JavaHost passes " +
                        "GEYSER_BEDROCK_PORT=$targetPort, GEYSER_BEDROCK_TRANSPORT=$BEDROCK_GEYSER_TRANSPORT " +
                        "and GEYSER_BEDROCK_WEBRTC_PORT=$targetWebrtcPort; with Geyser older than 2.12 " +
                        "set bedrock.port: $targetPort manually and restart."
                )
                outcome.changed -> {
                    outcome.originalPort?.let { writeSidecarPort(sidecar, it) }
                    val transportNote = if (outcome.transportPatched) {
                        " and set transport to \"$BEDROCK_GEYSER_TRANSPORT\" (Geyser 2.12.1 defaults to " +
                            "NetherNet, which disables the RakNet listener the bridge needs)"
                    } else {
                        ""
                    }
                    val webrtcNote = if (outcome.webrtcPortPatched) {
                        " (NetherNet WebRTC UDP moved to $targetWebrtcPort)"
                    } else {
                        ""
                    }
                    emitConsole(
                        instanceId,
                        LogLevel.INFO,
                        "Bedrock: moved Geyser from UDP $current to UDP $targetPort$transportNote" +
                            "$webrtcNote in ${BedrockGeyserConfig.describe(geyserConfig)}. " +
                            "The original port is saved so disabling the bridge restores it."
                    )
                }
                outcome.transportPatched || outcome.webrtcPortPatched -> emitConsole(
                    instanceId,
                    LogLevel.INFO,
                    "Bedrock: updated ${BedrockGeyserConfig.describe(geyserConfig)} to transport " +
                        "$BEDROCK_GEYSER_TRANSPORT and/or webrtc-port $targetWebrtcPort so RakNet stays " +
                        "available for Bedrock LAN discovery."
                )
                else -> Unit
            }
        }
        emitConsole(
            instanceId,
            LogLevel.INFO,
            if (bridgeEnabled) {
                "Bedrock bridge: Bedrock clients connect to UDP $BEDROCK_RELAY_PORT; Geyser is " +
                    "reached internally on UDP $targetPort."
            } else {
                "Bedrock: the LAN bridge is used by $busyOwnerName; this server's Geyser listens on " +
                    "UDP $targetPort so both servers can run at the same time."
            }
        )
        return BedrockBridgePlan(
            requested = bridgeEnabled,
            geyserDetected = true,
            geyserConfig = geyserConfig,
            environment = mapOf(
                "GEYSER_BEDROCK_PORT" to targetPort.toString(),
                "GEYSER_BEDROCK_ADDRESS" to "0.0.0.0",
                "GEYSER_BEDROCK_TRANSPORT" to BEDROCK_GEYSER_TRANSPORT,
                "GEYSER_BEDROCK_WEBRTC_PORT" to targetWebrtcPort.toString()
            ),
            geyserInternalPort = targetPort,
            webrtcPort = targetWebrtcPort
        )
    }

    /** Initial per-session Bedrock status published before Geyser answers. */
    private fun initialBedrockStatus(
        plan: BedrockBridgePlan,
        busyOwnerName: String?
    ): BedrockLanStatus = when {
        plan.requested -> BedrockLanStatus(
            geyserDetected = true,
            bridgeRequested = true,
            relayPort = BEDROCK_RELAY_PORT
        )
        plan.geyserDetected && busyOwnerName != null -> BedrockLanStatus(
            geyserDetected = true,
            bridgeRequested = false,
            warning = "Bedrock LAN bridge is in use by $busyOwnerName; this server's Geyser uses " +
                "UDP ${plan.geyserInternalPort} — add it manually in Bedrock for that server."
        )
        plan.geyserDetected -> BedrockLanStatus(geyserDetected = true, bridgeRequested = false)
        else -> BedrockLanStatus()
    }

    /** First JAR in [dir] whose file name contains [needle] (case-insensitive). */
    private fun findPluginJar(dir: File, needle: String): File? = runCatching {
        dir.listFiles { file ->
            file.isFile && file.extension.equals("jar", true) &&
                file.name.lowercase(Locale.US).contains(needle)
        }?.firstOrNull()
    }.getOrNull()

    private fun bedrockSidecarFile(instance: ServerInstance): File =
        File(paths.workingDir(instance.id), BEDROCK_SIDECAR_NAME)

    private fun readSidecarPort(file: File): Int? =
        runCatching { file.readText().trim().toIntOrNull() }.getOrNull()

    private fun writeSidecarPort(file: File, port: Int) {
        runCatching { file.writeText("$port\n") }
    }

    /**
     * Restores a Geyser port this app previously moved, when the bridge is now
     * off. Only restores when the file still holds the port we patched so a
     * user's later manual change is never overwritten.
     */
    private fun restoreBedrockPort(geyserConfig: File?, sidecar: File, instanceId: String) {
        if (!sidecar.isFile) return
        val original = readSidecarPort(sidecar)
        if (original != null && geyserConfig != null) {
            val current = runCatching { BedrockGeyserConfig.readBedrockPort(geyserConfig) }.getOrNull()
            if (current != null) {
                val restored = runCatching {
                    BedrockGeyserConfig.restorePort(geyserConfig, current, original)
                }.getOrDefault(false)
                if (restored) {
                    emitConsole(
                        instanceId,
                        LogLevel.INFO,
                        "Bedrock bridge is off: restored Geyser's UDP port to $original in " +
                            "${BedrockGeyserConfig.describe(geyserConfig)}."
                    )
                }
            }
        }
        runCatching { sidecar.delete() }
    }

    /**
     * Binds UDP 19132 and starts relaying to the owner session's Geyser port.
     * Called from the stats loop once the server is ready (so Geyser has already
     * bound its port, which avoids a bind race on the very first run).
     */
    private fun startBedrockBridge(session: Session) {
        if (!session.bedrockPlan.requested || session.bedrockRelayStarted) return
        if (session.finalized.get() || !isCurrentSession(session)) return
        if (synchronized(lock) { bedrockOwnerId } != session.instanceId) return
        session.bedrockLastAttemptAt = System.currentTimeMillis()
        val geyserPort = session.bedrockPlan.geyserInternalPort
        val relay = BedrockLanRelay(
            listenPort = BEDROCK_RELAY_PORT,
            upstreamHost = BEDROCK_UPSTREAM_HOST,
            upstreamPort = geyserPort,
            // Reply-only (Phantom-style): unsolicited pong broadcasts are untested
            // against real clients and can confuse the LAN list.
            beaconIntervalMs = 0L,
            broadcastProvider = { bedrockBroadcastTargets() },
            onError = { message ->
                emitConsole(session.instanceId, LogLevel.DEBUG, "Bedrock bridge: $message")
            }
        )
        val started = runCatching { relay.start() }.getOrDefault(false)
        if (!started) {
            val address = bedrockEndpoints().firstOrNull()?.hostPort
                ?: "<phone-ip>:$BEDROCK_RELAY_PORT"
            val warning = "The Bedrock LAN bridge could not bind UDP $BEDROCK_RELAY_PORT. Another app " +
                "may already use that port, or Geyser is still listening on $BEDROCK_RELAY_PORT " +
                "(JavaHost could not move it to $geyserPort). Bedrock clients can still join " +
                "manually at $address if Geyser is reachable."
            session.bedrockStatus = session.bedrockStatus.copy(
                relayRunning = false,
                geyserReachable = false,
                endpoints = bedrockEndpoints(),
                directEndpoints = bedrockDirectEndpoints(geyserPort),
                warning = warning
            )
            // One warning per session: a 2 s retry loop must not flood the console.
            if (!session.bedrockBindWarningEmitted) {
                session.bedrockBindWarningEmitted = true
                emitConsole(session.instanceId, LogLevel.WARN, "Bedrock bridge: $warning")
            }
            publishBedrockStatus(session)
            return
        }
        // A late successful bind must not become a ghost relay if the session was
        // stopped/finished while start() was running (mirrors the LAN announcer fix).
        if (session.finalized.get() || !isCurrentSession(session)) {
            runCatching { relay.stop() }
            return
        }
        session.bedrockRelay = relay
        session.bedrockRelayStarted = true
        session.bedrockBindWarningEmitted = false
        session.bedrockStartedAt = System.currentTimeMillis()
        session.bedrockStatus = session.bedrockStatus.copy(
            relayRunning = true,
            relayPort = BEDROCK_RELAY_PORT,
            warning = null
        )
        emitConsole(
            session.instanceId,
            LogLevel.INFO,
            "Bedrock LAN bridge: listening on UDP $BEDROCK_RELAY_PORT and forwarding to Geyser on " +
                "UDP $geyserPort. Waiting for Geyser to answer the Bedrock ping…"
        )
        publishBedrockStatus(session)
    }

    private fun stopBedrockBridge(session: Session) {
        session.bedrockRelayStarted = false
        session.bedrockStatus = session.bedrockStatus.copy(
            relayRunning = false,
            geyserReachable = false,
            endpoints = emptyList()
        )
        val relay = session.bedrockRelay
        session.bedrockRelay = null
        runCatching { relay?.stop() }
    }

    private fun publishBedrockStatus(session: Session) {
        if (!isCurrentSession(session)) return
        updateRuntime(session.instanceId) { status ->
            if (isActiveState(status.state)) {
                status.copy(lan = status.lan.copy(bedrock = currentBedrockStatus(session)))
            } else {
                status
            }
        }
    }

    /**
     * Pings the owner session's Geyser directly to get live Bedrock server info
     * (MOTD, version, players) and to know whether the bridge can work at all.
     */
    private suspend fun refreshBedrock(session: Session) {
        if (!session.bedrockPlan.requested || !session.bedrockRelayStarted) return
        if (session.finalized.get() || !isCurrentSession(session)) return
        val geyserPort = session.bedrockPlan.geyserInternalPort
        val info = withContext(Dispatchers.IO) { queryBedrockServer(geyserPort) }
        val now = System.currentTimeMillis()
        // Keep the bridge address visible even while Geyser is unreachable: it is
        // the documented manual fallback.
        val endpoints = bedrockEndpoints()
        if (info != null) {
            session.bedrockWarningEmitted = false
            if (!session.bedrockReadyEmitted) {
                session.bedrockReadyEmitted = true
                val address = endpoints.firstOrNull()?.hostPort ?: "<phone-ip>:$BEDROCK_RELAY_PORT"
                val direct = bedrockDirectEndpoints(geyserPort).firstOrNull()?.hostPort
                emitConsole(
                    session.instanceId,
                    LogLevel.INFO,
                    "Bedrock LAN bridge ready: Bedrock clients on the same Wi-Fi should see the server " +
                        "in LAN/Friends within a few seconds. Manual Add Server for the LAN bridge: " +
                        "$address." + (direct?.let {
                            " Newer Bedrock versions that switched to NetherNet may need the Geyser " +
                                "direct address instead: Add Server $it."
                        } ?: "") + " Geyser ${info.version} · ${info.players}/${info.maxPlayers} " +
                        "players · MOTD \"${info.motd}\"."
                )
            }
        } else if (now - session.bedrockStartedAt >= BEDROCK_WARN_AFTER_MS && !session.bedrockWarningEmitted) {
            session.bedrockWarningEmitted = true
            emitConsole(
                session.instanceId,
                LogLevel.WARN,
                "Bedrock bridge: waiting on UDP $BEDROCK_RELAY_PORT but Geyser is not answering RakNet " +
                    "on UDP $geyserPort. Geyser 2.12.1 defaults to the NetherNet transport; " +
                    "JavaHost sets transport: $BEDROCK_GEYSER_TRANSPORT and webrtc-port " +
                    "${session.bedrockPlan.webrtcPort}, so check plugins/Geyser-Spigot/config.yml and " +
                    "the console for Geyser bind/config errors (also make sure Floodgate is " +
                    "installed). Newer Bedrock clients can still join directly with Add Server " +
                    "<phone-ip>:$geyserPort."
            )
        }
        session.bedrockStatus = BedrockLanStatus(
            geyserDetected = true,
            bridgeRequested = true,
            relayRunning = true,
            relayPort = BEDROCK_RELAY_PORT,
            geyserReachable = info != null,
            motd = info?.motd,
            version = info?.version,
            protocol = info?.protocol,
            players = info?.let { "${it.players}/${it.maxPlayers}" },
            endpoints = endpoints,
            directEndpoints = bedrockDirectEndpoints(geyserPort),
            // Bind failures keep their warning from startBedrockBridge(); the
            // "Geyser not answering" case is shown by the UI's status line and
            // logged to the console once, so do not duplicate it here.
            warning = null
        )
        publishBedrockStatus(session)
    }

    /** One RakNet ping round-trip to a local Geyser listener; null when it does not answer. */
    private fun queryBedrockServer(port: Int): BedrockRakNet.ServerInfo? {
        val socket = DatagramSocket()
        return try {
            socket.soTimeout = BEDROCK_QUERY_TIMEOUT_MS
            val ping = BedrockRakNet.buildPing(System.currentTimeMillis(), bedrockClientGuid)
            val target = InetAddress.getByName(BEDROCK_UPSTREAM_HOST)
            socket.send(DatagramPacket(ping, ping.size, target, port))
            val buffer = ByteArray(BEDROCK_PONG_BUFFER)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val pong = BedrockRakNet.parsePong(buffer.copyOf(packet.length)) ?: return null
            BedrockRakNet.parseServerId(pong.serverId)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun bedrockEndpoints(): List<LanEndpointInfo> {
        val endpoints = runCatching { lanInfo.endpoints() }.getOrDefault(emptyList())
        return endpoints.map { endpoint ->
            LanEndpointInfo(
                address = endpoint.address,
                interfaceName = endpoint.interfaceName,
                label = endpoint.label,
                hostPort = "${endpoint.address}:$BEDROCK_RELAY_PORT"
            )
        }
    }

    /**
     * Geyser's own address for Bedrock clients that use the NetherNet transport
     * (Bedrock >= 26.60) and therefore cannot join through the RakNet bridge:
     * use Add Server with `<phone-ip>:<geyserPort>`.
     */
    private fun bedrockDirectEndpoints(geyserPort: Int): List<LanEndpointInfo> {
        val endpoints = runCatching { lanInfo.endpoints() }.getOrDefault(emptyList())
        return endpoints.map { endpoint ->
            LanEndpointInfo(
                address = endpoint.address,
                interfaceName = endpoint.interfaceName,
                label = endpoint.label,
                hostPort = "${endpoint.address}:$geyserPort"
            )
        }
    }

    /** Broadcast targets for the Bedrock pong beacon: limited + per-interface subnet broadcasts. */
    private fun bedrockBroadcastTargets(): List<String> {
        val targets = linkedSetOf<String>()
        targets.add(BEDROCK_LIMITED_BROADCAST)
        runCatching {
            for (endpoint in lanInfo.endpoints()) {
                val broadcast = LanAddressSelector.subnetBroadcast(endpoint.address, endpoint.prefixLength)
                if (broadcast != null && broadcast != endpoint.address) targets.add(broadcast)
            }
        }
        return targets.toList()
    }

    private fun currentBedrockStatus(session: Session): BedrockLanStatus {
        val plan = session.bedrockPlan
        if (!plan.geyserDetected) return BedrockLanStatus(geyserDetected = false, bridgeRequested = false)
        return session.bedrockStatus.copy(
            geyserDetected = true,
            bridgeRequested = plan.requested,
            relayPort = if (plan.requested) BEDROCK_RELAY_PORT else null,
            directEndpoints = bedrockDirectEndpoints(plan.geyserInternalPort)
        )
    }

    // =====================================================================
    // Graceful stop
    // =====================================================================

    /**
     * Shared graceful/force shutdown for [stop] and [restart].
     *
     * Minecraft gets a "stop" command and up to 8 s; generic servers do not get
     * a fake command (most would not understand it), they simply get a short
     * 2 s grace before SIGTERM. SIGTERM is followed by SIGKILL after 3 s.
     * Requests are idempotent and a later `stop(force = true)` escalates.
     */
    private suspend fun stopSession(session: Session, force: Boolean) {
        if (!session.stopInitiated.compareAndSet(false, true)) {
            withTimeoutOrNull(GRACEFUL_STOP_MAX_MS + 10_000L) { session.finished.await() }
            return
        }
        session.stopping = true
        session.stopRequested = true
        if (force) session.forceRequested = true
        stopLanAnnouncement(session)
        stopBedrockBridge(session)

        val alive = isProcessAlive(session.process)
        if (alive) {
            if (force) {
                emitConsole(session.instanceId, LogLevel.INFO, "Force stopping the server…")
                runCatching { session.process.destroy() }
            } else {
                if (session.isMinecraft) {
                    if (writeStdin(session, "stop")) {
                        emitConsole(
                            session.instanceId,
                            LogLevel.INFO,
                            "Sent stop command; waiting for the server to shut down…"
                        )
                    } else {
                        emitConsole(
                            session.instanceId,
                            LogLevel.WARN,
                            "Could not send the stop command; terminating the process."
                        )
                    }
                } else {
                    emitConsole(session.instanceId, LogLevel.INFO, "Stopping the server…")
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
                    emitConsole(
                        session.instanceId,
                        LogLevel.WARN,
                        "The server did not stop in time; terminating the process."
                    )
                }
                runCatching { session.process.destroy() }
                val deadline = System.currentTimeMillis() + KILL_GRACE_MS
                while (System.currentTimeMillis() < deadline && !session.exitDeferred.isCompleted) {
                    delay(WAIT_POLL_MS)
                }
                if (!session.exitDeferred.isCompleted) {
                    emitConsole(
                        session.instanceId,
                        LogLevel.WARN,
                        "The process ignored termination; killing it."
                    )
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
            emitConsole(
                session.instanceId,
                LogLevel.WARN,
                "The server process did not confirm exit; forcing it down."
            )
            forceKill(session)
            withTimeoutOrNull(KILL_GRACE_MS) { session.finished.await() }
        }
        if (!session.finished.isCompleted) {
            emitConsole(
                session.instanceId,
                LogLevel.ERROR,
                "Could not reap the server process; resetting its session state."
            )
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
    // Exit handling / crash post-mortem (per session)
    // =====================================================================

    private fun finishSession(session: Session, exitCode: Int) {
        if (!session.finalized.compareAndSet(false, true)) return
        stopLanAnnouncement(session)
        stopBedrockBridge(session)
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
        if (!expected) {
            val reportPath = runCatching { writeCrashReport(session, exitInfo, duration) }
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
            emitConsole(
                session.instanceId,
                LogLevel.INFO,
                "Server stopped (exit code $exitCode)."
            )
        } else {
            emitConsole(
                session.instanceId,
                LogLevel.ERROR,
                "Server exited unexpectedly: ${classified.summary} (exit code $exitCode)"
            )
            exitInfo.reportPath?.let {
                emitConsole(session.instanceId, LogLevel.ERROR, "Crash report saved to $it")
            }
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
        var nextFocus: String? = null
        var wasFocused = false
        synchronized(lock) {
            val currentInMap = sessions[session.instanceId]
            if (currentInMap === session) sessions.remove(session.instanceId)
            if (bedrockOwnerId == session.instanceId) bedrockOwnerId = null
            // If a replacement session for the same instance already started,
            // it owns the runtime entry and must not be overwritten.
            val replacementActive = currentInMap != null && currentInMap !== session
            if (!replacementActive) {
                _runtimes.value = _runtimes.value.toMutableMap().apply {
                    put(session.instanceId, finalStatus)
                }
                if (_focusedId.value == session.instanceId) {
                    wasFocused = true
                    _runtime.value = finalStatus
                    nextFocus = sessions.keys.firstOrNull()
                }
            }
        }
        session.finished.complete(Unit)
        if (wasFocused) {
            if (nextFocus != null) {
                focus(nextFocus)
            } else {
                publishFocusedConsole()
            }
        }
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
    // Port-conflict helpers
    // =====================================================================

    /**
     * Minecraft: `server.properties server-port` -> `instance.serverPort` ->
     * 25565. Generic servers: the instance port (may be null, in which case the
     * conflict cannot be pre-checked and the child's own bind error is reported).
     */
    private fun resolveConfiguredPort(instance: ServerInstance): Int? {
        if (instance.isMinecraft) {
            val propertyPort = runCatching {
                ServerPropertiesCodec.port(ServerPropertiesCodec.read(paths.serverProperties(instance.id)))
            }.getOrNull()
            return propertyPort ?: instance.serverPort ?: DEFAULT_MINECRAFT_PORT
        }
        return instance.serverPort
    }

    /**
     * Name of the instance owning [port]: a running session first, then any
     * instance whose configured port resolves to it. Caller must hold [lock].
     */
    private fun portOwnerLocked(port: Int, exceptInstanceId: String?): String? {
        sessions.values.firstOrNull { session ->
            session.instanceId != exceptInstanceId && session.resolvedPort == port
        }?.let { return it.instanceName }
        return instances.instances.value.firstOrNull { instance ->
            instance.id != exceptInstanceId && resolveConfiguredPort(instance) == port
        }?.name
    }

    override fun portOwner(port: Int, exceptInstanceId: String?): String? {
        if (port <= 0) return null
        synchronized(lock) { return portOwnerLocked(port, exceptInstanceId) }
    }

    private fun portConflictMessage(port: Int, ownerName: String): String =
        "Port $port is already used by $ownerName. Change server-port in server.properties " +
            "(Minecraft) or the instance port so both servers can run."

    // =====================================================================
    // Initial-state recovery / helpers
    // =====================================================================

    /**
     * Seeds the per-instance runtime map with the last active instance so the UI
     * can show a crash banner and the focused console immediately after a cold
     * start, without ever reporting a running server as active.
     */
    private fun recoverInitialRuntimes(): Map<String, ServerRuntimeStatus> {
        val (availMb, totalMb) = deviceMemoryMb()
        val lastId = settings.current().lastActiveInstanceId ?: return emptyMap()
        val instance = instances.get(lastId) ?: return emptyMap()
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
            return mapOf(lastId to base.copy(state = RunState.CRASHED, lastExit = exit))
        }
        return mapOf(lastId to base)
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
        @Volatile var resolvedPort: Int? = null
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
        @Volatile var lanAnnouncer: MinecraftLanAnnouncer? = null
        @Volatile var bedrockPlan: BedrockBridgePlan = BedrockBridgePlan(
            requested = false,
            geyserDetected = false,
            geyserConfig = null,
            environment = emptyMap(),
            geyserInternalPort = ServerManager.BEDROCK_GEYSER_PORT,
            webrtcPort = ServerManager.BEDROCK_GEYSER_WEBRTC_PORT
        )
        @Volatile var bedrockStatus: BedrockLanStatus = BedrockLanStatus()
        @Volatile var bedrockRelay: BedrockLanRelay? = null
        @Volatile var bedrockRelayStarted = false
        @Volatile var bedrockStartedAt = 0L
        @Volatile var bedrockLastRefreshAt = 0L
        @Volatile var bedrockLastAttemptAt = 0L
        @Volatile var bedrockBindWarningEmitted = false
        @Volatile var bedrockWarningEmitted = false
        @Volatile var bedrockReadyEmitted = false

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
        const val BEDROCK_RELAY_PORT = 19132
        const val BEDROCK_GEYSER_PORT = 19133
        const val BEDROCK_GEYSER_WEBRTC_PORT = 19134
        const val BEDROCK_GEYSER_TRANSPORT = "both"
        const val BEDROCK_UPSTREAM_HOST = "127.0.0.1"
        const val BEDROCK_LIMITED_BROADCAST = "255.255.255.255"
        const val BEDROCK_STATUS_INTERVAL_MS = 5_000L
        const val BEDROCK_START_RETRY_MS = 30_000L
        const val BEDROCK_WARN_AFTER_MS = 60_000L
        const val BEDROCK_QUERY_TIMEOUT_MS = 800
        const val BEDROCK_PONG_BUFFER = 2048
        const val BEDROCK_SIDECAR_NAME = ".javahost-geyser-bedrock-port"
        const val BEDROCK_NON_OWNER_PORT_BASE = 19140
        const val BEDROCK_NON_OWNER_WEBRTC_BASE = 19145
        const val DEFAULT_MINECRAFT_PORT = 25565
        const val LAN_READY_SETTLE_MS = 750L
        const val LAN_PROBE_TIMEOUT_MS = 2_500
        const val ANNOUNCE_RETRY_MS = 30_000L
        const val LOOPBACK_HOST = "127.0.0.1"
        const val GRACEFUL_STOP_MAX_MS = 8_000L
        const val GENERIC_STOP_GRACE_MS = 2_000L
        const val KILL_GRACE_MS = 2_000L
        const val WAIT_POLL_MS = 100L
    }
}
