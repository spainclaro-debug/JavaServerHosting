package dev.javahost.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.javahost.BuildConfig
import dev.javahost.core.model.GuideSection
import dev.javahost.core.model.JobState
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.PowerAction
import dev.javahost.ui.components.Bullet
import dev.javahost.ui.components.CodeLine
import dev.javahost.ui.components.InfoRow
import dev.javahost.ui.components.JobProgressCard
import dev.javahost.ui.components.LoadingRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenContent
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.SuccessRow
import dev.javahost.ui.components.Tag
import dev.javahost.ui.components.formatBytes
import dev.javahost.ui.components.formatMegabytes
import dev.javahost.ui.theme.javaHostStatusColors

private const val MINECRAFT_EULA_URL = "https://aka.ms/MinecraftEULA"

private data class WizardStep(val key: String, val title: String)

/**
 * First-run setup. Everything here can be repeated later from Settings; the
 * wizard never starts a server and never claims a guaranteed OEM menu path.
 */
@Composable
fun WizardScreen(state: AppUiState, actions: AppActions, onFinished: () -> Unit) {
    val planMinecraft = state.settings.planMinecraft
    val steps = remember(planMinecraft) {
        buildList {
            add(WizardStep("welcome", "Welcome"))
            add(WizardStep("runtime", "Java runtime"))
            add(WizardStep("reliability", "Battery & alerts"))
            add(WizardStep("phantom", "Background limits"))
            if (planMinecraft) add(WizardStep("eula", "Minecraft EULA"))
            add(WizardStep("done", "Ready"))
        }
    }
    var stepIndex by rememberSaveable { mutableStateOf(0) }
    // Keep the index valid when the Minecraft step is toggled on/off.
    LaunchedEffect(steps.size) { stepIndex = stepIndex.coerceIn(0, steps.lastIndex) }
    val step = steps[stepIndex]
    val isLast = stepIndex == steps.lastIndex
    val eulaAcknowledged = state.settings.minecraftEulaAcknowledged
    val eulaBlocked = step.key == "eula" && planMinecraft && !eulaAcknowledged
    val canSkipThisStep = step.key == "runtime" || step.key == "reliability" || step.key == "phantom"

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> actions.onNotificationPermissionResult(granted) }

    ScreenScaffold(
        title = "First-time setup",
        subtitle = "Step ${stepIndex + 1} of ${steps.size} · ${step.title}",
        topActions = {
            TextButton(onClick = { actions.completeWizard(); onFinished() }) { Text("Skip") }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    if (isLast) {
                        Button(
                            onClick = { actions.completeWizard(); onFinished() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Finish setup") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (stepIndex > 0) {
                                TextButton(onClick = { stepIndex -= 1 }) { Text("Back") }
                            } else {
                                TextButton(onClick = { actions.completeWizard(); onFinished() }) {
                                    Text("Skip setup")
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (canSkipThisStep) {
                                TextButton(onClick = { stepIndex += 1 }) { Text("Skip step") }
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { stepIndex += 1 }, enabled = !eulaBlocked) {
                                Text("Next")
                            }
                        }
                        if (eulaBlocked) {
                            Text(
                                "Accept the Minecraft EULA above to continue.",
                                style = MaterialTheme.typography.labelSmall,
                                color = javaHostStatusColors().warning
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        ScreenContent(padding) {
            state.message?.let { message ->
                MessageBanner(message.text, message.isError, actions::dismissMessage)
            }
            LinearProgressIndicator(
                progress = { (stepIndex + 1).toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth()
            )
            when (step.key) {
                "welcome" -> WelcomeStep(state = state, actions = actions)
                "runtime" -> RuntimeStep(state = state, actions = actions)
                "reliability" -> ReliabilityStep(
                    state = state,
                    actions = actions,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            actions.onNotificationPermissionResult(true)
                        }
                    }
                )
                "phantom" -> PhantomStep(state = state, actions = actions)
                "eula" -> EulaStep(state = state, actions = actions)
                else -> DoneStep(state = state)
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Step 1 — welcome + device                                                  */
/* -------------------------------------------------------------------------- */

@Composable
private fun WelcomeStep(state: AppUiState, actions: AppActions) {
    val device = state.device
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Welcome to JavaHost", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Run Java server JARs in the background on this phone. Minecraft servers are supported first-class; other Java servers are labelled Advanced / Experimental because no game-specific integrations are provided.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard(title = "How it works") {
            Bullet("Pick a server .jar from your storage — JavaHost copies it into app-private storage.")
            Bullet("A Java runtime is downloaded once (≈106 MB for Java 21) and verified against the Termux package index.")
            Bullet("A foreground service keeps the server process alive; Android may still pause it unless you allow battery exemption.")
            Bullet("Only one JVM is hosted at a time. Each server gets its own directory, world and configuration.")
        }
        SectionCard(title = "This device") {
            InfoRow("Model", listOf(device.manufacturer, device.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown" })
            InfoRow("Android", if (device.androidRelease.isBlank()) "API ${device.androidSdk}" else "${device.androidRelease} (API ${device.androidSdk})")
            InfoRow("CPU", "${device.coreCount} cores · ${device.abi.ifBlank { "unknown ABI" }}")
            InfoRow(
                "Memory",
                if (device.totalRamMb > 0) {
                    "${formatMegabytes(device.availRamMb.toInt())} free of ${formatMegabytes(device.totalRamMb.toInt())}"
                } else "Detecting…"
            )
            InfoRow("Safe heap ceiling", formatMegabytes(device.safeMaxHeapMb))
            if (device.abi.isNotBlank() && device.abi != "arm64-v8a") {
                MessageBanner(
                    "Only 64-bit ARM (arm64-v8a) Java runtimes are provided by JavaHost. " +
                        "This device reports ${device.abi}, so servers will probably not start.",
                    isError = true
                )
            }
            if (device.lowRamDevice) {
                MessageBanner(
                    "This is a low-RAM device. Keep server memory at or below " +
                        "${formatMegabytes(device.safeMaxHeapMb)} and run one server at a time.",
                    isError = false
                )
            }
        }
        SectionCard(title = "What will you run?") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("I plan to run Minecraft servers", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Adds a Minecraft EULA step and enables Minecraft-only tools (server.properties, mods, plugins).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.settings.planMinecraft,
                    onCheckedChange = { actions.setPlanMinecraft(it) }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("Minecraft: first-class")
                Tag("Other Java servers: experimental", javaHostStatusColors().warning)
            }
            Text(
                "You can change this later in Settings. Setup can be finished later too — every step is available from Settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Step 2 — Java runtime                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun RuntimeStep(state: AppUiState, actions: AppActions) {
    val major = state.recommendedJreMajor
    val installed = state.jres.firstOrNull { it.major == major }
    val job = state.jobFor(major)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            title = "Install Java $major (recommended)",
            subtitle = "Required before a server can start"
        ) {
            when {
                installed != null -> {
                    SuccessRow("Java $major is installed — ${installed.versionLabel}")
                    InfoRow("Installed size", formatBytes(installed.installedBytes))
                    InfoRow("Location", installed.javaHome)
                    Text(
                        "Manage Java versions from Settings → Java runtimes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                job != null && job.state == JobState.RUNNING -> {
                    JobProgressCard(job, onCancel = { actions.cancelJreJob(major) })
                    Text(
                        "Keep the app open while the runtime downloads. The download is roughly 106 MB and is verified with SHA-256 from the signed Termux package index.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                job != null && job.state == JobState.SUCCEEDED -> {
                    LoadingRow("Finishing Java $major installation…")
                }
                else -> {
                    if (job != null && job.state == JobState.FAILED) {
                        MessageBanner(
                            job.message ?: "Java installation failed. Check your connection or mirror and try again.",
                            isError = true
                        )
                    }
                    ListOfInstallNotes()
                    Button(
                        onClick = { actions.installJre(major) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Download and install Java $major (≈106 MB)") }
                }
            }
        }
        SectionCard(title = "Why is this needed?") {
            Bullet("Android does not ship with a Java runtime that can launch server JARs.")
            Bullet("JavaHost downloads Termux's OpenJDK packages, verifies the checksum, and extracts the runtime into app-private storage.")
            Bullet("Nothing is bundled in the APK; the runtime is removed when you uninstall JavaHost.")
        }
        MessageBanner(
            "You can skip this step and install a runtime later from Settings → Java runtimes. " +
                "Servers will not start until a runtime is installed.",
            isError = false
        )
        Text(
            "Java 17 and Java 25 are also available under Settings → Java runtimes if a server needs them.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ListOfInstallNotes() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoRow("Download", "≈106 MB (Java 21 packages)")
        InfoRow("Verification", "SHA-256 from HTTPS package index")
        InfoRow("Installed size", "≈170 MB")
        Bullet("A Wi-Fi connection is recommended. Downloads can be cancelled and retried (they restart from the beginning).")
    }
}

/* -------------------------------------------------------------------------- */
/* Step 3 — battery exemption + notifications                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun ReliabilityStep(
    state: AppUiState,
    actions: AppActions,
    onRequestNotifications: () -> Unit
) {
    val batteryExempt = state.power.batteryOptimizationIgnored
    val notificationsGranted = state.power.notificationsGranted || Build.VERSION.SDK_INT < 33
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Battery optimization") {
            if (batteryExempt) {
                SuccessRow("JavaHost is exempt from battery optimization")
            } else {
                MessageBanner(
                    "Android may pause or kill the server in the background without an exemption. " +
                        "The system dialog is controlled by Android — the wording varies by version.",
                    isError = false
                )
            }
            if (!batteryExempt) {
                Button(
                    onClick = { actions.openPowerAction(PowerAction.REQUEST_BATTERY_EXEMPTION) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Request battery exemption") }
                OutlinedButton(
                    onClick = { actions.openPowerAction(PowerAction.OPEN_BATTERY_SETTINGS) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open battery settings") }
            } else {
                TextButton(onClick = { actions.openPowerAction(PowerAction.OPEN_BATTERY_SETTINGS) }) {
                    Text("Open battery settings")
                }
            }
        }
        SectionCard(title = "Notifications") {
            if (notificationsGranted) {
                SuccessRow("Notifications are allowed")
            } else {
                Text(
                    "Android 13+ requires permission for notifications. JavaHost uses a foreground service; " +
                        "the notification shows server status and makes the process visible to Android.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow notifications")
                }
            }
            if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                Text(
                    "If the system does not show a dialog, enable notifications for JavaHost in Android settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { actions.openPowerAction(PowerAction.OPEN_NOTIFICATION_SETTINGS) }
            ) { Text("Open notification settings") }
        }
        SectionCard(title = "Keep in mind") {
            Bullet("An exemption is not a guarantee: aggressive OEM battery savers can still stop the app.")
            Bullet("Charging while testing helps. Avoid swiping JavaHost out of the recents list while a server runs.")
            Bullet("You can revisit these settings any time from Settings → Power & reliability.")
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Step 4 — Phantom Process Killer (Android 12+)                             */
/* -------------------------------------------------------------------------- */

@Composable
private fun PhantomStep(state: AppUiState, actions: AppActions) {
    val sdk = if (state.power.androidSdk > 0) maxOf(state.power.androidSdk, Build.VERSION.SDK_INT) else Build.VERSION.SDK_INT
    val guide: GuideSection? = remember {
        actions.guideSections().firstOrNull {
            it.title.contains("phantom", ignoreCase = true) ||
                it.title.contains("process", ignoreCase = true)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (sdk < 31) {
            SectionCard(title = "Not needed on this Android version") {
                SuccessRow("Android 12+ only: your device does not limit child processes this way.")
                Text(
                    "No action is needed here. OEM battery savers can still stop background apps — see the power guide from Home.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            SectionCard(title = "Android 12+ background process limits") {
                Text(
                    "Android 12 and newer can kill extra child processes when the system is under memory pressure " +
                        "(the \"Phantom Process Killer\"). A hosted JVM counts as a child process, so a long-running " +
                        "server can be terminated after a while.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Bullet("JavaHost runs the server in a foreground service, which is the most reliable in-app mitigation.")
                Bullet("Some devices allow raising the phantom process limit from Developer options; Android itself does not provide a standard toggle, and menus differ by manufacturer.")
                Bullet("There is no fixed OEM menu path. If you cannot find the option, use the ADB command below.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_DEVELOPER_OPTIONS) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Developer options") }
                    OutlinedButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_APP_DETAILS) },
                        modifier = Modifier.weight(1f)
                    ) { Text("App info") }
                }
            }
            SectionCard(title = "Optional ADB command") {
                Text(
                    "If you have a computer with ADB, this raises the phantom-process ceiling. Reboot the phone afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CodeLine("adb shell device_config put activity_manager max_phantom_processes 2147483647")
                Text(
                    "This is an advanced, optional step. JavaHost cannot run it for you and does not require it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (guide != null) {
                SectionCard(title = guide.title, subtitle = guide.intro) {
                    guide.steps.forEach { guideStep ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(guideStep.title, style = MaterialTheme.typography.labelLarge)
                            Bullet(guideStep.body)
                        }
                    }
                    guide.adbCommands.forEach { CodeLine(it) }
                }
            }
            MessageBanner(
                "OEM battery savers (Xiaomi, Samsung, OnePlus, Huawei and others) behave differently. " +
                    "The full power guide from Home links to per-manufacturer advice.",
                isError = false
            )
        }
        MessageBanner("You can skip this step. It only affects how long a server survives under memory pressure.", false)
    }
}

/* -------------------------------------------------------------------------- */
/* Step 5 — Minecraft EULA (opt-in only)                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun EulaStep(state: AppUiState, actions: AppActions) {
    val uriHandler = LocalUriHandler.current
    val acknowledged = state.settings.minecraftEulaAcknowledged
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Minecraft End User License Agreement") {
            Text(
                "You chose to run Minecraft servers. Mojang's EULA applies to the server software " +
                    "and to the game. JavaHost does not download Minecraft or bypass licensing — you supply your own server JAR.",
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { uriHandler.openUri(MINECRAFT_EULA_URL) }) {
                Text("Open the Minecraft EULA (aka.ms/MinecraftEULA)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { checked ->
                        if (checked) actions.acknowledgeMinecraftEula()
                    }
                )
                Column {
                    Text("I accept the Minecraft EULA", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A copy of the acknowledgement is stored on this device so Minecraft servers can write eula=true.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!acknowledged) {
                MessageBanner(
                    "You can also accept the EULA later on an individual server's detail screen.",
                    isError = false
                )
            } else {
                SuccessRow("Minecraft EULA acknowledged")
            }
        }
        SectionCard(title = "What JavaHost will do") {
            Bullet("Write eula=true into the server's eula.txt only after you accept — the server itself still shows the EULA prompt if you refuse.")
            Bullet("Hide Minecraft-specific sections (server.properties, mods, plugins) for non-Minecraft servers.")
            Bullet("Treat the server JAR as your responsibility; JavaHost does not redistribute Minecraft assets.")
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Step 6 — done                                                              */
/* -------------------------------------------------------------------------- */

@Composable
private fun DoneStep(state: AppUiState) {
    val major = state.recommendedJreMajor
    val jre = state.jres.firstOrNull { it.major == major }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, contentDescription = null, tint = javaHostStatusColors().success, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("JavaHost is ready", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                "You can change every option later from Settings. JavaHost ${BuildConfig.VERSION_NAME}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard(title = "Setup summary") {
            InfoRow(
                "Java runtime",
                if (jre != null) "Java ${jre.major} installed" else "Not installed — install before starting a server"
            )
            InfoRow(
                "Battery optimization",
                if (state.power.batteryOptimizationIgnored) "Exempt" else "Not exempt"
            )
            InfoRow(
                "Notifications",
                if (state.power.notificationsGranted || Build.VERSION.SDK_INT < 33) "Allowed" else "Not allowed"
            )
            InfoRow(
                "Planned server type",
                if (state.settings.planMinecraft) {
                    if (state.settings.minecraftEulaAcknowledged) "Minecraft (EULA accepted)" else "Minecraft (EULA pending)"
                } else {
                    "Not set — both Minecraft and generic Java servers are available"
                }
            )
        }
        SectionCard(title = "Next step") {
            Text(
                "Tap New server on the home screen, pick a .jar file, and JavaHost will inspect it, " +
                    "suggest a runtime and create a dedicated directory for it.",
                style = MaterialTheme.typography.bodyMedium
            )
            Bullet("Minecraft servers need a server JAR such as vanilla, Paper, Fabric or Forge.")
            Bullet("Other Java servers are Advanced / Experimental — JavaHost runs the JAR and shows its console, but gives no game-specific tooling.")
        }
        if (!state.jreReady) {
            MessageBanner(
                "No Java runtime is installed yet. You can finish setup, but install one before starting a server.",
                isError = false
            )
        }
    }
}
