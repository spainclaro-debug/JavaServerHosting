package dev.javahost.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.javahost.core.model.ConsoleEntry
import dev.javahost.core.model.LogLevel
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.model.ServerRuntimeStatus
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.ConfirmDialog
import dev.javahost.ui.components.ConsoleEmptyState
import dev.javahost.ui.components.ConsoleLevelFilter
import dev.javahost.ui.components.ConsoleLineRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.StatePill
import dev.javahost.ui.components.TerminalBackground
import dev.javahost.ui.components.formatMegabytes
import dev.javahost.ui.components.formatUptime
import dev.javahost.ui.components.readableMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rendering is capped so a chatty server cannot blow up the LazyColumn. */
private const val MAX_RENDERED_LINES = 2000

private data class ConsoleNotice(val text: String, val isError: Boolean = false)

/* -------------------------------------------------------------------------- */
/* Screen                                                                      */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    state: AppUiState,
    actions: AppActions,
    instance: ServerInstance,
    entries: List<ConsoleEntry>,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var levelFilter by remember(instance.id) { mutableStateOf<LogLevel?>(null) }
    var query by remember(instance.id) { mutableStateOf("") }
    var autoScroll by remember(instance.id) { mutableStateOf(true) }
    var showTimestamps by remember(instance.id) { mutableStateOf(true) }
    var command by remember(instance.id) { mutableStateOf("") }
    var stopConfirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConsoleNotice?>(null) }
    var history by remember(instance.id) { mutableStateOf<List<ConsoleEntry>>(emptyList()) }

    val runtime = state.runtime
    val attached = runtime.instanceId == instance.id
    val running = attached && runtime.state == RunState.RUNNING
    val shownState = if (attached) runtime.state else RunState.STOPPED

    // When no live session is attached to this instance, load the app-owned
    // transcript from disk so a stopped/crashed server still has a readable
    // console after a process restart.
    LaunchedEffect(instance.id, attached, entries.isEmpty()) {
        if (!attached && entries.isEmpty()) {
            val lines = actions.readStartupTail(instance.id, 800)
            history = lines.mapIndexed { index, line ->
                ConsoleEntry(seq = Long.MIN_VALUE + index, timestamp = 0L, level = LogLevel.UNKNOWN, text = line)
            }
        } else if (entries.isNotEmpty()) {
            history = emptyList()
        }
    }
    val displayEntries = if (entries.isEmpty()) history else entries

    val filtered = remember(displayEntries, levelFilter, query) {
        val needle = query.trim()
        displayEntries.filter { entry ->
            (levelFilter == null || entry.level == levelFilter) &&
                (needle.isEmpty() || entry.text.contains(needle, ignoreCase = true))
        }
    }
    val rendered = remember(filtered) {
        if (filtered.size > MAX_RENDERED_LINES) filtered.takeLast(MAX_RENDERED_LINES) else filtered
    }

    // Follow the newest line only while auto-scroll is on, and never while the
    // user is actively dragging the list.
    LaunchedEffect(rendered.size, rendered.firstOrNull()?.seq, autoScroll) {
        if (autoScroll && rendered.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(rendered.lastIndex)
        }
    }

    // When a real user scroll settles, auto-scroll mirrors whether the bottom is
    // visible: scrolling up turns it off, scrolling back down turns it on.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                autoScroll = info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 1
            }
        }
    }

    // --- actions ------------------------------------------------------------
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            try {
                actions.exportConsole(uri)
                notice = ConsoleNotice("Console log exported.")
            } catch (t: Throwable) {
                notice = ConsoleNotice("Export failed: ${t.readableMessage()}", isError = true)
            }
        }
    }

    val exportConsole: () -> Unit = {
        if (entries.isEmpty()) {
            notice = ConsoleNotice("There is nothing to export yet.", isError = true)
        } else {
            exportLauncher.launch("${safeFileStem(instance.name)}-console-${fileTimestamp()}.log")
        }
    }

    val sendCommand: (String) -> Unit = { raw ->
        val cmd = raw.trim()
        if (cmd.isNotEmpty()) {
            when {
                !running -> notice = ConsoleNotice(
                    "The server is not running, so commands cannot be sent.",
                    isError = true
                )
                else -> try {
                    actions.sendCommand(cmd)
                    command = ""
                    notice = null
                } catch (t: Throwable) {
                    notice = ConsoleNotice("Could not send command: ${t.readableMessage()}", isError = true)
                }
            }
        }
    }

    val jumpToLatest: () -> Unit = {
        autoScroll = true
        scope.launch {
            if (rendered.isNotEmpty()) {
                listState.scrollToItem(rendered.lastIndex)
            }
        }
    }

    val stateLabel = runStateLabel(shownState)

    // --- layout -------------------------------------------------------------
    ScreenScaffold(
        title = instance.name,
        subtitle = "Console · $stateLabel",
        onBack = onBack,
        topActions = {
            IconButton(onClick = onOpenFiles) {
                Icon(Icons.Default.List, contentDescription = "Browse instance files")
            }
            IconButton(onClick = exportConsole, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.Share, contentDescription = "Export console log")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            ConsoleStatusStrip(runtime = runtime, attached = attached, shownState = shownState, isMinecraft = instance.isMinecraft)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search console lines") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                ConsoleLevelFilter(selected = levelFilter, onSelect = { levelFilter = it })
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = autoScroll,
                        onClick = { autoScroll = !autoScroll },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (autoScroll) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text("Auto-scroll", fontSize = 11.sp)
                            }
                        }
                    )
                    FilterChip(
                        selected = showTimestamps,
                        onClick = { showTimestamps = !showTimestamps },
                        label = { Text("Timestamps", fontSize = 11.sp) }
                    )
                    TextButton(onClick = { actions.clearConsole() }, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                    Text(
                        text = if (filtered.size > rendered.size) {
                            "showing last ${rendered.size} of ${filtered.size}"
                        } else {
                            "${filtered.size} lines"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (notice != null) {
                    MessageBanner(text = notice!!.text, isError = notice!!.isError) { notice = null }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TerminalBackground)
            ) {
                when {
                    displayEntries.isEmpty() -> ConsoleEmptyState(
                        title = emptyConsoleTitle(attached, shownState),
                        body = emptyConsoleBody(instance, attached, shownState, runtime)
                    )
                    rendered.isEmpty() -> ConsoleEmptyState(
                        title = "No matching lines",
                        body = "Nothing matches the current level filter or search text. Clear the filter to see the whole log."
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(rendered, key = { it.seq }) { entry ->
                            ConsoleLineRow(entry = entry, showTimestamp = showTimestamps && entry.timestamp > 0L)
                        }
                    }
                }
                if (!autoScroll && rendered.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 3.dp
                    ) {
                        TextButton(onClick = jumpToLatest) {
                            Text("Jump to latest", fontSize = 11.sp)
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (instance.isMinecraft) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QuickCommandChip("list", enabled = running) { sendCommand("list") }
                            QuickCommandChip("say Hello", enabled = running) { sendCommand("say Hello") }
                            QuickCommandChip("save-all", enabled = running) { sendCommand("save-all") }
                            QuickCommandChip("stop", enabled = running) { stopConfirm = true }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = running,
                            placeholder = {
                                Text(if (running) "Type a command…" else "Start the server to send commands")
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = { sendCommand(command) })
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { sendCommand(command) },
                            enabled = running && command.isNotBlank()
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send command")
                        }
                    }
                    if (!running) {
                        Text(
                            text = when {
                                !attached -> "Start this server to send commands."
                                shownState == RunState.STARTING -> "The server is starting; commands unlock once it is running."
                                shownState == RunState.STOPPING -> "The server is stopping."
                                shownState == RunState.CRASHED -> "The server stopped unexpectedly. Check the exit details on the server screen."
                                else -> "Start this server to send commands."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (stopConfirm) {
        ConfirmDialog(
            title = "Stop the server?",
            text = "This sends the Minecraft \"stop\" command so the world is saved before the process exits. " +
                "For generic Java servers use the power controls on the server screen.",
            confirmLabel = "Stop server",
            destructive = true,
            onConfirm = {
                try {
                    actions.sendCommand("stop")
                } catch (t: Throwable) {
                    notice = ConsoleNotice("Could not stop the server: ${t.readableMessage()}", isError = true)
                }
            },
            onDismiss = { stopConfirm = false }
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Small pieces                                                                */
/* -------------------------------------------------------------------------- */

@Composable
private fun QuickCommandChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontSize = 11.sp) }
    )
}

@Composable
private fun ConsoleStatusStrip(
    runtime: ServerRuntimeStatus,
    attached: Boolean,
    shownState: RunState,
    isMinecraft: Boolean
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatePill(text = runStateLabel(shownState), state = shownState)
            if (!attached) {
                StatusText("no active server")
            } else {
                if (runtime.startedAt != null) {
                    StatusText("up ${formatUptime(runtime.uptimeMs)}")
                }
                if (isMinecraft) {
                    val hasPlayers = runtime.playersMax > 0 || runtime.playersOnline > 0
                    if (hasPlayers) {
                        StatusText("${runtime.playersOnline}/${runtime.playersMax} players")
                    } else if (shownState == RunState.RUNNING) {
                        StatusText("players n/a")
                    }
                } else {
                    runtime.serverPort?.let { StatusText("port $it") }
                    runtime.connectionsOnline?.let { StatusText("$it connections") }
                }
                runtime.heapUsedMb?.let { used ->
                    val max = runtime.heapMaxMb
                    StatusText(
                        if (max != null && max > 0) {
                            "heap ${formatMegabytes(used)} / ${formatMegabytes(max)}"
                        } else {
                            "heap ${formatMegabytes(used)}"
                        }
                    )
                }
                runtime.pid?.let { StatusText("pid $it") }
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun runStateLabel(state: RunState): String = when (state) {
    RunState.STOPPED -> "Stopped"
    RunState.STARTING -> "Starting"
    RunState.RUNNING -> "Running"
    RunState.STOPPING -> "Stopping"
    RunState.CRASHED -> "Crashed"
}

private fun emptyConsoleTitle(attached: Boolean, shownState: RunState): String = when {
    !attached -> "No console output"
    shownState == RunState.STARTING -> "Starting…"
    shownState == RunState.CRASHED -> "Server crashed"
    else -> "Console is empty"
}

private fun emptyConsoleBody(
    instance: ServerInstance,
    attached: Boolean,
    shownState: RunState,
    runtime: ServerRuntimeStatus
): String = when {
    !attached -> "Start ${instance.name} from its server screen. Output streams here in real time."
    shownState == RunState.STARTING -> "Waiting for the first line of server output."
    shownState == RunState.CRASHED -> runtime.lastExit?.summary
        ?.takeIf { it.isNotBlank() }
        ?: "The process exited unexpectedly. Open the server screen for the last exit details."
    else -> "The console is empty. Start the server to see its log."
}

private fun safeFileStem(name: String): String {
    val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.')
    return cleaned.ifEmpty { "server" }
}

private fun fileTimestamp(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
