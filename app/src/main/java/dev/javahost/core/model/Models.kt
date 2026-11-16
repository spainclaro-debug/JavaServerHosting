package dev.javahost.core.model

import kotlinx.serialization.Serializable

/*
 * Shared, persistence-friendly domain model.
 *
 * These types are deliberately free of Android framework types (except where
 * noted) so unit tests can run on a plain JVM. Android-only handles such as
 * Uri stay in UI/ViewModel code.
 */

enum class ServerKind { MINECRAFT, GENERIC }

enum class ServerFlavor {
    VANILLA,
    PAPER,
    FABRIC,
    FORGE,
    BUKKIT,
    OTHER_MINECRAFT,
    GENERIC
}

@Serializable
enum class RunState { STOPPED, STARTING, RUNNING, STOPPING, CRASHED }

/** App-wide colour theme chosen in Settings. */
@Serializable
enum class ThemeChoice {
    /** Follow the system dark/light setting (default). */
    SYSTEM,
    /** The warm cream / brown light theme. */
    LIGHT,
    /** The teal-on-ink dark theme. */
    DARK,
    /** Android 12+ dynamic colours; falls back to [SYSTEM] below Android 12. */
    MATERIAL_YOU,
    MONOCHROMATIC_BLUE,
    ENERGY,
    TRIADIC,
    ANALOG_GREEN,
    HIGH_CONTRAST_BLACK
}

@Serializable
enum class ExitReason {
    STOPPED_BY_USER,
    CRASH_OUT_OF_MEMORY,
    CRASH_PORT_IN_USE,
    CRASH_UNSUPPORTED_JAVA,
    CRASH_MISSING_LIBRARY,
    CRASH_EULA_REJECTED,
    KILLED_BY_SYSTEM,
    CRASH_UNKNOWN
}

@Serializable
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, COMMAND, UNKNOWN }

/** Result of opening a JAR and looking for server signatures. */
@Serializable
data class JarInspection(
    val mainClass: String? = null,
    val kind: ServerKind = ServerKind.GENERIC,
    val flavor: ServerFlavor = ServerFlavor.GENERIC,
    /** 0..1 heuristic confidence. Manual override always wins. */
    val confidence: Float = 0f,
    val evidence: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Highest class-file major version found while sampling entries, e.g. 61 = Java 17. */
    val classFileMajor: Int? = null,
    /** Suggested minimum runtime major (17, 21 or 25). */
    val suggestedJreMajor: Int = 21,
    val minecraftVersion: String? = null,
    /** True for Forge/Fabric installer jars that must be executed once, not as a server. */
    val needsInstaller: Boolean = false,
    /** Number of entries inspected (diagnostics / tests). */
    val entriesScanned: Int = 0
) {
    val isMinecraft: Boolean get() = kind == ServerKind.MINECRAFT
    val label: String
        get() = when (flavor) {
            ServerFlavor.VANILLA -> "Minecraft Vanilla"
            ServerFlavor.PAPER -> "Minecraft Paper / Spigot family"
            ServerFlavor.FABRIC -> "Minecraft Fabric"
            ServerFlavor.FORGE -> "Minecraft Forge"
            ServerFlavor.BUKKIT -> "Minecraft Bukkit family"
            ServerFlavor.OTHER_MINECRAFT -> "Minecraft server"
            ServerFlavor.GENERIC -> "Generic Java server"
        }
}

sealed class InspectionResult {
    data class Success(val inspection: JarInspection) : InspectionResult()
    data class Failure(val message: String) : InspectionResult()
}

/** One user-created server. Serialized as part of the instance store. */
@Serializable
data class ServerInstance(
    val id: String,
    val name: String,
    /** File name inside the instance working directory. */
    val jarFile: String,
    val kind: ServerKind = ServerKind.GENERIC,
    val flavor: ServerFlavor = ServerFlavor.GENERIC,
    val minecraftVersion: String? = null,
    val jreMajor: Int = 21,
    val memoryMb: Int = 1536,
    val autoMemory: Boolean = true,
    /** Raw free-text JVM flags (before -jar). */
    val jvmArgs: String = "",
    /** Raw free-text arguments after the jar. Minecraft defaults to "nogui". */
    val serverArgs: String = "nogui",
    val eulaAccepted: Boolean = false,
    val autoRestart: Boolean = false,
    val createdAt: Long = 0L,
    val lastStartedAt: Long? = null,
    val lastExitCode: Int? = null,
    val lastExitAt: Long? = null,
    /** Short, human-readable reason of the previous unexpected exit, if any. */
    val lastCrashSummary: String? = null,
    val totalRuntimeMs: Long = 0L,
    val serverPort: Int? = null
) {
    val isMinecraft: Boolean get() = kind == ServerKind.MINECRAFT
}

