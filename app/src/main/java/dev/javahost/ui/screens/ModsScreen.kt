package dev.javahost.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.javahost.core.model.FileEntry
import dev.javahost.core.model.ModTarget
import dev.javahost.core.model.ModrinthProject
import dev.javahost.core.model.ModrinthVersion
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerInstance
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.EmptyState
import dev.javahost.ui.components.LoadingRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.Tag
import dev.javahost.ui.components.formatBytes
import dev.javahost.ui.components.readableMessage
import dev.javahost.ui.theme.javaHostStatusColors
import kotlinx.coroutines.launch
import java.util.Locale

private data class ModsNotice(val text: String, val isError: Boolean = false)

/* -------------------------------------------------------------------------- */
/* Screen                                                                      */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(
    state: AppUiState,
    actions: AppActions,
    instance: ServerInstance,
    onBack: () -> Unit
) {
    if (!instance.isMinecraft) {
        ScreenScaffold(title = "Mods & plugins", subtitle = instance.name, onBack = onBack) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "Minecraft-only feature",
                    body = "Modrinth browsing is available for Minecraft instances. " +
                        "Generic Java servers get raw file access instead — open Files to upload JARs or edit configs."
                )
            }
        }
        return
    }

    val scope = rememberCoroutineScope()

    var target by remember(instance.id) { mutableStateOf(defaultTarget(instance)) }
    var query by remember(instance.id) { mutableStateOf("") }
    var gameVersion by remember(instance.id) { mutableStateOf(instance.minecraftVersion.orEmpty()) }
    var loader by remember(instance.id) { mutableStateOf(defaultLoader(instance)) }

    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ModrinthProject>>(emptyList()) }
    var lastPageSize by remember { mutableStateOf(0) }
    var searchedOnce by remember { mutableStateOf(false) }

    var installedFiles by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var installedLoading by remember { mutableStateOf(false) }
    var installedError by remember { mutableStateOf<String?>(null) }
    var installedRefresh by remember { mutableStateOf(0) }

    var notice by remember { mutableStateOf<ModsNotice?>(null) }
    var expandedProjectId by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<ModrinthVersion>>(emptyList()) }
    var versionsLoading by remember { mutableStateOf(false) }
    var versionsError by remember { mutableStateOf<String?>(null) }
    var installingVersionId by remember { mutableStateOf<String?>(null) }

    val running = state.runtimeFor(instance.id).state == RunState.RUNNING
    val folder = folderFor(target)

    LaunchedEffect(instance.id, target, installedRefresh) {
        installedLoading = true
        installedError = null
        actions.listFiles(instance.id, folder)
            .onSuccess {
                installedFiles = it.filter { entry -> !entry.isDirectory }
                installedLoading = false
            }
            .onFailure {
                installedFiles = emptyList()
                installedError = it.readableMessage()
                installedLoading = false
            }
    }

    fun runSearch(append: Boolean) {
        if (searching) return
        searching = true
        searchError = null
        notice = null
        val offset = if (append) results.size else 0
        scope.launch {
            actions.searchModrinth(
                target = target,
                query = query.trim(),
                gameVersion = gameVersion.trim().ifBlank { null },
                loader = loader.trim().ifBlank { null },
                offset = offset
            ).onSuccess { page ->
                results = if (append) {
                    results + page.filter { project -> results.none { it.id == project.id } }
                } else {
                    page
                }
                lastPageSize = page.size
                searchedOnce = true
                searching = false
            }.onFailure { error ->
                searchError = modrinthError("Search", error)
                searching = false
            }
        }
    }

    fun loadVersions(project: ModrinthProject) {
        expandedProjectId = project.id
        versions = emptyList()
        versionsError = null
        versionsLoading = true
        scope.launch {
            actions.listModrinthVersions(
                projectId = project.id,
                gameVersion = gameVersion.trim().ifBlank { null },
                loader = loader.trim().ifBlank { null }
            ).onSuccess {
                versions = it
                versionsLoading = false
            }.onFailure { error ->
                versionsError = modrinthError("Load versions", error)
                versionsLoading = false
            }
        }
    }

    fun installVersion(version: ModrinthVersion) {
        if (installingVersionId != null) return
        if (!isTargetSupported(instance, target)) {
            notice = ModsNotice(
                "${if (target == ModTarget.PLUGINS) "Plugins" else "Mods"} are not loaded by a " +
                    "${flavorLabel(instance.flavor)} server. Install a compatible server JAR first.",
                isError = true
            )
            return
        }
        if (!isVersionCompatible(instance, target, version)) {
            notice = ModsNotice(
                "This version is not built for a ${flavorLabel(instance.flavor)} server " +
                    "(loaders: ${version.loaders.joinToString(", ").ifBlank { "not listed" }}). " +
                    "Pick a version whose loaders match your server.",
                isError = true
            )
            return
        }
        installingVersionId = version.id
        notice = null
        scope.launch {
            actions.installModrinth(instance.id, version, target)
                .onSuccess { file ->
                    installingVersionId = null
                    notice = ModsNotice("Installed ${file.name}. Restart the server to load it.")
                    installedRefresh++
                }
                .onFailure { error ->
                    installingVersionId = null
                    notice = ModsNotice(modrinthError("Install", error), isError = true)
                }
        }
    }

    fun switchTarget(next: ModTarget) {
        if (next == target) return
        target = next
        results = emptyList()
        searchedOnce = false
        lastPageSize = 0
        searchError = null
        notice = null
        expandedProjectId = null
        versions = emptyList()
        versionsError = null
    }

    ScreenScaffold(
        title = "Mods & plugins",
        subtitle = "${instance.name} · Modrinth",
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = if (target == ModTarget.PLUGINS) 0 else 1) {
                Tab(
                    selected = target == ModTarget.PLUGINS,
                    onClick = { switchTarget(ModTarget.PLUGINS) },
                    text = { Text("Plugins") }
                )
                Tab(
                    selected = target == ModTarget.MODS,
                    onClick = { switchTarget(ModTarget.MODS) },
                    text = { Text("Mods") }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (target == ModTarget.PLUGINS && installedFiles.isNotEmpty()) {
                    val names = installedFiles.map { it.name.lowercase() }
                    val hasGeyser = names.any { "geyser" in it }
                    val hasFloodgate = names.any { "floodgate" in it }
                    val hasViaVersion = names.any { "viaversion" in it }
                    val hasViaBackwards = names.any { "viabackwards" in it }
                    if (hasGeyser && !hasFloodgate) {
                        SurfaceNote(
                            "Geyser is installed but Floodgate is not. Bedrock authentication may fail. " +
                                "Floodgate for Paper is distributed by GeyserMC (geysermc.org), not Modrinth; " +
                                "download the JAR and import it via Files into plugins/."
                        )
                    }
                    if (hasViaVersion && !hasViaBackwards) {
                        SurfaceNote(
                            "ViaVersion lets NEWER Java clients join this server. If your client is " +
                                "OLDER than Minecraft ${instance.minecraftVersion ?: "this server"}, " +
                                "also install ViaBackwards (and ViaRewind for 1.7/1.8)."
                        )
                    }
                }
                if (gameVersion.isBlank() && instance.isMinecraft) {
                    SurfaceNote(
                        "Minecraft version is unknown for this instance, so plugin/mod versions " +
                            "cannot be filtered automatically. Enter the server's version above to " +
                            "avoid installing a JAR for the wrong Minecraft release."
                    )
                }
                if (!isTargetSupported(instance, target)) {
                    SurfaceNote(
                        when (instance.flavor) {
                            ServerFlavor.VANILLA ->
                                "This is a Vanilla server: it does not load plugins or mods. " +
                                    "Geyser, Floodgate and ViaVersion require a Paper/Spigot server JAR " +
                                    "(or Fabric/Forge for mods). Installation is disabled so you do not " +
                                    "end up with files the server will ignore. Import a Paper JAR from " +
                                    "papermc.io as a new server (keep the world/ and plugins/ folders)."
                            ServerFlavor.FABRIC, ServerFlavor.FORGE ->
                                "Fabric/Forge load mods from mods/, not Bukkit plugins from plugins/. " +
                                    "Switch to the Mods tab and install Fabric/Forge versions."
                            else ->
                                "This server type does not load ${if (target == ModTarget.PLUGINS) "Bukkit plugins" else "these mods"}. " +
                                    "Choose a compatible server JAR first."
                        }
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search Modrinth ${if (target == ModTarget.PLUGINS) "plugins" else "mods"}") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch(false) })
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = gameVersion,
                        onValueChange = { gameVersion = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Game version") },
                        placeholder = { Text("any") },
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = loader,
                        onValueChange = { loader = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Loader") },
                        placeholder = { Text("any") },
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { runSearch(false) },
                        enabled = !searching,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Searching…")
                        } else {
                            Text("Search Modrinth")
                        }
                    }
                    if (results.isNotEmpty() || searchedOnce) {
                        TextButton(
                            onClick = {
                                results = emptyList()
                                searchedOnce = false
                                lastPageSize = 0
                                searchError = null
                                expandedProjectId = null
                                versions = emptyList()
                            }
                        ) { Text("Clear") }
                    }
                }

                if (searchError != null) {
                    MessageBanner(text = searchError!!, isError = true)
                    TextButton(onClick = { runSearch(false) }) { Text("Retry search") }
                }
                if (notice != null) {
                    MessageBanner(text = notice!!.text, isError = notice!!.isError) { notice = null }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Installed ${if (target == ModTarget.PLUGINS) "plugins" else "mods"}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Folder: $folder/ · restart the server after changes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { installedRefresh++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh installed files")
                        }
                    }
                }

                when {
                    installedLoading -> item { LoadingRow("Loading installed files…") }
                    installedError != null -> item {
                        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            MessageBanner(text = "Could not list $folder/: $installedError", isError = true)
                        }
                    }
                    installedFiles.isEmpty() -> item {
                        Text(
                            "No files in $folder/ yet.",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> items(installedFiles, key = { it.relativePath }) { entry ->
                        InstalledFileRow(entry)
                    }
                }

                item {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Search results",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (results.isNotEmpty()) {
                                Text(
                                    "${results.size} shown",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (running) {
                            Text(
                                "The server is running; installed files are picked up after a restart.",
                                style = MaterialTheme.typography.labelSmall,
                                color = javaHostStatusColors().warning
                            )
                        }
                    }
                }

                when {
                    searching && results.isEmpty() -> item { LoadingRow("Searching Modrinth…") }
                    !searchedOnce && results.isEmpty() -> item {
                        EmptyState(
                            title = "Search Modrinth",
                            body = "Enter a name above and search. Results are filtered by the game version and loader fields, " +
                                "which are prefilled from this instance."
                        )
                    }
                    searchedOnce && results.isEmpty() -> item {
                        EmptyState(
                            title = "No results",
                            body = "Nothing matched this query with the current game version (${gameVersion.ifBlank { "any" }}) " +
                                "and loader (${loader.ifBlank { "any" }}). Try clearing those fields or using a shorter query."
                        )
                    }
                    else -> items(results, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            expanded = expandedProjectId == project.id,
                            versions = versions,
                            versionsLoading = versionsLoading && expandedProjectId == project.id,
                            versionsError = if (expandedProjectId == project.id) versionsError else null,
                            installingVersionId = installingVersionId,
                            installAllowed = isTargetSupported(instance, target),
                            onToggle = {
                                if (expandedProjectId == project.id) {
                                    expandedProjectId = null
                                    versions = emptyList()
                                    versionsError = null
                                } else {
                                    loadVersions(project)
                                }
                            },
                            onInstall = { version -> installVersion(version) }
                        )
                    }
                }

                if (results.isNotEmpty() && lastPageSize > 0) {
                    item {
                        TextButton(
                            onClick = { runSearch(true) },
                            enabled = !searching,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (searching) "Loading…" else "Load more results") }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Pieces                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun SurfaceNote(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(javaHostStatusColors().info.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = javaHostStatusColors().info,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InstalledFileRow(entry: FileEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = javaHostStatusColors().success,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatBytes(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ProjectCard(
    project: ModrinthProject,
    expanded: Boolean,
    versions: List<ModrinthVersion>,
    versionsLoading: Boolean,
    versionsError: String?,
    installingVersionId: String?,
    installAllowed: Boolean,
    onToggle: () -> Unit,
    onInstall: (ModrinthVersion) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = project.title.trim().firstOrNull()?.uppercase() ?: "?",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        project.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val author = project.author?.takeIf { it.isNotBlank() }
                    Text(
                        text = buildString {
                            if (author != null) append("by $author · ")
                            append("${formatDownloadCount(project.downloads)} downloads")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Tag(
                    text = if (project.projectType == "mod") "mod" else "plugin",
                    color = if (project.projectType == "mod") javaHostStatusColors().success else javaHostStatusColors().info
                )
            }
            Text(
                project.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Loaders: " + project.loaders.take(5).joinToString(", ").ifBlank { "not listed" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Game versions: " + project.gameVersions.take(4).joinToString(", ").ifBlank { "not listed" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Hide versions" else "Versions")
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                when {
                    versionsLoading -> LoadingRow("Loading versions…")
                    versionsError != null -> MessageBanner(text = versionsError, isError = true)
                    versions.isEmpty() -> Text(
                        "No versions match the current game version and loader filters.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        versions.take(25).forEach { version ->
                            VersionRow(
                                version = version,
                                installEnabled = installingVersionId == null && installAllowed,
                                installing = installingVersionId == version.id,
                                onInstall = { onInstall(version) }
                            )
                        }
                        if (versions.size > 25) {
                            Text(
                                "Showing the newest 25 of ${versions.size} versions.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: ModrinthVersion,
    installEnabled: Boolean,
    installing: Boolean,
    onInstall: () -> Unit
) {
    val primaryFile = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                version.versionNumber.ifBlank { version.name },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    primaryFile?.let { append(it.filename) }
                    val gameVersions = version.gameVersions.take(3).joinToString(", ")
                    if (gameVersions.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(gameVersions)
                    }
                    primaryFile?.let {
                        if (isNotEmpty()) append(" · ")
                        append(formatBytes(it.size))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (version.loaders.isNotEmpty()) {
                Text(
                    version.loaders.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onInstall,
            enabled = installEnabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 4.dp
            )
        ) {
            if (installing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Install", fontSize = 12.sp)
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                     */
/* -------------------------------------------------------------------------- */

/** Plugins require a Bukkit-family server; mods require Fabric/Forge. */
private fun isTargetSupported(instance: ServerInstance, target: ModTarget): Boolean = when (target) {
    ModTarget.PLUGINS -> instance.flavor in setOf(
        ServerFlavor.PAPER,
        ServerFlavor.BUKKIT,
        ServerFlavor.OTHER_MINECRAFT
    )
    ModTarget.MODS -> instance.flavor in setOf(ServerFlavor.FABRIC, ServerFlavor.FORGE)
}

/**
 * A plugin version must advertise a Bukkit-family loader; a mod version must
 * advertise Fabric/Forge/NeoForge. Empty loader lists are accepted because
 * Modrinth occasionally omits them.
 */
private fun isVersionCompatible(
    instance: ServerInstance,
    target: ModTarget,
    version: ModrinthVersion
): Boolean {
    val loaders = version.loaders.map { it.lowercase() }
    if (loaders.isEmpty()) return true
    return when (target) {
        ModTarget.PLUGINS -> loaders.any {
            it in setOf("paper", "spigot", "bukkit", "folia", "purpur", "bungeecord", "velocity")
        }
        ModTarget.MODS -> loaders.any {
            it in setOf("fabric", "quilt", "forge", "neoforge")
        }
    }
}

private fun flavorLabel(flavor: ServerFlavor): String = when (flavor) {
    ServerFlavor.VANILLA -> "Vanilla"
    ServerFlavor.PAPER -> "Paper"
    ServerFlavor.FABRIC -> "Fabric"
    ServerFlavor.FORGE -> "Forge"
    ServerFlavor.BUKKIT -> "Bukkit/Spigot"
    ServerFlavor.OTHER_MINECRAFT -> "Minecraft modded"
    ServerFlavor.GENERIC -> "generic Java"
}

private fun defaultTarget(instance: ServerInstance): ModTarget = when (instance.flavor) {
    ServerFlavor.FABRIC, ServerFlavor.FORGE -> ModTarget.MODS
    else -> ModTarget.PLUGINS
}

private fun defaultLoader(instance: ServerInstance): String = when (instance.flavor) {
    ServerFlavor.FABRIC -> "fabric"
    ServerFlavor.FORGE -> "forge"
    ServerFlavor.BUKKIT -> "bukkit"
    ServerFlavor.PAPER, ServerFlavor.OTHER_MINECRAFT -> "paper"
    ServerFlavor.VANILLA -> ""
    ServerFlavor.GENERIC -> ""
}

private fun folderFor(target: ModTarget): String =
    if (target == ModTarget.PLUGINS) "plugins" else "mods"

private fun formatDownloadCount(count: Long): String = when {
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(Locale.US, "%.1fk", count / 1_000.0)
    else -> count.toString()
}

/** Turns a failed Modrinth [Result] into an actionable sentence, especially for HTTP 429. */
private fun modrinthError(action: String, error: Throwable): String {
    val message = error.readableMessage()
    val lower = message.lowercase()
    return when {
        "429" in lower || "rate limit" in lower || "too many requests" in lower ->
            "$action failed: Modrinth is rate-limiting requests from this device. Wait about a minute, then try again."
        "timeout" in lower || "timed out" in lower ->
            "$action failed: Modrinth did not answer in time. Check your connection and retry."
        "unable to resolve host" in lower || "unknownhost" in lower || "network" in lower ||
            "connect" in lower || "offline" in lower ->
            "$action failed: could not reach Modrinth. Check your internet connection."
        else -> "$action failed: $message"
    }
}
