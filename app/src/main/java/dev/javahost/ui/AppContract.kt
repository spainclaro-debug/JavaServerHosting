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
    val runtime: ServerRuntimeStatus = ServerRuntimeStatus(),
    val activeInstanceId: String? = null,
    val jres: List<JreInstallation> = emptyList(),
    val jreJobs: Map<String, JobProgress> = emptyMap(),
    val pendingJar: PendingJar? = null,
    val lastCrash: ExitInfo? = null,
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
    fun startServer(id: String)
    fun stopServer(force: Boolean = false)
    fun restartServer()
    /** Immediately kill a wedged server process and clear the session. */
    fun forceStopServer()
    fun sendCommand(command: String)
    fun clearConsole()
    fun exportConsole(uri: Uri)
    suspend fun readStartupTail(instanceId: String, lines: Int = 200): List<String>

    // --- settings --------------------------------------------------------------
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
