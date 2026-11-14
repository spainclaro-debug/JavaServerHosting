package dev.javahost.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.javahost.core.model.GuideSection
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.PowerAction
import dev.javahost.ui.components.Bullet
import dev.javahost.ui.components.CodeLine
import dev.javahost.ui.components.InfoRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenContent
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.SuccessRow
import dev.javahost.ui.theme.Danger
import dev.javahost.ui.theme.TealGreen
import dev.javahost.ui.theme.WarnAmber

private const val DONT_KILL_MY_APP_URL = "https://dontkillmyapp.com"

/**
 * Honest power/reliability guidance. Android deliberately does not expose one
 * universal "keep my server alive" switch, so this screen explains the limits
 * and deep-links to the system screens that do exist on this device.
 */
@Composable
fun PowerGuideScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val sections: List<GuideSection> = remember { actions.guideSections() }
    val batteryExempt = state.power.batteryOptimizationIgnored
    val notificationsGranted = state.power.notificationsGranted

    ScreenScaffold(
        title = "Power & reliability",
        subtitle = "Keeping a server alive in the background",
        onBack = onBack
    ) { padding ->
        ScreenContent(padding) {
            state.message?.let { message ->
                MessageBanner(message.text, message.isError, actions::dismissMessage)
            }

            SectionCard(title = "Current status") {
                if (batteryExempt) {
                    SuccessRow("Battery optimization exemption is active")
                } else {
                    InfoRow("Battery optimization", "Not exempt", Danger)
                }
                InfoRow(
                    "Notifications",
                    if (notificationsGranted) "Allowed" else "Blocked",
                    if (notificationsGranted) TealGreen else Danger
                )
                InfoRow("Android API", state.power.androidSdk.toString())
                InfoRow(
                    "Phantom process limits",
                    if (state.power.phantomProcessKillerGuidanceNeeded) "Guidance recommended (Android 12+)"
                    else "Not applicable on this Android version"
                )
                if (!batteryExempt) {
                    Button(
                        onClick = { actions.openPowerAction(PowerAction.REQUEST_BATTERY_EXEMPTION) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Request battery exemption") }
                }
            }

            SectionCard(title = "Open the right system screen") {
                Text(
                    "Android's labels and locations differ between manufacturers. These buttons open " +
                        "the closest available system screen; if one is missing on your device the OS handles it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { actions.openPowerAction(PowerAction.OPEN_BATTERY_SETTINGS) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Battery settings") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_NOTIFICATION_SETTINGS) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Notifications") }
                    OutlinedButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_APP_DETAILS) },
                        modifier = Modifier.weight(1f)
                    ) { Text("App info") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_DEVELOPER_OPTIONS) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Developer options") }
                    OutlinedButton(
                        onClick = { uriHandler.openUri(DONT_KILL_MY_APP_URL) },
                        modifier = Modifier.weight(1f)
                    ) { Text("OEM advice") }
                }
                TextButton(onClick = { uriHandler.openUri(DONT_KILL_MY_APP_URL) }) {
                    Text("Open dontkillmyapp.com")
                }
            }

            SectionCard(title = "Why background servers stop") {
                Text("Doze and App Standby", style = MaterialTheme.typography.titleMedium)
                Bullet("When the screen is off and the phone is still, Android restricts background work. A foreground service with an ongoing notification is the supported way to keep a server process alive.")
                Text("Phantom Process Killer (Android 12+)", style = MaterialTheme.typography.titleMedium)
                Bullet("Android 12+ can kill additional child processes when memory is low. A hosted JVM is a child process, so a server can be killed without a Java exception.")
                Bullet("The reliable in-app mitigation is the foreground service JavaHost already uses. Some devices allow raising the limit via ADB; Android has no standard toggle.")
                Text("OEM battery savers", style = MaterialTheme.typography.titleMedium)
                Bullet("Samsung, Xiaomi, OnePlus, Huawei, OPPO and others add their own killers on top of Android. Their menus change between models and versions; JavaHost cannot guarantee a fixed path.")
                Bullet("The \"don't kill my app\" database links to per-manufacturer advice. Use the OEM advice button above.")
                Text("The media keep-alive hack", style = MaterialTheme.typography.titleMedium)
                Bullet("JavaHost has an optional, feature-flagged trick that holds an audio focus to look more like a media player. It is marginal: it can help on some ROMs, does nothing on others, and cannot defeat a strict battery saver.")
                InfoRow(
                    "Media hack in this build",
                    when {
                        !state.power.mediaHackFeatureEnabled -> "Feature flag off"
                        state.power.mediaHackSettingEnabled -> "Enabled in settings"
                        else -> "Available but off"
                    },
                    if (state.power.mediaHackFeatureEnabled) TealGreen else WarnAmber
                )
                Text(
                    "Configure it under Settings → Advanced. It is never required to run a server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            sections.forEach { section ->
                SectionCard(title = section.title, subtitle = section.intro.ifBlank { null }) {
                    section.steps.forEach { step ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(step.title, style = MaterialTheme.typography.labelLarge)
                            Bullet(step.body)
                        }
                    }
                    section.adbCommands.forEach { command -> CodeLine(command) }
                    section.helpUrl?.let { url ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text("Open related help") }
                    }
                }
            }

            SectionCard(title = "What JavaHost does for you") {
                Bullet("Runs servers in a foreground service with an ongoing notification.")
                Bullet("Saves crashes with the last output lines so you can see why a server stopped.")
                Bullet("Optionally restarts a crashed server automatically (Settings → Power & reliability).")
                Bullet("Shows live heap and process memory while a server is running.")
                Text(
                    "No app can guarantee that Android or an OEM battery saver will never kill a background " +
                        "process. Keep backups of your worlds, especially before long unattended runs.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