/** Persisted app-wide settings. */
@Serializable
data class AppSettings(
    val wizardComplete: Boolean = false,
    val defaultJreMajor: Int = 21,
    val defaultMemoryMb: Int = 1536,
    val autoMemoryByDefault: Boolean = true,
    /** Marginal keep-alive hack; off by default, hidden behind feature flag. */
    val silentMediaHack: Boolean = false,
    /** Empty means build-config mirror. Advanced users may set a Termux mirror. */
    val termuxMirror: String = "",
    val lastActiveInstanceId: String? = null,
    val consoleLogLimit: Int = 5000,
    /** Colour theme chosen in Settings; applies immediately. */
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** Announce a running Minecraft server on the LAN (multicast discovery). */
    val minecraftLanDiscovery: Boolean = true,
    /**
     * Bedrock Edition: run the app-side LAN bridge (UDP 19132) and move Geyser to
     * 19133 so Bedrock clients see the server in LAN/Friends. Applies on the next
     * server start because Geyser's port is fixed at process launch.
     */
    val bedrockLanDiscovery: Boolean = true,
    val autoRestartOnCrash: Boolean = false,
    val notificationsRequested: Boolean = false,
    val firstServerAdded: Boolean = false,
    /** Internal copy of Mojang's EULA acknowledgement from the first-run wizard. */
    val minecraftEulaAcknowledged: Boolean = false,
    /** Set when the user says they plan to run Minecraft; enables wizard EULA step. */
    val planMinecraft: Boolean = false
)

@Serializable
data class ExitInfo(
    val exitCode: Int? = null,
    val signal: Int? = null,
    val timestamp: Long = 0L,
    val expected: Boolean = false,
    val reason: ExitReason = ExitReason.CRASH_UNKNOWN,
    val summary: String = "",
    val tail: List<String> = emptyList(),
    /** Absolute path of the saved crash report, if one was written. */
    val reportPath: String? = null
)

data class ConsoleEntry(
    val seq: Long,
    val timestamp: Long,
    val level: LogLevel,
    val text: String
)

/* ------------------------------------------------------------------------- */
/* LAN access / discovery models                                             */
/* ------------------------------------------------------------------------- */

/** One address clients on the local network can try for the running server. */
data class LanEndpointInfo(
    val address: String,
    val interfaceName: String,
    val label: String,
    /** "192.168.1.50:25565" — always present when a port is known. */
    val hostPort: String? = null,
    /** "http://192.168.1.50:4567/" for web/HTTP servers (generic JARs). */
    val url: String? = null
)

/** Result of the app's own TCP reachability self-test against one LAN address. */
data class LanProbeInfo(
    val address: String,
    val port: Int,
    val result: String,
    val ok: Boolean,
    val latencyMs: Long? = null,
    val detail: String? = null
)

/**
 * Bedrock Edition (Geyser) LAN bridge state.
 *
 * Bedrock clients discover LAN servers by broadcasting a RakNet unconnected ping
 * to UDP 19132; JavaHost's bridge answers it and relays gameplay to Geyser on
 * UDP 19133, so the client's "LAN/Friends" list can show the server.
 */
data class BedrockLanStatus(
    /** A Geyser plugin JAR was found in plugins/. */
    val geyserDetected: Boolean = false,
    /** The user wants the bridge and it is applicable (Minecraft + Geyser). */
    val bridgeRequested: Boolean = false,
    /** The UDP 19132 relay actually bound and is relaying. */
    val relayRunning: Boolean = false,
    /** Port Bedrock clients must use (normally 19132). */
    val relayPort: Int? = null,
    /** Geyser answered a RakNet ping on its internal port (normally 19133). */
    val geyserReachable: Boolean = false,
    val motd: String? = null,
    val version: String? = null,
    val protocol: Int? = null,
    /** "3/10" when known. */
    val players: String? = null,
    /** Bedrock addresses clients can use through the LAN bridge (UDP 19132), one per LAN interface. */
    val endpoints: List<LanEndpointInfo> = emptyList(),
    /**
     * Geyser's own addresses (`<ip>:19133`) for Bedrock clients that switched to
     * the NetherNet transport and cannot join through the RakNet LAN bridge; use
     * Add Server with one of these as the fallback.
     */
    val directEndpoints: List<LanEndpointInfo> = emptyList(),
    /** Actionable problem (relay could not bind, Geyser not reachable, ...). */
    val warning: String? = null
)

/**
 * Everything the UI needs to describe how other devices can reach the server:
 * the usable LAN addresses, the port, the phone's connectivity state, the
 * reachability self-test and the one-line warnings/help text.
 */
