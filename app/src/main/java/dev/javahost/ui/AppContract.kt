package dev.javahost.ui

import android.net.Uri
import dev.javahost.core.model.AppSettings
import dev.javahost.core.model.DeviceInfo
import dev.javahost.core.model.ExitInfo
import dev.javahost.core.model.FileEntry
import dev.javahost.core.model.GuideSection
import dev.javahost.core.model.JobProgress
import dev.javahost.core.model.JreInstallation
import dev.javahost.core.model.ModTarget
import dev.javahost.core.model.ModrinthProject
import dev.javahost.core.model.ModrinthVersion
import dev.javahost.core.model.NewInstanceDraft
import dev.javahost.core.model.PendingJar
import dev.javahost.core.model.PowerChecks
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.model.ServerRuntimeStatus
import dev.javahost.core.model.ThemeChoice
import dev.javahost.core.model.UiMessage
import java.io.File

/**
 * Immutable snapshot the whole Compose tree renders from. Heavy, high-frequency
 * state (console lines) intentionally lives in a separate flow so one log line
 * does not recompose every screen.
 */
data class AppUiState(
    val loading: Boolean = true,
    val wizardComplete: Boolean = false,
    val device: DeviceInfo = DeviceInfo(
        abi = "", androidSdk = 26, androidRelease = "", totalRamMb = 0,
        availRamMb = 0, safeMaxHeapMb = 2048, autoHeapMb = 1536,
        lowRamDevice = false, manufacturer = "", model = "", coreCount = 1
    ),
    val settings: AppSettings = AppSettings(),
    val instances: List<ServerInstance> = emptyList(),
    /** Focused instance status (back-compat: console screen / notification target). */
    val runtime: ServerRuntimeStatus = ServerRuntimeStatus(),
    /** Per-instance live/terminal status; entries survive until the process dies. */
    val runtimes: Map<String, ServerRuntimeStatus> = emptyMap(),
    /**
     * Port -> instance name for every configured instance (Minecraft:
     * `server.properties server-port`, fallback [ServerInstance.serverPort]/25565;
     * generic: [ServerInstance.serverPort]). Used for port-conflict warnings.
     */
    val portOwners: Map<Int, String> = emptyMap(),
    /** Port -> instance id, so callers can exclude their own instance. */
    val portOwnerIds: Map<Int, String> = emptyMap(),
    /** Instance id -> resolved configured port (for the detail screen warning). */
    val instancePorts: Map<String, Int> = emptyMap(),
    /**
     * Per instance id: true when a Geyser plugin/mod is installed on disk. Detected
     * independently of a running server so the LAN card can offer the Bedrock bridge
     * while the instance is stopped.
     */
    val bedrockDetected: Map<String, Boolean> = emptyMap(),
    val activeInstanceId: String? = null,
    val jres: List<JreInstallation> = emptyList(),
    val jreJobs: Map<String, JobProgress> = emptyMap(),
    val pendingJar: PendingJar? = null,
    val lastCrash: ExitInfo? = null,
    /** Instance the [lastCrash] belongs to (newest unexpected exit across all). */
    val lastCrashInstanceId: String? = null,
    val power: PowerChecks = PowerChecks(),
    val message: UiMessage? = null,
    val importingJar: Boolean = false,
    val supportedJreMajors: List<Int> = listOf(25, 21, 17),
    val recommendedJreMajor: Int = 21,
    /** Set after a successful create so AppRoot can navigate to the new instance. */
    val lastCreatedInstanceId: String? = null
) {
    fun instance(id: String?): ServerInstance? = id?.let { key -> instances.firstOrNull { it.id == key } }
    val activeInstance: ServerInstance? get() = instance(activeInstanceId)
    /** Number of servers currently starting/running/stopping. */
    val runningCount: Int get() = runtimes.values.count { it.isActive }
    /** Runtime of one instance; an empty STOPPED status when it has never run. */
    fun runtimeFor(id: String): ServerRuntimeStatus = runtimes[id] ?: ServerRuntimeStatus()
    /** Ids of every instance that currently has an active child JVM. */
    val activeInstanceIds: List<String>
        get() = runtimes.values.filter { it.isActive }.mapNotNull { it.instanceId }

    /**
     * Another instance whose configured port is [port], excluding [exceptInstanceId].
     * Scans every instance (not just the first one recorded in [portOwners]) so a
     * port shared by two instances warns symmetrically on both sides.
     */
    fun portConflictFor(port: Int, exceptInstanceId: String? = null): ServerInstance? =
        instances.firstOrNull { it.id != exceptInstanceId && instancePorts[it.id] == port }
    /** True when at least one runtime exists; per-major gating happens on start. */
    val jreReady: Boolean get() = jres.isNotEmpty()
    fun isInstalled(major: Int): Boolean = jres.any { it.major == major }
    fun jobFor(major: Int): JobProgress? = jreJobs["jre-$major"]
}

enum class PowerAction {
    REQUEST_BATTERY_EXEMPTION,
    OPEN_BATTERY_SETTINGS,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_DEVELOPER_OPTIONS,
    OPEN_APP_DETAILS
}

data class PropertiesSnapshot(
    val raw: String,
    val entries: Map<String, String>
)

