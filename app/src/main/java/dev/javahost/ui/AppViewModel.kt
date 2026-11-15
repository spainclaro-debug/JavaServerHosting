package dev.javahost.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.javahost.BuildConfig
import dev.javahost.JavaHostApp
import dev.javahost.core.jar.JarInspector
import dev.javahost.core.model.AppSettings
import dev.javahost.core.model.ConsoleEntry
import dev.javahost.core.model.DeviceInfo
import dev.javahost.core.model.ExitInfo
import dev.javahost.core.model.FileEntry
import dev.javahost.core.model.GuideSection
import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.JobProgress
import dev.javahost.core.model.JreInstallation
import dev.javahost.core.model.ModTarget
import dev.javahost.core.model.ModrinthProject
import dev.javahost.core.model.ModrinthVersion
import dev.javahost.core.model.NewInstanceDraft
import dev.javahost.core.model.PendingJar
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.model.ServerKind
import dev.javahost.core.model.ServerRuntimeStatus
import dev.javahost.core.model.StartResult
import dev.javahost.core.model.UiMessage
import dev.javahost.core.net.BedrockGeyserConfig
import dev.javahost.core.service.ForegroundServerService
import dev.javahost.core.storage.SafePaths
import dev.javahost.core.storage.ServerPropertiesCodec
import dev.javahost.core.system.MemoryAdvisor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class AppViewModel(application: Application) : AndroidViewModel(application), AppActions {

    private val app = application as JavaHostApp
    private val container get() = app.container
    private val context: Context get() = getApplication()

    private val _state = MutableStateFlow(AppUiState(loading = true))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    val consoleEntries: StateFlow<List<ConsoleEntry>> get() = container.server.consoleEntries

    private val _navigation = MutableStateFlow<NavigationRequest?>(null)
    val navigationRequest: StateFlow<NavigationRequest?> = _navigation.asStateFlow()

    private val messageSeq = AtomicLong(0)
    private val jreInstallJobs = mutableMapOf<Int, Job>()
    private var jreFolderImportJob: Job? = null
    private var pendingJarSource: File? = null

    init {
        collectState()
        refreshDeviceInfo()
        refreshPowerChecks()
        _state.update { it.copy(loading = false, wizardComplete = container.settings.current().wizardComplete) }
    }

    private fun collectState() {
        viewModelScope.launch {
            container.settings.state.collect { settings ->
                _state.update {
                    it.copy(
                        settings = settings,
                        wizardComplete = settings.wizardComplete,
                        recommendedJreMajor = settings.defaultJreMajor
                    )
                }
            }
        }
        viewModelScope.launch {
            container.instances.instances.collect { instances ->
                val bedrockDetected = detectBedrockInstances(instances)
                _state.update { it.copy(instances = instances, bedrockDetected = bedrockDetected) }
            }
        }
        viewModelScope.launch {
            container.server.runtime.collect { runtime ->
                _state.update { previous ->
                    val unexpectedExit = runtime.lastExit?.takeIf { !it.expected }
                    previous.copy(
                        runtime = runtime,
                        activeInstanceId = runtime.instanceId,
                        // Never surface a user-requested stop as a crash. Clear
                        // the banner when a new run starts so it cannot go stale.
                        lastCrash = when {
                            unexpectedExit != null -> unexpectedExit
                            runtime.isActive -> null
                            else -> previous.lastCrash
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            container.jre.installations.collect { jres ->
                _state.update { it.copy(jres = jres) }
            }
        }
        viewModelScope.launch {
            container.jre.jobs.collect { jobs ->
                _state.update { it.copy(jreJobs = jobs) }
            }
        }
    }

    private fun refreshDeviceInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val info = container.memory.deviceInfo()
            _state.update {
                it.copy(
                    device = info,
                    recommendedJreMajor = it.settings.defaultJreMajor,
                    // If the user never picked a heap, follow the conservative auto value.
                    settings = it.settings
                )
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Messages / onboarding                                              */
    /* ------------------------------------------------------------------ */

    private fun showMessage(text: String, isError: Boolean = false) {
        _state.update { it.copy(message = UiMessage(messageSeq.incrementAndGet(), text, isError)) }
    }

    override fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun dismissCrash() {
        _state.update { it.copy(lastCrash = null) }
        // Also clear the persisted instance-level summary, otherwise the detail
        // screen's crash card reappears (and survives a cold start).
        _state.value.instances
            .filter { it.lastCrashSummary != null || it.lastExitAt != null }
            .forEach { instance ->
                container.instances.update(instance.id) {
                    it.copy(lastCrashSummary = null, lastExitCode = null, lastExitAt = null)
                }
            }
        container.server.clearLastCrash()
    }

    override fun completeWizard() {
        container.settings.update { it.copy(wizardComplete = true) }
        _state.update { it.copy(wizardComplete = true) }
    }

    override fun setPlanMinecraft(value: Boolean) {
        container.settings.update { it.copy(planMinecraft = value) }
    }

    override fun acknowledgeMinecraftEula() {
        container.settings.update { it.copy(minecraftEulaAcknowledged = true) }
    }

    override fun onNotificationPermissionResult(granted: Boolean) {
        container.settings.update { it.copy(notificationsRequested = true) }
        if (!granted) showMessage("Notifications are blocked. The server can still run, but you will not see its status.", true)
        refreshPowerChecks()
    }

    override fun refreshPowerChecks() {
        val settings = container.settings.current()
        runCatching {
            _state.update { it.copy(power = container.power.checks(settings)) }
        }
    }

    override fun openPowerAction(action: PowerAction): Boolean {
        val intent: Intent? = when (action) {
            PowerAction.REQUEST_BATTERY_EXEMPTION -> container.power.batteryExemptionIntent()
            PowerAction.OPEN_BATTERY_SETTINGS -> container.power.batterySettingsIntent()
            PowerAction.OPEN_NOTIFICATION_SETTINGS -> container.power.notificationSettingsIntent()
            PowerAction.OPEN_DEVELOPER_OPTIONS -> container.power.developerOptionsIntent()
            PowerAction.OPEN_APP_DETAILS -> container.power.appDetailsIntent()
        }
        return runCatching {
            if (intent == null) return false
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrElse {
            showMessage("Could not open that system screen on this device.", true)
            false
        }
    }

    /* ------------------------------------------------------------------ */
    /* JRE                                                                */
    /* ------------------------------------------------------------------ */

    override fun installJre(major: Int) {
        if (jreInstallJobs[major]?.isActive == true) return
        val job = viewModelScope.launch {
            val result = container.jre.install(major)
            result.onSuccess { installed ->
                showMessage("Java ${installed.major} (${installed.versionLabel}) installed.")
                refreshDeviceInfo()
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    showMessage("Java $major install failed: ${error.message}", true)
                }
            }
        }
        jreInstallJobs[major] = job
        job.invokeOnCompletion { jreInstallJobs.remove(major) }
    }

    override fun cancelJreJob(major: Int) {
        container.jre.cancelInstall(major)
        jreInstallJobs.remove(major)?.cancel()
        showMessage("Cancelled Java $major download.")
    }

    override fun importJreArchive(uri: Uri) {
        viewModelScope.launch {
            val displayName = queryDisplayName(uri) ?: "imported-jre"
            val result = runCatching {
                val cache = withContext(Dispatchers.IO) {
                    val dest = File(container.paths.downloadsDir, "import-${System.currentTimeMillis()}-${sanitizeFileName(displayName)}")
                    copyUriToFile(uri, dest)
                    dest
                }
                try {
                    container.jre.importArchive(cache, displayName)
                } finally {
                    cache.delete()
                }
            }.getOrElse { Result.failure(it) }
            result.onSuccess { showMessage("Java ${it.major} imported from archive.") }
                .onFailure { showMessage("JRE import failed: ${it.message}", true) }
        }
    }

    override fun importJreFolder(uri: Uri) {
        if (jreFolderImportJob?.isActive == true) {
            showMessage("A JRE folder import is already running.", true)
            return
        }
        val job = viewModelScope.launch {
            var temp: File? = null
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(context, uri)
                        ?: error("The selected folder is not accessible")
                    val home = locateJavaHomeDocument(root)
                        ?: error(
                            "No Java runtime (bin/java) was found in that folder. " +
                                "Pick the JRE home, or a parent folder that contains it."
                        )
                    // Cheap pre-flight before copying a possibly 300 MB tree: if
                    // bin/java is a glibc (Oracle/Adoptium Linux) build, reject
                    // now instead of copying it and failing later.
                    if (!documentLooksLikeAndroidBionic(home)) {
                        error(
                            "This Java runtime is not an Android/bionic build. Official Oracle or " +
                                "Adoptium Linux JDKs use glibc and cannot run on Android. Use a " +
                                "Termux/Pojav-style Android aarch64 runtime, or let JavaHost download one."
                        )
                    }
                    val displayName = home.name?.takeIf { it.isNotBlank() }
                        ?: root.name?.takeIf { it.isNotBlank() }
                        ?: "imported-jre"
                    val destination = File(
                        container.paths.downloadsDir,
                        "jre-folder-${System.currentTimeMillis()}"
                    )
                    temp = destination
                    copyDocumentTree(home, destination)
                    destination to displayName
                }
                temp = prepared.first
                val result = container.jre.importDirectory(prepared.first, prepared.second)
                result.onSuccess {
                    showMessage("Java ${it.major} (${it.versionLabel}) imported from folder.")
                    refreshDeviceInfo()
                }.onFailure {
                    showMessage(it.message ?: "Could not import that JRE folder.", true)
                }
            } catch (error: CancellationException) {
                // ViewModel scoped work was cancelled: cleanup happens below.
                throw error
            } catch (error: Exception) {
                showMessage("JRE folder import failed: ${error.message}", true)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    temp?.deleteRecursively()
                }
            }
        }
        jreFolderImportJob = job
        job.invokeOnCompletion {
            if (jreFolderImportJob === job) jreFolderImportJob = null
        }
    }

    /** Reads only the first 8 KiB of SAF bin/java, mirroring JreRuntimeValidation. */
    private fun documentLooksLikeAndroidBionic(home: DocumentFile): Boolean {
        val java = home.findFile("bin")?.findFile("java")?.takeIf { it.isFile } ?: return false
        return runCatching {
            context.contentResolver.openInputStream(java.uri)?.use { input ->
                val buffer = ByteArray(8192)
                val read = input.read(buffer)
                if (read <= 0) return@use false
                String(buffer, 0, read, Charsets.ISO_8859_1).contains("/system/bin/linker")
            } ?: false
        }.getOrDefault(false)
    }

    /** Finds the JRE home inside a SAF tree without copying unrelated folders. */
    private fun locateJavaHomeDocument(root: DocumentFile): DocumentFile? {
        fun isJavaHome(document: DocumentFile): Boolean =
            document.findFile("bin")?.findFile("java")?.isFile == true

        if (isJavaHome(root)) return root
        val queue = ArrayDeque<Pair<DocumentFile, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (directory, depth) = queue.removeFirst()
            if (depth >= MAX_JRE_SEARCH_DEPTH) continue
            for (child in directory.listFiles()) {
                if (!child.isDirectory) continue
                if (isJavaHome(child)) return child
                queue.add(child to depth + 1)
            }
        }
        return null
    }

    /**
     * Copies only the selected JRE home into [destination], with cancellation,
     * entry-count and byte-budget checks so a mistaken parent-folder selection
     * cannot fill the cache.
     */
    private suspend fun copyDocumentTree(
        source: DocumentFile,
        destination: File,
        depth: Int = 0,
        copiedBytes: LongArray = longArrayOf(0L),
        copiedFiles: IntArray = intArrayOf(0)
    ) {
        if (depth > MAX_JRE_SEARCH_DEPTH) error("The selected folder is nested too deeply")
        if (!destination.isDirectory && !destination.mkdirs()) {
            error("Cannot create a temporary folder for the import")
        }
        for (child in source.listFiles()) {
            currentCoroutineContext().ensureActive()
            val safeName = SafePaths.sanitizeFileName(child.name, fallback = "file")
            if (child.isDirectory) {
                copyDocumentTree(child, File(destination, safeName), depth + 1, copiedBytes, copiedFiles)
                continue
            }
            copiedFiles[0]++
            if (copiedFiles[0] > MAX_JRE_IMPORT_FILES) {
                error("The selected folder has too many files to import safely")
            }
            val input = context.contentResolver.openInputStream(child.uri)
                ?: error("Cannot read ${child.name}")
            val output = File(destination, safeName)
            input.use { stream ->
                output.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        copiedBytes[0] += read
                        if (copiedBytes[0] > MAX_JRE_IMPORT_BYTES) {
                            error("The selected folder is larger than the import limit")
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    override fun uninstallJre(major: Int) {
        viewModelScope.launch {
            container.jre.uninstall(major)
            showMessage("Java $major removed.")
            refreshDeviceInfo()
        }
    }

    /* ------------------------------------------------------------------ */
    /* JAR import / instance creation                                     */
    /* ------------------------------------------------------------------ */

    override fun inspectJar(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importingJar = true) }
            persistReadPermission(uri)
            val displayName = queryDisplayName(uri) ?: "server.jar"
            try {
                val source = withContext(Dispatchers.IO) {
                    val dest = File(
                        container.paths.downloadsDir,
                        "pending-${System.currentTimeMillis()}-${sanitizeFileName(displayName)}"
                    )
                    copyUriToFile(uri, dest)
                    dest
                }
                when (val inspection = withContext(Dispatchers.IO) { JarInspector.inspect(source) }) {
                    is InspectionResult.Success -> {
                        pendingJarSource?.delete()
                        pendingJarSource = source
                        _state.update {
                            it.copy(
                                importingJar = false,
                                pendingJar = PendingJar(displayName, source.name, inspection.inspection)
                            )
                        }
                    }

                    is InspectionResult.Failure -> {
                        source.delete()
                        _state.update { it.copy(importingJar = false) }
                        showMessage(inspection.message, true)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "JAR import failed", error)
                _state.update { it.copy(importingJar = false) }
                showMessage("Could not read that JAR: ${error.message}", true)
            }
        }
    }

    override fun clearPendingJar() {
        pendingJarSource?.delete()
        pendingJarSource = null
        _state.update { it.copy(pendingJar = null) }
    }

    override fun createInstance(draft: NewInstanceDraft) {
        val source = pendingJarSource
        if (source == null || !source.isFile) {
            showMessage("Pick the server JAR again before creating the instance.", true)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val id = container.paths.newInstanceId()
                val workingDir = container.paths.workingDir(id)
                val jarName = sanitizeJarName(draft.jarFile)
                val destination = File(workingDir, jarName)
                source.copyTo(destination, overwrite = true)
                if (draft.inspection.isMinecraft && draft.eulaAccepted) {
                    container.paths.eulaFile(id).writeText("eula=true\n")
                }
                val now = System.currentTimeMillis()
                val instance = ServerInstance(
                    id = id,
                    name = draft.name.trim().ifBlank { jarName.removeSuffix(".jar") },
                    jarFile = jarName,
                    kind = draft.inspection.kind,
                    flavor = draft.inspection.flavor,
                    minecraftVersion = draft.inspection.minecraftVersion,
                    jreMajor = draft.jreMajor,
                    memoryMb = draft.memoryMb,
                    autoMemory = draft.autoMemory,
                    jvmArgs = draft.jvmArgs.trim(),
                    serverArgs = draft.serverArgs.trim(),
                    eulaAccepted = draft.eulaAccepted,
                    autoRestart = container.settings.current().autoRestartOnCrash,
                    createdAt = now,
                    serverPort = draft.serverPort
                )
                container.instances.create(instance)
                container.settings.update {
                    it.copy(
                        firstServerAdded = true,
                        minecraftEulaAcknowledged = it.minecraftEulaAcknowledged ||
                            (draft.inspection.isMinecraft && draft.eulaAccepted)
                    )
                }
                source.delete()
                pendingJarSource = null
                _state.update {
                    it.copy(pendingJar = null, lastCreatedInstanceId = id)
                }
                showMessage("Created ${instance.name}.")
            } catch (error: Exception) {
                Log.w(TAG, "instance creation failed", error)
                showMessage("Could not create instance: ${error.message}", true)
            }
        }
    }

    override fun deleteInstance(id: String) {
        val runtime = _state.value.runtime
        val needsStop = runtime.isActive && runtime.instanceId == id
        viewModelScope.launch {
            if (needsStop) {
                showMessage("Stopping the server before deleting its files…")
                runCatching { ForegroundServerService.stop(context, force = true) }
                val stopped = withTimeoutOrNull(20_000) {
                    container.server.runtime.first { status ->
                        status.instanceId != id || !status.isActive
                    }
                } != null
                if (!stopped) {
                    showMessage(
                        "The server did not stop in time; the instance was not deleted. Stop it manually and retry.",
                        true
                    )
                    return@launch
                }
            }
            // Deleting a large world walks thousands of files: never on main.
            withContext(Dispatchers.IO) { container.instances.delete(id) }
            showMessage("Instance deleted.")
        }
    }

    override fun updateInstance(instance: ServerInstance) {
        container.instances.upsert(instance)
    }

    override fun renameInstance(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            showMessage("Name cannot be empty.", true)
            return
        }
        container.instances.update(id) { it.copy(name = trimmed) }
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                          */
    /* ------------------------------------------------------------------ */

    override fun startServer(id: String) {
        val instance = container.instances.get(id)
        if (instance == null) {
            showMessage("Instance not found.", true)
            return
        }
        if (!container.jre.isInstalled(instance.jreMajor)) {
            showMessage("Java ${instance.jreMajor} is not installed. Open Runtime and install it first.", true)
            return
        }
        if (instance.isMinecraft && !instance.eulaAccepted) {
            showMessage("Accept the Minecraft EULA on the instance screen before starting.", true)
            return
        }
        val runtime = container.server.runtime.value
        if (runtime.isActive && runtime.instanceId != id) {
            showMessage("Stop ${runtime.instanceName ?: "the running server"} first. Only one JVM is hosted at a time.", true)
            return
        }
        runCatching { ForegroundServerService.start(context, id) }
            .onFailure { showMessage("Could not start the server service: ${it.message}", true) }
    }

    override fun stopServer(force: Boolean) {
        runCatching { ForegroundServerService.stop(context, force) }
            .onFailure { showMessage("Could not stop the server: ${it.message}", true) }
    }

    override fun restartServer() {
        runCatching { ForegroundServerService.restart(context) }
            .onFailure { showMessage("Could not restart the server: ${it.message}", true) }
    }

    override fun forceStopServer() {
        runCatching { ForegroundServerService.forceStop(context) }
            .onFailure {
                // Even if the service helper fails, clear the controller state.
                container.server.forceStop()
                showMessage("Force-stopped the server session.", true)
            }
    }

    override fun sendCommand(command: String) {
        if (command.isBlank()) return
        container.server.sendCommand(command)
    }

    override fun probeLan() {
        if (!container.server.probeLan()) {
            showMessage("Start the server to test its LAN address.")
            return
        }
        showMessage("Testing LAN reachability from the phone…")
    }

    /** Called from MainActivity.onResume so status reflects system-dialog changes. */
    fun onResume() {
        refreshPowerChecks()
        refreshDeviceInfo()
    }

    override fun clearConsole() {
        container.server.clearConsole()
    }

    override fun exportConsole(uri: Uri) {
        viewModelScope.launch {
            try {
                val temp = File(container.paths.downloadsDir, "console-${System.currentTimeMillis()}.log")
                val exported = container.server.exportConsole(temp).getOrThrow()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        exported.inputStream().use { it.copyTo(out) }
                    } ?: error("Cannot open destination")
                    exported.delete()
                }
                persistWritePermission(uri)
                showMessage("Console exported.")
            } catch (error: Exception) {
                showMessage("Export failed: ${error.message}", true)
            }
        }
    }

    override suspend fun readStartupTail(instanceId: String, lines: Int): List<String> =
        withContext(Dispatchers.IO) {
            // The controller is instance-aware: active session tail, crash tail,
            // then the app transcript, then the server's own logs for THIS id.
            container.server.readStartupTail(instanceId, lines)
        }

    /* ------------------------------------------------------------------ */
    /* Settings                                                           */
    /* ------------------------------------------------------------------ */

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        container.settings.update(transform)
    }

    override fun setDefaultMemory(mb: Int) = updateSettings { it.copy(defaultMemoryMb = mb) }
    override fun setAutoMemoryDefault(enabled: Boolean) = updateSettings { it.copy(autoMemoryByDefault = enabled) }
    override fun setDefaultJre(major: Int) = updateSettings { it.copy(defaultJreMajor = major) }
    override fun setSilentMediaHack(enabled: Boolean) {
        if (!BuildConfig.ENABLE_SILENT_MEDIA_HACK) {
            showMessage("The silent-media keep-alive is disabled in this build (Play policy risk).", true)
            return
        }
        updateSettings { it.copy(silentMediaHack = enabled) }
        refreshPowerChecks()
    }

    override fun setTermuxMirror(mirror: String) = updateSettings { it.copy(termuxMirror = mirror.trim()) }
    override fun setAutoRestart(enabled: Boolean) = updateSettings { it.copy(autoRestartOnCrash = enabled) }
    override fun setMinecraftLanDiscovery(enabled: Boolean) =
        updateSettings { it.copy(minecraftLanDiscovery = enabled) }
    override fun setBedrockLanDiscovery(enabled: Boolean) =
        updateSettings { it.copy(bedrockLanDiscovery = enabled) }

    override fun setInstanceJvmArgs(id: String, args: String) {
        container.instances.update(id) { it.copy(jvmArgs = args.trim()) }
    }

    override fun setInstanceArgs(id: String, args: String) {
        container.instances.update(id) { it.copy(serverArgs = args.trim()) }
    }

    override fun setInstanceMemory(id: String, memoryMb: Int, auto: Boolean) {
        container.instances.update(id) { it.copy(memoryMb = memoryMb, autoMemory = auto) }
    }

    override fun setInstanceJre(id: String, major: Int) {
        if (!container.jre.isInstalled(major)) {
            showMessage("Java $major is not installed yet.", true)
            return
        }
        container.instances.update(id) { it.copy(jreMajor = major) }
    }

    override fun setInstanceAutoRestart(id: String, enabled: Boolean) {
        container.instances.update(id) { it.copy(autoRestart = enabled) }
    }

    override fun acceptEula(id: String) {
        val instance = container.instances.get(id) ?: return
        runCatching { container.paths.eulaFile(id).writeText("eula=true\n") }
            .onFailure {
                showMessage("Could not write eula.txt: ${it.message}", true)
                return
            }
        container.instances.update(id) { it.copy(eulaAccepted = true) }
        container.settings.update { it.copy(minecraftEulaAcknowledged = true) }
        showMessage("Minecraft EULA accepted for ${instance.name}.")
    }

    /* ------------------------------------------------------------------ */
    /* Files                                                              */
    /* ------------------------------------------------------------------ */

    override suspend fun listFiles(instanceId: String, relativeDir: String): Result<List<FileEntry>> =
        withContext(Dispatchers.IO) { container.files.list(instanceId, relativeDir) }

    override suspend fun readTextFile(instanceId: String, relativePath: String): Result<String> =
        withContext(Dispatchers.IO) { container.files.readText(instanceId, relativePath) }

    override suspend fun writeTextFile(instanceId: String, relativePath: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            container.files.writeText(instanceId, relativePath, text).onFailure {
                showMessage(it.message ?: "Write failed", true)
            }.map { }
        }

    override suspend fun deleteEntry(instanceId: String, relativePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            container.files.delete(instanceId, relativePath).onFailure {
                showMessage(it.message ?: "Delete failed", true)
            }
        }

    override suspend fun renameEntry(instanceId: String, relativePath: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            container.files.rename(instanceId, relativePath, newName).onFailure {
                showMessage(it.message ?: "Rename failed", true)
            }.map { }
        }

    override suspend fun createDirectory(instanceId: String, parentRelative: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            container.files.mkdir(instanceId, parentRelative, name).onFailure {
                showMessage(it.message ?: "Could not create folder", true)
            }.map { }
        }

    override suspend fun importFile(instanceId: String, targetRelativeDir: String, uri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            val name = queryDisplayName(uri) ?: "imported-file"
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open selected file"))
            input.use {
                container.files.importStream(instanceId, targetRelativeDir, name, it).onFailure { error ->
                    showMessage(error.message ?: "Import failed", true)
                }.map { file -> file.name }
            }
        }

    override suspend fun exportFile(instanceId: String, relativePath: String, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = container.files.resolve(instanceId, relativePath)
                    ?: error("File not found")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("Cannot open destination")
                persistWritePermission(uri)
            }.onFailure { showMessage("Export failed: ${it.message}", true) }
        }

    /* ------------------------------------------------------------------ */
    /* server.properties                                                  */
    /* ------------------------------------------------------------------ */

    override suspend fun readServerProperties(instanceId: String): Result<PropertiesSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = container.paths.serverProperties(instanceId)
                val raw = if (file.isFile) file.readText() else ""
                val entries = ServerPropertiesCodec.parse(raw)
                PropertiesSnapshot(raw = raw, entries = entries)
            }.onFailure { showMessage("Could not read server.properties: ${it.message}", true) }
        }

    override suspend fun saveServerProperties(instanceId: String, entries: Map<String, String>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = container.paths.serverProperties(instanceId)
                val previous = if (file.isFile) file.readText() else ""
                file.writeText(ServerPropertiesCodec.format(entries, previous))
                showMessage("server.properties saved. Restart the server to apply changes.")
            }.onFailure { showMessage("Could not save server.properties: ${it.message}", true) }
        }

    /* ------------------------------------------------------------------ */
    /* Modrinth                                                           */
    /* ------------------------------------------------------------------ */

    override suspend fun searchModrinth(
        target: ModTarget,
        query: String,
        gameVersion: String?,
        loader: String?,
        offset: Int
    ): Result<List<ModrinthProject>> = withContext(Dispatchers.IO) {
        container.modrinth.search(target, query, gameVersion, loader, offset)
    }

    override suspend fun listModrinthVersions(
        projectId: String,
        gameVersion: String?,
        loader: String?
    ): Result<List<ModrinthVersion>> = withContext(Dispatchers.IO) {
        container.modrinth.listVersions(projectId, gameVersion, loader)
    }

    override suspend fun installModrinth(
        instanceId: String,
        version: ModrinthVersion,
        target: ModTarget
    ): Result<File> = withContext(Dispatchers.IO) {
        container.modrinth.install(instanceId, version, target)
    }

    /* ------------------------------------------------------------------ */
    /* Guides                                                             */
    /* ------------------------------------------------------------------ */

    override fun guideSections(): List<GuideSection> = container.power.guideSections()

    /* ------------------------------------------------------------------ */
    /* External navigation                                                */
    /* ------------------------------------------------------------------ */

    fun onExternalNavigation(instanceId: String, openConsole: Boolean) {
        _navigation.value = NavigationRequest(instanceId, openConsole, System.nanoTime())
    }

    fun consumeNavigation() {
        _navigation.value = null
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun persistWritePermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun copyUriToFile(uri: Uri, dest: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open selected file")
        input.use { source ->
            dest.parentFile?.mkdirs()
            dest.outputStream().use { target -> source.copyTo(target) }
        }
    }

    private fun sanitizeFileName(name: String): String =
        SafePaths.sanitizeFileName(name, fallback = "server.jar")

    private fun sanitizeJarName(name: String): String {
        val cleaned = sanitizeFileName(name)
        return if (cleaned.lowercase(Locale.ROOT).endsWith(".jar")) cleaned else "$cleaned.jar"
    }

    /**
     * Cheap per-instance Geyser probe for the LAN card while no server is running.
     * Mirrors ServerManager's detection: a Geyser `config.yml` inside the plugins
     * directory, a Geyser plugin JAR in `plugins/`, or a Fabric Geyser mod JAR in
     * `mods/`. Runs off the main thread and never throws.
     */
    private suspend fun detectBedrockInstances(instances: List<ServerInstance>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            instances.associate { instance ->
                instance.id to runCatching { hasGeyserInstalled(instance.id) }.getOrDefault(false)
            }
        }

    private fun hasGeyserInstalled(instanceId: String): Boolean {
        val pluginsDir = container.paths.pluginsDir(instanceId)
        if (BedrockGeyserConfig.findConfigFile(pluginsDir) != null) return true
        if (hasGeyserJar(pluginsDir)) return true
        return hasGeyserJar(container.paths.modsDir(instanceId))
    }

    private fun hasGeyserJar(dir: File): Boolean =
        dir.listFiles().orEmpty().any { file ->
            file.isFile &&
                file.extension.equals("jar", ignoreCase = true) &&
                file.name.lowercase(Locale.ROOT).contains("geyser")
        }

    override fun onCleared() {
        // The child JVM belongs to the foreground service, not the ViewModel.
        super.onCleared()
    }

    data class NavigationRequest(
        val instanceId: String,
        val openConsole: Boolean,
        val nonce: Long
    )

    companion object {
        private const val TAG = "AppViewModel"
        private const val MAX_JRE_SEARCH_DEPTH = 5
        private const val MAX_JRE_IMPORT_BYTES = 1_500_000_000L
        private const val MAX_JRE_IMPORT_FILES = 20_000
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