data class LanStatus(
    val endpoints: List<LanEndpointInfo> = emptyList(),
    val port: Int? = null,
    val wifiConnected: Boolean = false,
    val ethernetConnected: Boolean = false,
    val mobileDataConnected: Boolean = false,
    val vpnActive: Boolean = false,
    /** Minecraft only: multicast LAN discovery announcement is active. */
    val announcementEnabled: Boolean = false,
    val probes: List<LanProbeInfo> = emptyList(),
    /** Blocking problem the user must fix (no LAN address, VPN, loopback bind). */
    val warning: String? = null,
    /** Short actionable line ("Add server 192.168.1.50:25565 in Minecraft"). */
    val summary: String? = null,
    /** Bedrock Edition bridge state (Geyser). */
    val bedrock: BedrockLanStatus = BedrockLanStatus()
) {
    val hasEndpoint: Boolean get() = endpoints.isNotEmpty()
}

/** Live status of the (single) active server. */
data class ServerRuntimeStatus(
    val state: RunState = RunState.STOPPED,
    val instanceId: String? = null,
    val instanceName: String? = null,
    val pid: Long? = null,
    val startedAt: Long? = null,
    val uptimeMs: Long = 0L,
    val playersOnline: Int = 0,
    val playersMax: Int = 0,
    /** TCP connections on the server port for generic (non-Minecraft) servers. */
    val connectionsOnline: Int? = null,
    val serverPort: Int? = null,
    val heapUsedMb: Int? = null,
    val heapMaxMb: Int? = null,
    val processRssMb: Long? = null,
    val deviceAvailMb: Long = 0L,
    val deviceTotalMb: Long = 0L,
    val ready: Boolean = false,
    val lastExit: ExitInfo? = null,
    /** LAN reachability snapshot for the active server. */
    val lan: LanStatus = LanStatus()
) {
    val isActive: Boolean
        get() = state == RunState.STARTING || state == RunState.RUNNING || state == RunState.STOPPING
}

/** Outcome of trying to start a server process. */
sealed class StartResult {
    data object Ok : StartResult()
    data class Rejected(val message: String) : StartResult()
}

data class JreInstallation(
    val major: Int,
    val versionLabel: String,
    val javaHome: String,
    val installedBytes: Long,
    val installedAt: Long
)

enum class JobState { RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class JobProgress(
    val key: String,
    val title: String,
    val fraction: Float? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val state: JobState = JobState.RUNNING,
    val message: String? = null
)

data class NewInstanceDraft(
    val name: String,
    val jarFile: String,
    val inspection: JarInspection,
    val jreMajor: Int,
    val memoryMb: Int,
    val autoMemory: Boolean,
    val jvmArgs: String,
    val serverArgs: String,
    val eulaAccepted: Boolean,
    val serverPort: Int? = null
)

data class PendingJar(
    val displayName: String,
    val jarFile: String,
    val inspection: JarInspection
)

data class DeviceInfo(
    val abi: String,
    val androidSdk: Int,
    val androidRelease: String,
    val totalRamMb: Long,
    val availRamMb: Long,
    val safeMaxHeapMb: Int,
    val autoHeapMb: Int,
    val lowRamDevice: Boolean,
    val manufacturer: String,
    val model: String,
    val coreCount: Int
)

data class PowerChecks(
    val batteryOptimizationIgnored: Boolean = false,
    val notificationsGranted: Boolean = true,
    val androidSdk: Int = 26,
    val phantomProcessKillerGuidanceNeeded: Boolean = false,
    val mediaHackFeatureEnabled: Boolean = false,
    val mediaHackSettingEnabled: Boolean = false
)

data class UiMessage(val id: Long, val text: String, val isError: Boolean = false)

/* ------------------------------------------------------------------------- */
/* Repository / file-browser / guidance models                               */
/* ------------------------------------------------------------------------- */

data class FileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long
)

data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val downloads: Long,
    val projectType: String,
    val loaders: List<String>,
    val gameVersions: List<String>,
    val author: String?
)

data class ModrinthFile(
    val filename: String,
    val url: String,
    val size: Long,
    val sha1: String?,
    val primary: Boolean
)

data class ModrinthVersion(
    val id: String,
    val projectId: String,
    val name: String,
    val versionNumber: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<ModrinthFile>,
    val datePublished: String
)

enum class ModTarget { PLUGINS, MODS }

data class GuideStep(val title: String, val body: String)

data class GuideSection(
    val title: String,
    val intro: String,
    val steps: List<GuideStep>,
    val adbCommands: List<String> = emptyList(),
    val helpUrl: String? = null
)
