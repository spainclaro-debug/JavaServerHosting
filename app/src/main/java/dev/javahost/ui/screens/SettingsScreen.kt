package dev.javahost.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.javahost.BuildConfig
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.PowerAction
import dev.javahost.ui.components.Bullet
import dev.javahost.ui.components.CodeLine
import dev.javahost.ui.components.InfoRow
import dev.javahost.ui.components.LabeledField
import dev.javahost.ui.components.LabeledSlider
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenContent
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.SuccessRow
import dev.javahost.ui.components.formatMegabytes
import dev.javahost.ui.theme.TealGreen
import dev.javahost.ui.theme.WarnAmber

@Composable
fun SettingsScreen(
    state: AppUiState,
    actions: AppActions,
    onBack: () -> Unit,
    onOpenJre: () -> Unit,
    onOpenPowerGuide: () -> Unit
) {
    val settings = state.settings
    val device = state.device
    val safeMaxMb = maxOf(512, (device.safeMaxHeapMb / 128) * 128)
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> actions.onNotificationPermissionResult(granted) }

    var mirror by remember(settings.termuxMirror) { mutableStateOf(settings.termuxMirror) }
    val notificationsGranted = state.power.notificationsGranted || Build.VERSION.SDK_INT < 33

    ScreenScaffold(
        title = "Settings",
        subtitle = "JavaHost ${BuildConfig.VERSION_NAME}",
        onBack = onBack
    ) { padding ->
        ScreenContent(padding) {
            state.message?.let { message ->
                MessageBanner(message.text, message.isError, actions::dismissMessage)
            }

            /* ---------------- Runtime defaults ---------------- */
            SectionCard(title = "Runtime defaults", subtitle = "Used for new servers") {
                JavaMajorPicker(
                    label = "Default Java runtime",
                    selected = settings.defaultJreMajor,
                    majors = state.supportedJreMajors,
                    installedMajors = state.jres.map { it.major }.toSet(),
                    onSelect = actions::setDefaultJre
                )
                if (!state.isInstalled(settings.defaultJreMajor)) {
                    Text(
                        "Java ${settings.defaultJreMajor} is not installed. New servers will need it before they can start.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic memory", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Pick a safe heap for each new server from device RAM. " +
                                "Recommendation: ${formatMegabytes(device.autoHeapMb)}; ceiling ${formatMegabytes(safeMaxMb)}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoMemoryByDefault,
                        onCheckedChange = actions::setAutoMemoryDefault
                    )
                }
                LabeledSlider(
                    label = "Default heap",
                    value = settings.defaultMemoryMb.coerceIn(256, safeMaxMb),
                    range = 256..safeMaxMb,
                    step = 128,
                    enabled = !settings.autoMemoryByDefault,
                    supportingText = if (settings.autoMemoryByDefault) {
                        "Automatic memory is on; this value is used when you turn it off."
                    } else {
                        "Sliders are capped at this device's safe ceiling."
                    },
                    onValueChange = actions::setDefaultMemory
                )
                OutlinedButton(onClick = onOpenJre, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage Java runtimes")
                }
            }

            /* ---------------- Reliability ---------------- */
            SectionCard(title = "Power & reliability", subtitle = "Keep servers alive in the background") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Restart servers after a crash", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "New instances inherit this default (existing instances have their own switch).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoRestartOnCrash,
                        onCheckedChange = actions::setAutoRestart
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                if (state.power.batteryOptimizationIgnored) {
                    SuccessRow("Battery optimization exemption is active")
                } else {
                    InfoRow("Battery optimization", "Not exempt — Android may pause the server")
                }
                Button(
                    onClick = { actions.openPowerAction(PowerAction.REQUEST_BATTERY_EXEMPTION) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Request battery exemption") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_BATTERY_SETTINGS) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Battery settings") }
                    OutlinedButton(
                        onClick = onOpenPowerGuide,
                        modifier = Modifier.weight(1f)
                    ) { Text("Full power guide") }
                }
            }

            /* ---------------- Notifications ---------------- */
            SectionCard(title = "Notifications") {
                if (notificationsGranted) {
                    SuccessRow("Notifications are allowed")
                    TextButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_NOTIFICATION_SETTINGS) }
                    ) { Text("Open notification settings") }
                } else {
                    Text(
                        "Android 13+ requires permission for notifications. JavaHost runs a foreground " +
                            "service when a server is active; the notification shows its status.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                actions.onNotificationPermissionResult(true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow notifications") }
                    TextButton(
                        onClick = { actions.openPowerAction(PowerAction.OPEN_NOTIFICATION_SETTINGS) }
                    ) { Text("Open notification settings") }
                }
            }

            /* ---------------- Advanced ---------------- */
            SectionCard(
                title = "Advanced",
                subtitle = "For experienced users"
            ) {
                LabeledField(
                    label = "Termux package mirror",
                    value = mirror,
                    onValueChange = { mirror = it },
                    supportingText = "Optional. Leave empty to use the built-in mirrors. Only used for Java runtime downloads.",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { actions.setTermuxMirror(mirror.trim()) },
                        enabled = mirror.trim() != settings.termuxMirror,
                        modifier = Modifier.weight(1f)
                    ) { Text("Save mirror") }
                    TextButton(
                        onClick = {
                            mirror = ""
                            actions.setTermuxMirror("")
                        },
                        enabled = mirror.isNotEmpty() || settings.termuxMirror.isNotEmpty()
                    ) { Text("Reset") }
                }
                InfoRow("Built-in primary", BuildConfig.TERMUX_PRIMARY_MIRROR)
                InfoRow("Built-in fallback", BuildConfig.TERMUX_FALLBACK_MIRROR)

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Silent media keep-alive", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (BuildConfig.ENABLE_SILENT_MEDIA_HACK) {
                                "Plays a near-silent loop without taking audio focus to discourage aggressive process reapers. " +
                                    "The toggle applies to a running server within ~2 seconds. This is a marginal " +
                                    "workaround and not a guarantee."
                            } else {
                                "Feature flag off in this build (ENABLE_SILENT_MEDIA_HACK). " +
                                    "Even when available this is a marginal hack that cannot defeat " +
                                    "aggressive battery savers; it is never required."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.silentMediaHack && BuildConfig.ENABLE_SILENT_MEDIA_HACK,
                        onCheckedChange = if (BuildConfig.ENABLE_SILENT_MEDIA_HACK) {
                            actions::setSilentMediaHack
                        } else {
                            null
                        },
                        enabled = BuildConfig.ENABLE_SILENT_MEDIA_HACK
                    )
                }
                if (!BuildConfig.ENABLE_SILENT_MEDIA_HACK) {
                    Text(
                        "Feature flag: ${if (state.power.mediaHackFeatureEnabled) "on" else "off"}. " +
                            "This build ships with the flag off, so the option is disabled.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            /* ---------------- About ---------------- */
            SectionCard(title = "About JavaHost") {
                InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("Telemetry", "None — no analytics, accounts or tracking")
                InfoRow("Java runtime", "Not bundled; downloaded on request")
                InfoRow("Support", "Community project — no warranty")
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Text(
                    "JavaHost runs arbitrary Java server JARs in a foreground service. Minecraft is " +
                        "supported first-class; everything else is Advanced / Experimental.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Bullet("Sideload only: JavaHost targets Android 9 (API 28) so downloaded runtimes can be executed from app-private storage. That profile is not suitable for Google Play distribution.")
                Bullet("Minecraft is not included. You must supply your own server JAR and comply with Mojang's EULA.")
                Bullet("Java runtimes come from Termux's OpenJDK packages (GPLv2 with Classpath Exception) and are downloaded at your request.")
                Bullet("Modrinth API is used for mod/plugin search and downloads.")
                Bullet("TinyMCServer (AGPL-3.0) was consulted as an architectural reference for hosting a JVM on Android; no code was copied.")
                Bullet("Other third-party components: OkHttp (Apache 2.0), kotlinx.serialization (Apache 2.0), Apache Commons Compress (Apache 2.0), XZ for Java (public domain).")
            }
            SectionCard(title = "Licenses") {
                Text(
                    "JavaHost itself is distributed as a community project. Full third-party license texts " +
                        "ship with the project source. Android's open-source notices are available in " +
                        "system settings → About phone → Legal information.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CodeLine("OpenJDK — GPLv2 with Classpath Exception")
                CodeLine("OkHttp / kotlinx.serialization / Commons Compress — Apache License 2.0")
                CodeLine("XZ for Java — public domain")
                CodeLine("Modrinth API — used over HTTPS; see modrinth.com/legal")
            }
        }
    }
}
