package dev.javahost.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.javahost.core.model.FileEntry
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerInstance
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.ConfirmDialog
import dev.javahost.ui.components.EmptyState
import dev.javahost.ui.components.LabeledField
import dev.javahost.ui.components.LoadingRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.formatBytes
import dev.javahost.ui.components.formatTimestamp
import dev.javahost.ui.components.readableMessage
import dev.javahost.ui.theme.Danger
import dev.javahost.ui.theme.TealGreen
import dev.javahost.ui.theme.WarnAmber
import kotlinx.coroutines.launch

private data class FilesNotice(val text: String, val isError: Boolean = false)

private data class EditorState(
    val entry: FileEntry,
    val original: String,
    val text: String = original,
    val saving: Boolean = false,
    val error: String? = null
) {
    val dirty: Boolean get() = text != original
}

/* -------------------------------------------------------------------------- */
/* Screen                                                                      */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    state: AppUiState,
    actions: AppActions,
    instance: ServerInstance,
    onBack: () -> Unit,
    onOpenProperties: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentDir by remember(instance.id) { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<FilesNotice?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var pendingExport by remember { mutableStateOf<FileEntry?>(null) }

    val running = state.runtime.instanceId == instance.id && state.runtime.state == RunState.RUNNING

    LaunchedEffect(instance.id, currentDir, refreshKey) {
        loading = true
        loadError = null
        actions.listFiles(instance.id, currentDir)
            .onSuccess {
                entries = it
                loading = false
            }
            .onFailure {
                entries = emptyList()
                loadError = it.readableMessage()
                loading = false
            }
    }

    // Inside the tree, hardware back goes up one folder before leaving the screen.
    BackHandler(enabled = currentDir.isNotEmpty()) {
        currentDir = parentDir(currentDir)
    }

    fun reload() {
        refreshKey++
    }

    fun openFile(entry: FileEntry) {
        if (entry.isDirectory) {
            currentDir = entry.relativePath
            notice = null
            return
        }
        scope.launch {
            actions.readTextFile(instance.id, entry.relativePath)
                .onSuccess { text -> editor = EditorState(entry, original = text) }
                .onFailure {
                    notice = FilesNotice(
                        "Cannot open ${entry.name}: ${it.readableMessage()}",
                        isError = true
                    )
                }
        }
    }

    fun saveEditor(state: EditorState) {
        editor = state.copy(saving = true, error = null)
        scope.launch {
            actions.writeTextFile(instance.id, state.entry.relativePath, state.text)
                .onSuccess {
                    editor = null
                    notice = FilesNotice("Saved ${state.entry.name}.")
                    reload()
                }
                .onFailure {
                    editor = editor?.copy(saving = false, error = it.readableMessage())
                }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                actions.importFile(instance.id, currentDir, uri)
                    .onSuccess { imported ->
                        notice = FilesNotice("Imported ${imported.ifBlank { "file" }}.")
                        reload()
                    }
                    .onFailure {
                        notice = FilesNotice("Import failed: ${it.readableMessage()}", isError = true)
                    }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri != null && target != null) {
            scope.launch {
                actions.exportFile(instance.id, target.relativePath, uri)
                    .onSuccess { notice = FilesNotice("Exported ${target.name}.") }
                    .onFailure {
                        notice = FilesNotice("Export failed: ${it.readableMessage()}", isError = true)
                    }
            }
        }
    }

    val sortedEntries = remember(entries) {
        entries.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    ScreenScaffold(
        title = "Files",
        subtitle = instance.name,
        onBack = onBack,
        topActions = {
            if (instance.isMinecraft) {
                IconButton(onClick = onOpenProperties) {
                    Icon(Icons.Default.Settings, contentDescription = "Edit server.properties")
                }
            }
            IconButton(onClick = { newFolderOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = "New folder")
            }
            IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.ExitToApp, contentDescription = "Import file")
            }
            IconButton(onClick = { notice = null; reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FileBreadcrumbs(
                    currentDir = currentDir,
                    rootLabel = instance.name,
                    onNavigate = { target ->
                        currentDir = target
                        notice = null
                    }
                )
                if (notice != null) {
                    MessageBanner(text = notice!!.text, isError = notice!!.isError) { notice = null }
                }
                FilesWarningStrip(running = running)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingRow("Loading files…")
                    }
                    loadError != null -> EmptyState(
                        title = "Could not read this folder",
                        body = loadError ?: "Unknown error",
                        actionLabel = "Retry",
                        onAction = { reload() }
                    )
                    sortedEntries.isEmpty() -> EmptyState(
                        title = "This folder is empty",
                        body = "Import a file from storage or create a folder to get started."
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(sortedEntries, key = { it.relativePath }) { entry ->
                            FileRow(
                                entry = entry,
                                onOpen = { openFile(entry) },
                                onRename = { renaming = entry },
                                onExport = {
                                    pendingExport = entry
                                    exportLauncher.launch(entry.name)
                                },
                                onDelete = { deleting = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    // --- dialogs ------------------------------------------------------------
    if (newFolderOpen) {
        NewFolderDialog(
            parentLabel = currentDir.ifBlank { "root" },
            onDismiss = { newFolderOpen = false },
            onCreate = { name, reportError ->
                scope.launch {
                    actions.createDirectory(instance.id, currentDir, name)
                        .onSuccess {
                            newFolderOpen = false
                            notice = FilesNotice("Created folder \"$name\".")
                            reload()
                        }
                        .onFailure { reportError(it.readableMessage()) }
                }
            }
        )
    }

    val renameTarget = renaming
    if (renameTarget != null) {
        RenameDialog(
            entry = renameTarget,
            onDismiss = { renaming = null },
            onRename = { name, reportError ->
                scope.launch {
                    actions.renameEntry(instance.id, renameTarget.relativePath, name)
                        .onSuccess {
                            renaming = null
                            notice = FilesNotice("Renamed to \"$name\".")
                            reload()
                        }
                        .onFailure { reportError(it.readableMessage()) }
                }
            }
        )
    }

    val deleteTarget = deleting
    if (deleteTarget != null) {
        ConfirmDialog(
            title = "Delete \"${deleteTarget.name}\"?",
            text = if (deleteTarget.isDirectory) {
                "This permanently deletes the folder and everything inside it. " +
                    "Deleting world, plugin or JAR files can break this instance."
            } else {
                "This permanently deletes the file. Deleting the server JAR, world or plugin files can break this instance."
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                scope.launch {
                    actions.deleteEntry(instance.id, deleteTarget.relativePath)
                        .onSuccess {
                            notice = FilesNotice("Deleted \"${deleteTarget.name}\".")
                            reload()
                        }
                        .onFailure {
                            notice = FilesNotice(
                                "Could not delete \"${deleteTarget.name}\": ${it.readableMessage()}",
                                isError = true
                            )
                        }
                }
            },
            onDismiss = { deleting = null }
        )
    }

    val editorState = editor
    if (editorState != null) {
        EditorDialog(
            state = editorState,
            onTextChange = { text -> editor = editor?.copy(text = text) },
            onSave = { saveEditor(editorState) },
            onDismiss = { editor = null }
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Pieces                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun FileBreadcrumbs(
    currentDir: String,
    rootLabel: String,
    onNavigate: (String) -> Unit
) {
    val segments = remember(currentDir) {
        currentDir.split('/').filter { it.isNotBlank() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (currentDir.isNotEmpty()) {
            IconButton(
                onClick = { onNavigate(parentDir(currentDir)) },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Up one folder",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        TextButton(
            onClick = { onNavigate("") },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "Root",
                fontSize = 12.sp,
                color = if (currentDir.isEmpty()) TealGreen else MaterialTheme.colorScheme.onSurface
            )
        }
        segments.forEachIndexed { index, segment ->
            Text(
                text = "/",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            TextButton(
                onClick = {
                    onNavigate(segments.take(index + 1).joinToString("/"))
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = segment.ifBlank { rootLabel },
                    fontSize = 12.sp,
                    color = if (index == segments.lastIndex) TealGreen
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun FilesWarningStrip(running: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = WarnAmber.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = WarnAmber,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (running) {
                    "Editing or deleting server files can break this instance. The server is running — restart it after changes."
                } else {
                    "Editing or deleting server files can break this instance."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember(entry.relativePath) { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Default.List else Icons.Default.Edit,
                contentDescription = null,
                tint = if (entry.isDirectory) TealGreen
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (entry.isDirectory) {
                        "Folder"
                    } else {
                        "${formatBytes(entry.sizeBytes)} · ${formatTimestamp(entry.modifiedAt)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "File actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Export") },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = Danger
                            )
                        }
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
}

@Composable
private fun NewFolderDialog(
    parentLabel: String,
    onDismiss: () -> Unit,
    onCreate: (String, (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val validation = validateEntryName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Creating inside: $parentLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LabeledField(
                    label = "Folder name",
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    supportingText = error ?: validation
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim()) { message -> error = message } },
                enabled = name.isNotBlank() && validation == null
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RenameDialog(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onRename: (String, (String) -> Unit) -> Unit
) {
    var name by remember(entry.relativePath) { mutableStateOf(entry.name) }
    var error by remember(entry.relativePath) { mutableStateOf<String?>(null) }
    val validation = validateEntryName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entry.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LabeledField(
                    label = "New name",
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    supportingText = error ?: validation
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(name.trim()) { message -> error = message } },
                enabled = name.isNotBlank() && validation == null && name.trim() != entry.name
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditorDialog(
    state: EditorState,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = {
            Column {
                Text(
                    text = "Edit ${state.entry.name}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.entry.relativePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.error != null) {
                    MessageBanner(text = state.error, isError = true)
                }
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 320.dp),
                    singleLine = false,
                    enabled = !state.saving,
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    ),
                    placeholder = { Text("Empty file") }
                )
                Text(
                    text = buildString {
                        append("${state.text.length} chars")
                        if (state.dirty) append(" · unsaved changes")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = !state.saving && state.dirty) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) { Text("Close") }
        }
    )
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                     */
/* -------------------------------------------------------------------------- */

private fun parentDir(path: String): String =
    path.substringBeforeLast('/', missingDelimiterValue = "")

/** Returns an error message for an invalid single-segment file name, or null when valid. */
private fun validateEntryName(raw: String): String? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> null // button is disabled; no error text needed yet
        name == "." || name == ".." -> "That name is reserved."
        name.contains('/') || name.contains('\\') -> "Names cannot contain slashes."
        name.length > 200 -> "That name is too long."
        else -> null
    }
}
