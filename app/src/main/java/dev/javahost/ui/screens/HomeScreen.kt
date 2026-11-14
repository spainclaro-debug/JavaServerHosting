package dev.javahost.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.javahost.core.model.ExitReason
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerInstance
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.CodeLine
import dev.javahost.ui.components.EmptyState
import dev.javahost.ui.components.InfoRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.StatePill
import dev.javahost.ui.components.Tag
import dev.javahost.ui.components.formatBytes
import dev.javahost.ui.components.formatMegabytes
import dev.javahost.ui.components.formatUptime
import dev.javahost.ui.theme.Danger
import dev.javahost.ui.theme.WarnAmber

/* -------------------------------------------------------------------------- */
/* Shared, short display labels (used by the Detail screen too)               */
/* -------------------------------------------------------------------------- */

internal fun mainFlavorLabel(flavor: ServerFlavor): String = when (flavor) {
    ServerFlavor.VANILLA -> "Vanilla"
    ServerFlavor.PAPER -> "Paper / Spigot"
    ServerFlavor.FABRIC -> "Fabric"
    ServerFlavor.FORGE -> "Forge"
    ServerFlavor.BUKKIT -> "Bukkit"
    ServerFlavor.OTHER_MINECRAFT -> "Minecraft"
    ServerFlavor.GENERIC -> "Generic Java"
}

internal fun mainRunStateLabel(state: RunState): String = when (state) {
    RunState.STOPPED -> "Stopped"
    RunState.STARTING -> "Starting"
    RunState.RUNNING -> "Running"
    RunState.STOPPING -> "Stopping"
    RunState.CRASHED -> "Crashed"
}

internal fun mainExitReasonLabel(reason: ExitReason): String = when (reason) {
    ExitReason.STOPPED_BY_USER -> "Stopped by user"
    ExitReason.CRASH_OUT_OF_MEMORY -> "Out of memory"
    ExitReason.CRASH_PORT_IN_USE -> "Port already in use"
    ExitReason.CRASH_UNSUPPORTED_JAVA -> "Unsupported Java version"
    ExitReason.CRASH_MISSING_LIBRARY -> "Missing library"
    ExitReason.CRASH_EULA_REJECTED -> "Minecraft EULA not accepted"
    ExitReason.KILLED_BY_SYSTEM -> "Killed by Android"
    ExitReason.CRASH_UNKNOWN -> "Unknown error"
}