/**
 * All commands the UI may issue. Implemented by [AppViewModel]; screens stay
 * stateless and receive this interface so they can be developed independently.
 */
interface AppActions {
    // --- messages / onboarding -------------------------------------------------
    fun dismissMessage()
    fun dismissCrash()
    fun completeWizard()
    fun setPlanMinecraft(value: Boolean)
    fun acknowledgeMinecraftEula()
    fun onNotificationPermissionResult(granted: Boolean)
    fun refreshPowerChecks()
    fun openPowerAction(action: PowerAction): Boolean

    // --- JRE -------------------------------------------------------------------
    fun installJre(major: Int)
    fun cancelJreJob(major: Int)
    fun importJreArchive(uri: Uri)
    /** SAF folder picker: an already-downloaded/extracted JRE on the device. */
    fun importJreFolder(uri: Uri)
    fun uninstallJre(major: Int)

    // --- JAR / instances -------------------------------------------------------
    fun inspectJar(uri: Uri)
    fun clearPendingJar()
    fun createInstance(draft: NewInstanceDraft)
    fun deleteInstance(id: String)
    fun updateInstance(instance: ServerInstance)
    fun renameInstance(id: String, name: String)

    // --- lifecycle -------------------------------------------------------------
    /** Start one server (up to [dev.javahost.core.api.ServerController.MAX_RUNNING_SERVERS] may run). */
    fun startServer(id: String)
    /** Gracefully stop one server; the other running servers are unaffected. */
    fun stopServer(id: String, force: Boolean = false)
    /** Stop then start one server; other running servers are unaffected. */
    fun restartServer(id: String)
    /** Immediately kill a wedged server process and clear its session. */
    fun forceStopServer(id: String)
    fun sendCommand(instanceId: String, command: String)
    fun clearConsole(instanceId: String)
    fun exportConsole(instanceId: String, uri: Uri)
    suspend fun readStartupTail(instanceId: String, lines: Int = 200): List<String>
    /** Switch the focused instance (console/notification target) without leaving the screen. */
    fun focusConsole(instanceId: String)

    // --- LAN -------------------------------------------------------------------
    /**
     * Re-resolve the LAN addresses of one running server and TCP-probe each one,
     * publishing the result to its [ServerRuntimeStatus.lan]. No-op (with a
     * message) when that server is not active.
     */
    fun probeLan(instanceId: String)

    /** Enable/disable the Minecraft LAN discovery announcement for running servers. */
    fun setMinecraftLanDiscovery(enabled: Boolean)

    /**
     * Enable/disable the Bedrock Edition LAN bridge (Geyser on UDP 19133, app relay
     * on UDP 19132). Takes effect on the next server start/restart because Geyser's
     * port is fixed when the JVM launches.
     */
    fun setBedrockLanDiscovery(enabled: Boolean)

    // --- settings --------------------------------------------------------------
    /** Persist the colour theme (applies immediately via MainActivity). */
    fun setTheme(theme: ThemeChoice)
    fun setDefaultMemory(mb: Int)
    fun setAutoMemoryDefault(enabled: Boolean)
    fun setDefaultJre(major: Int)
    fun setSilentMediaHack(enabled: Boolean)
    fun setTermuxMirror(mirror: String)
    fun setAutoRestart(enabled: Boolean)
    fun setInstanceJvmArgs(id: String, args: String)
    fun setInstanceArgs(id: String, args: String)
    fun setInstanceMemory(id: String, memoryMb: Int, auto: Boolean)
    fun setInstanceJre(id: String, major: Int)
    fun setInstanceAutoRestart(id: String, enabled: Boolean)
    fun acceptEula(id: String)

    // --- files -----------------------------------------------------------------
    suspend fun listFiles(instanceId: String, relativeDir: String): Result<List<FileEntry>>
    suspend fun readTextFile(instanceId: String, relativePath: String): Result<String>
    suspend fun writeTextFile(instanceId: String, relativePath: String, text: String): Result<Unit>
    suspend fun deleteEntry(instanceId: String, relativePath: String): Result<Unit>
    suspend fun renameEntry(instanceId: String, relativePath: String, newName: String): Result<Unit>
    suspend fun createDirectory(instanceId: String, parentRelative: String, name: String): Result<Unit>
    suspend fun importFile(instanceId: String, targetRelativeDir: String, uri: Uri): Result<String>
    suspend fun exportFile(instanceId: String, relativePath: String, uri: Uri): Result<Unit>

    // --- server.properties -----------------------------------------------------
    suspend fun readServerProperties(instanceId: String): Result<PropertiesSnapshot>
    suspend fun saveServerProperties(instanceId: String, entries: Map<String, String>): Result<Unit>

    // --- Modrinth --------------------------------------------------------------
    suspend fun searchModrinth(
        target: ModTarget,
        query: String,
        gameVersion: String?,
        loader: String?,
        offset: Int = 0
    ): Result<List<ModrinthProject>>

    suspend fun listModrinthVersions(
        projectId: String,
        gameVersion: String?,
        loader: String?
    ): Result<List<ModrinthVersion>>

    suspend fun installModrinth(
        instanceId: String,
        version: ModrinthVersion,
        target: ModTarget
    ): Result<File>

    // --- guides ----------------------------------------------------------------
    fun guideSections(): List<GuideSection>
}
