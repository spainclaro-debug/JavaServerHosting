package dev.javahost.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.javahost.core.model.ExitInfo
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerInstance
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.CodeLine
import dev.javahost.ui.components.ConfirmDialog
import dev.javahost.ui.components.InfoRow
import dev.javahost.ui.components.LabeledField
import dev.javahost.ui.components.LabeledSlider
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenContent
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.StatePill
import dev.javahost.ui.components.Tag
import dev.javahost.ui.components.formatBytes
import dev.javahost.ui.components.formatMegabytes
import dev.javahost.ui.components.formatTimestamp
import dev.javahost.ui.components.formatUptime
import dev.javahost.ui.theme.Danger
import dev.javahost.ui.theme.WarnAmber

private const val DETAIL_EULA_URL = "https://aka.ms/MinecraftEULA"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    state: AppUiState,
    actions: AppActions,
    instance: ServerInstance,
    onBack: () -> Unit,
    onConsole: () -> Unit,
    onFiles: () -> Unit,
    onProperties: () -> Unit,
    onMods: () -> Unit,
    onOpenJre: () -> Unit
) {
    val live = state.instance(instance.id) ?: instance
    val runtime = state.runtime
    val uriHandler = LocalUriHandler.current
    val isThisActive = runtime.instanceId == live.id && runtime.isActive
    val running = isThisActive && runtime.state == RunState.RUNNING
    val jreInstalled = state.isInstalled(live.jreMajor)
    val eulaOk = !live.isMinecraft || live.eulaAccepted
    val anotherActive = runtime.isActive && runtime.instanceId != live.id
    val canStart = !isThisActive && !anotherActive && jreInstalled && eulaOk

    var showDelete by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showForceStop by remember { mutableStateOf(false) }

    val safeMaxMb = maxOf(512, (state.device.safeMaxHeapMb / 128) * 128)
    val autoHeapMb = state.device.autoHeapMb.coerceIn(256, safeMaxMb)

    var jvmArgs by remember(live.id, live.jvmArgs) { mutableStateOf(live.jvmArgs) }
    var serverArgs by remember(live.id, live.serverArgs) { mutableStateOf(live.serverArgs) }
    var memoryMb by remember(live.id, live.memoryMb) { mutableStateOf(live.memoryMb) }
    var autoMemory by remember(live.id, live.autoMemory) { mutableStateOf(live.autoMemory) }

    val crashInfo: ExitInfo? = state.lastCrash?.takeIf { crash ->
        (live.lastExitAt != null && crash.timestamp == live.lastExitAt) ||
            (runtime.instanceId == live.id && !crash.expected) ||
            (state.activeInstanceId == live.id && !crash.expected)
    }
    val showCrash = live.lastCrashSummary != null || (crashInfo != null && !crashInfo.expected)

    ScreenScaffold(
        title = live.name,
        subtitle = "${mainFlavorLabel(live.flavor)} · Java ${live.jreMajor}",
        onBack = onBack,
        topActions = {
            IconButton(onClick = { showRename = true }) {
                Icon(Icons.Default.Edit, contentDescription = "Rename server")
            }
        }
    ) { padding ->
        ScreenContent(padding) {
            state.message?.let { message ->
                MessageBanner(message.text, message.isError, actions::dismissMessage)
            }

            /* ---------------- Status + controls ---------------- */
            SectionCard(
                title = "Status",
                trailing = {
                    StatePill(
                        if (isThisActive) mainRunStateLabel(runtime.state) else mainRunStateLabel(RunState.STOPPED),
                        if (isThisActive) runtime.state else RunState.STOPPED
                    )
                }
            ) {
                if (isThisActive) {
                    InfoRow("Uptime", formatUptime(runtime.uptimeMs))
                    if (live.isMinecraft) {
                        InfoRow(
                            "Players",
                            if (runtime.playersMax > 0) "${runtime.playersOnline} / ${runtime.playersMax}"
                            else "${runtime.playersOnline}"
                        )
                    } else {
                        InfoRow("Port", runtime.serverPort?.toString() ?: "not reported")
                        InfoRow("Connections", runtime.connectionsOnline?.toString() ?: "n/a")
                    }
                    runtime.heapUsedMb?.let { used ->
                        InfoRow(
                            "Heap",
                            buildString {
                                append("$used MB")
                                runtime.heapMaxMb?.let { max -> append(" / $max MB") }
                            }
                        )
                    }
                    runtime.processRssMb?.let { rss ->
                        InfoRow("Process memory", formatBytes(rss * 1024L * 1024L))
                    }
                    runtime.pid?.let { pid -> InfoRow("PID", pid.toString()) }
                    if (runtime.deviceAvailMb > 0) {
                        InfoRow("Device memory free", formatMegabytes(runtime.deviceAvailMb.toInt()))
                    }
                } else {
                    InfoRow("Memory", formatMegabytes(live.memoryMb))
                    InfoRow("Java runtime", if (jreInstalled) "Java ${live.jreMajor}" else "Java ${live.jreMajor} — not installed")
                    if (live.serverPort != null) InfoRow("Port", live.serverPort.toString())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isThisActive) {
                        Button(
                            onClick = { actions.stopServer(false) },
                            modifier = Modifier.weight(1f),
                            enabled = runtime.state != RunState.STOPPING
                        ) { Text("Stop") }
                    } else {
                        Button(
                            onClick = { actions.startServer(live.id) },
                            modifier = Modifier.weight(1f),
                            enabled = canStart
                        ) { Text("Start") }
                    }
                    OutlinedButton(
                        onClick = { actions.restartServer() },
                        modifier = Modifier.weight(1f),
                        enabled = isThisActive
                    ) { Text("Restart") }
                }
                if (isThisActive) {
                    TextButton(
                        onClick = { showForceStop = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Force stop (use if shutdown is stuck)") }
                }
                if (!isThisActive) {
                    val reason = when {
                        anotherActive -> "Another server is running. Only one JVM is hosted at a time."
                        !jreInstalled -> "Install Java ${live.jreMajor} before starting this server."
                        !eulaOk -> "Accept the Minecraft EULA below before starting."
                        else -> null
                    }
                    if (reason != null) {
                        Text(reason, style = MaterialTheme.typography.labelSmall, color = WarnAmber)
                    }
                }
                if (running) {
                    TextButton(onClick = onConsole) { Text("Open live console") }
                }
            }

            if (!jreInstalled) {
                MessageBanner("Java ${live.jreMajor} is not installed. Servers cannot start until it is.", isError = true)
                OutlinedButton(onClick = onOpenJre, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Java runtimes")
                }
            }

            /* ---------------- Crash post-mortem ---------------- */
            if (showCrash) {
                SectionCard(
                    title = "Last run failed",
                    subtitle = live.lastCrashSummary ?: crashInfo?.summary?.ifBlank { null } ?: "The server exited unexpectedly",
                    trailing = { Tag("Crash", Danger) }
                ) {
                    crashInfo?.let { crash ->
                        InfoRow("Reason", mainExitReasonLabel(crash.reason))
                        crash.exitCode?.let { code -> InfoRow("Exit code", code.toString()) }
                        crash.signal?.let { signal -> InfoRow("Signal", signal.toString()) }
                        if (crash.tail.isNotEmpty()) {
                            Text("Last output", style = MaterialTheme.typography.labelLarge)
                            crash.tail.takeLast(12).forEach { line -> CodeLine(line) }
                        }
                        if (crash.reportPath != null) {
                            InfoRow("Crash report", crash.reportPath)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { actions.startServer(live.id) },
                            modifier = Modifier.weight(1f),
                            enabled = jreInstalled && eulaOk && !anotherActive
                        ) { Text("Restart") }
                        OutlinedButton(onClick = onConsole, modifier = Modifier.weight(1f)) {
                            Text("Console")
                        }
                    }
                    TextButton(onClick = { actions.dismissCrash() }) { Text("Dismiss report") }
                }
            }

            /* ---------------- Minecraft EULA ---------------- */
            if (live.isMinecraft && !live.eulaAccepted) {
                SectionCard(title = "Minecraft EULA required") {
                    Text(
                        "Minecraft servers require accepting Mojang's EULA. JavaHost writes eula=true for this " +
                            "instance only after you accept.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { uriHandler.openUri(DETAIL_EULA_URL) }) {
                        Text("Open the Minecraft EULA (aka.ms/MinecraftEULA)")
                    }
                    Button(
                        onClick = { actions.acceptEula(live.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Accept EULA for this server") }
                }
            }

            /* ---------------- Tools ---------------- */
            SectionCard(title = "Tools") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onConsole, modifier = Modifier.weight(1f)) { Text("Console") }
                    OutlinedButton(onClick = onFiles, modifier = Modifier.weight(1f)) { Text("Files") }
                }
                if (live.isMinecraft) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onProperties, modifier = Modifier.weight(1f)) {
                            Text("server.properties")
                        }
                        OutlinedButton(onClick = onMods, modifier = Modifier.weight(1f)) { Text("Mods") }
                    }
                } else {
                    Text(
                        "Minecraft-only tools (server.properties, mods, plugins) are hidden for generic Java servers.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            /* ---------------- Memory ---------------- */
            SectionCard(title = "Memory") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic memory", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "JavaHost picks ${formatMegabytes(autoHeapMb)} from device RAM. " +
                                "Safe ceiling: ${formatMegabytes(safeMaxMb)}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoMemory,
                        onCheckedChange = { checked ->
                            autoMemory = checked
                            if (checked) memoryMb = autoHeapMb
                        }
                    )
                }
                LabeledSlider(
                    label = "Heap size",
                    value = if (autoMemory) live.memoryMb else memoryMb,
                    range = 256..safeMaxMb,
                    step = 128,
                    enabled = !autoMemory,
                    onValueChange = { memoryMb = it }
                )
                if (!autoMemory && memoryMb > state.device.autoHeapMb) {
                    Text(
                        "Above the automatic pick. This device's safe ceiling is ${formatMegabytes(safeMaxMb)}; " +
                            "Android may still kill the process under memory pressure.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            actions.setInstanceMemory(
                                live.id,
                                if (autoMemory) autoHeapMb else memoryMb,
                                autoMemory
                            )
                        },
                        enabled = autoMemory != live.autoMemory || memoryMb != live.memoryMb,
                        modifier = Modifier.weight(1f)
                    ) { Text("Apply memory") }
                    Text(
                        "Used on next start",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            /* ---------------- Runtime ---------------- */
            SectionCard(title = "Java runtime") {
                JavaMajorPicker(
                    label = "Java version",
                    selected = live.jreMajor,
                    majors = state.supportedJreMajors,
                    installedMajors = state.jres.map { it.major }.toSet(),
                    onSelect = { actions.setInstanceJre(live.id, it) }
                )
                if (live.jreMajor != state.settings.defaultJreMajor) {
                    Text(
                        "Differs from the default runtime (Java ${state.settings.defaultJreMajor}).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Restart after a crash", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Automatically start this server again if it exits unexpectedly.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = live.autoRestart,
                        onCheckedChange = { actions.setInstanceAutoRestart(live.id, it) }
                    )
                }
            }

            /* ---------------- Arguments ---------------- */
            SectionCard(title = "Arguments", subtitle = "Applied the next time the server starts") {
                LabeledField(
                    label = "JVM arguments (before -jar)",
                    value = jvmArgs,
                    onValueChange = { jvmArgs = it },
                    singleLine = false,
                    supportingText = "Example: -XX:+UseG1GC -Dfile.encoding=UTF-8"
                )
                Button(
                    onClick = { actions.setInstanceJvmArgs(live.id, jvmArgs.trim()) },
                    enabled = jvmArgs.trim() != live.jvmArgs,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save JVM arguments") }
                LabeledField(
                    label = "Server arguments (after the JAR)",
                    value = serverArgs,
                    onValueChange = { serverArgs = it },
                    singleLine = false,
                    supportingText = if (live.isMinecraft) "Minecraft normally uses \"nogui\"." else "Passed to the server's main class."
                )
                Button(
                    onClick = { actions.setInstanceArgs(live.id, serverArgs.trim()) },
                    enabled = serverArgs.trim() != live.serverArgs,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save server arguments") }
            }

            /* ---------------- Instance info ---------------- */
            SectionCard(title = "Instance") {
                InfoRow("JAR file", live.jarFile)
                InfoRow("Type", mainFlavorLabel(live.flavor))
                if (live.isMinecraft) {
                    InfoRow("Minecraft version", live.minecraftVersion ?: "—")
                }
                InfoRow("Created", if (live.createdAt > 0) formatTimestamp(live.createdAt) else "Unknown")
                if (live.lastExitCode != null) InfoRow("Last exit code", live.lastExitCode.toString())
                if (live.totalRuntimeMs > 0) InfoRow("Total runtime", formatUptime(live.totalRuntimeMs))
                Text(
                    "Only one JVM runs at a time. Stopping a server lets it save its world before exiting.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            /* ---------------- Danger zone ---------------- */
            SectionCard(title = "Delete server") {
                Text(
                    "Deletes the server directory from app storage, including its world, logs and " +
                        "configuration. The .jar file you originally picked is not modified. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { showDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete server") }
            }
        }
    }

    if (showForceStop) {
        ConfirmDialog(
            title = "Force-stop the server?",
            text = "The JVM is killed immediately. The world may not finish saving, and plugins " +
                "will be shut down without their normal stop hooks. Use this only when a normal " +
                "Stop has not completed.",
            confirmLabel = "Force stop",
            destructive = true,
            onConfirm = { actions.forceStopServer() },
            onDismiss = { showForceStop = false }
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "Delete ${live.name}?",
            text = "This permanently removes the server and all of its files from app storage. " +
                "If the server is running it will be stopped first.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                actions.deleteInstance(live.id)
                onBack()
            },
            onDismiss = { showDelete = false }
        )
    }

    if (showRename) {
        var newName by remember(live.id) { mutableStateOf(live.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename server") },
            text = {
                LabeledField(
                    label = "Name",
                    value = newName,
                    onValueChange = { newName = it },
                    supportingText = "Shown on the home screen and in notifications."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.renameInstance(live.id, newName.trim())
                        showRename = false
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            }
        )
    }
}