/* -------------------------------------------------------------------------- */
/* Home                                                                        */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppUiState,
    actions: AppActions,
    onOpenInstance: (String) -> Unit,
    onCreate: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenJre: () -> Unit,
    onOpenPowerGuide: () -> Unit,
    onOpenConsole: (String) -> Unit
) {
    val crash = state.lastCrash
    val crashInstance = state.instance(state.activeInstanceId ?: state.settings.lastActiveInstanceId)
    val active = state.activeInstance

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("JavaHost", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Java server host",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenJre) {
                        Icon(Icons.Default.Build, contentDescription = "Java runtimes")
                    }
                    IconButton(onClick = onOpenPowerGuide) {
                        Icon(Icons.Default.Notifications, contentDescription = "Power and reliability guide")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New server") }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.message?.let { message ->
                item {
                    MessageBanner(message.text, message.isError, actions::dismissMessage)
                }
            }

            if (!state.jreReady) {
                item {
                    SectionCard(
                        title = "Java runtime missing",
                        subtitle = "Required before a server can start"
                    ) {
                        Text(
                            "No Java runtime is installed. Download Java ${state.recommendedJreMajor} " +
                                "(≈106 MB from Termux packages, checksum verified) once, then start any server.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = onOpenJre, modifier = Modifier.fillMaxWidth()) {
                            Text("Open Java runtimes")
                        }
                    }
                }
            }

            if (crash != null) {
                item {
                    CrashBannerCard(
                        crash = crash,
                        crashInstance = crashInstance,
                        onRestart = {
                            if (crashInstance != null) actions.startServer(crashInstance.id)
                            else actions.restartServer()
                        },
                        onDetails = crashInstance?.let { instance -> { onOpenInstance(instance.id) } },
                        onDismiss = actions::dismissCrash
                    )
                }
            }

            if (active != null && state.runtime.isActive) {
                item {
                    ActiveServerCard(
                        state = state,
                        instance = active,
                        onOpenConsole = { onOpenConsole(active.id) },
                        onOpenDetails = { onOpenInstance(active.id) },
                        actions = actions
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.instances.isEmpty()) "Servers" else "Servers (${state.instances.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.activeInstanceId != null) {
                        Tag("1 JVM limit", WarnAmber)
                    }
                }
            }

            if (state.instances.isEmpty()) {
                item {
                    EmptyState(
                        title = "No servers yet",
                        body = "Pick a server .jar you already have — Minecraft server JARs are first-class, " +
                            "and other Java servers run in Advanced / Experimental mode. JavaHost copies the file " +
                            "into app-private storage and keeps the original untouched.",
                        actionLabel = "New server",
                        onAction = onCreate
                    )
                }
            } else {
                items(state.instances, key = { it.id }) { instance ->
                    InstanceCard(
                        state = state,
                        instance = instance,
                        onOpen = { onOpenInstance(instance.id) },
                        onStart = { actions.startServer(instance.id) },
                        onStop = { actions.stopServer(false) },
                        onOpenJre = onOpenJre
                    )
                }
            }

            item {
                DeviceCard(
                    state = state,
                    onOpenJre = onOpenJre,
                    onOpenPowerGuide = onOpenPowerGuide,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun CrashBannerCard(
    crash: dev.javahost.core.model.ExitInfo,
    crashInstance: ServerInstance?,
    onRestart: () -> Unit,
    onDetails: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    SectionCard(
        title = "Server stopped unexpectedly",
        subtitle = crash.summary.ifBlank { mainExitReasonLabel(crash.reason) },
        trailing = { Tag("Crash", Danger) }
    ) {
        if (crash.tail.isNotEmpty()) {
            crash.tail.takeLast(3).forEach { line -> CodeLine(line) }
        }
        InfoRow("Reason", mainExitReasonLabel(crash.reason))
        if (crash.reportPath != null) {
            InfoRow("Crash report", crash.reportPath)
        }
        if (crashInstance == null) {
            Text(
                "The instance for this exit no longer exists.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (crashInstance != null) {
                Button(onClick = onRestart, modifier = Modifier.weight(1f)) { Text("Restart") }
            }
            if (onDetails != null) {
                OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("Details") }
            }
        }
        TextButton(onClick = onDismiss) { Text("Dismiss report") }
    }
}

@Composable
private fun ActiveServerCard(
    state: AppUiState,
    instance: ServerInstance,
    actions: AppActions,
    onOpenConsole: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val runtime = state.runtime
    SectionCard(
        title = instance.name,
        subtitle = "Active server · ${mainFlavorLabel(instance.flavor)}",
        trailing = { StatePill(mainRunStateLabel(runtime.state), runtime.state) }
    ) {
        InfoRow("Uptime", formatUptime(runtime.uptimeMs))
        if (instance.isMinecraft) {
            InfoRow(
                "Players",
                if (runtime.playersMax > 0) "${runtime.playersOnline} / ${runtime.playersMax}"
                else "${runtime.playersOnline}"
            )
        } else {
            InfoRow("Port", runtime.serverPort?.toString() ?: "not reported")
            InfoRow(
                "Connections",
                runtime.connectionsOnline?.toString() ?: "n/a"
            )
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
        runtime.processRssMb?.let { rss -> InfoRow("Process memory", formatBytes(rss * 1024L * 1024L)) }
        runtime.pid?.let { pid -> InfoRow("PID", pid.toString()) }
        if (runtime.deviceAvailMb > 0) {
            InfoRow("Device memory free", formatMegabytes(runtime.deviceAvailMb.toInt()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenConsole, modifier = Modifier.weight(1f)) { Text("Console") }
            if (runtime.state != RunState.STOPPING) {
                Button(onClick = { actions.stopServer(false) }, modifier = Modifier.weight(1f)) {
                    Text("Stop")
                }
            }
        }
        TextButton(onClick = onOpenDetails) { Text("Open server details") }
    }
}

@Composable
private fun InstanceCard(
    state: AppUiState,
    instance: ServerInstance,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenJre: () -> Unit
) {
    val runtime = state.runtime
    val isThisActive = runtime.instanceId == instance.id && runtime.isActive
    val anyActive = runtime.isActive
    val jreInstalled = state.isInstalled(instance.jreMajor)
    val eulaOk = !instance.isMinecraft || instance.eulaAccepted
    val anotherServerActive = anyActive && runtime.instanceId != instance.id
    val canStart = !isThisActive && !anotherServerActive && jreInstalled && eulaOk
    val crashSummary = instance.lastCrashSummary
    val stateForPill = when {
        isThisActive -> runtime.state
        crashSummary != null -> RunState.CRASHED
        else -> RunState.STOPPED
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        instance.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(mainFlavorLabel(instance.flavor))
                        Tag("Java ${instance.jreMajor}")
                        if (!instance.isMinecraft) Tag("Experimental", WarnAmber)
                    }
                }
                StatePill(
                    mainRunStateLabel(stateForPill),
                    stateForPill
                )
            }
            InfoRow(
                "Memory",
                if (instance.autoMemory) "${formatMegabytes(instance.memoryMb)} (auto)"
                else formatMegabytes(instance.memoryMb)
            )
            InfoRow(
                "Java runtime",
                if (jreInstalled) "Java ${instance.jreMajor} installed"
                else "Java ${instance.jreMajor} — not installed"
            )
            if (crashSummary != null) {
                MessageBanner("Last run: $crashSummary", isError = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isThisActive) {
                    Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
                } else {
                    Button(onClick = onStart, modifier = Modifier.weight(1f), enabled = canStart) {
                        Text("Start")
                    }
                }
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Details") }
                if (!jreInstalled) {
                    TextButton(onClick = onOpenJre) { Text("Get Java") }
                }
            }
            when {
                anotherServerActive -> Text(
                    "Another server is running. Only one JVM is hosted at a time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                !jreInstalled -> Text(
                    "Install Java ${instance.jreMajor} before starting this server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber
                )
                !eulaOk -> Text(
                    "Accept the Minecraft EULA in server details before starting.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarnAmber
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    state: AppUiState,
    onOpenJre: () -> Unit,
    onOpenPowerGuide: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val device = state.device
    SectionCard(title = "This device", subtitle = "Memory, runtime and reliability") {
        InfoRow(
            "Memory",
            if (device.totalRamMb > 0) {
                "${formatMegabytes(device.availRamMb.toInt())} free of ${formatMegabytes(device.totalRamMb.toInt())}"
            } else "Detecting…"
        )
        InfoRow("Safe heap ceiling", formatMegabytes(device.safeMaxHeapMb))
        InfoRow(
            "Java runtime",
            if (state.jreReady) "Java ${state.recommendedJreMajor} installed"
            else "Not installed"
        )
        InfoRow("CPU", "${device.coreCount} cores · ${device.abi.ifBlank { "unknown ABI" }}")
        if (device.lowRamDevice) {
            MessageBanner(
                "Low-RAM device: keep server memory at or below ${formatMegabytes(device.safeMaxHeapMb)}.",
                isError = true
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenJre, modifier = Modifier.weight(1f)) { Text("Runtimes") }
            OutlinedButton(onClick = onOpenPowerGuide, modifier = Modifier.weight(1f)) { Text("Power guide") }
        }
        TextButton(onClick = onOpenSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("Open settings")
        }
    }
}
