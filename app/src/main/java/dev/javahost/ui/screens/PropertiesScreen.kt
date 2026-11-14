package dev.javahost.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.storage.ServerPropertiesCodec
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.EmptyState
import dev.javahost.ui.components.LabeledField
import dev.javahost.ui.components.LoadingRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenContent
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.SuccessRow
import dev.javahost.ui.components.readableMessage
import dev.javahost.ui.theme.InfoBlue
import dev.javahost.ui.theme.TealGreen
import dev.javahost.ui.theme.WarnAmber
import kotlinx.coroutines.launch

private const val EULA_URL = "https://aka.ms/MinecraftEULA"

private data class PropertiesNotice(val text: String, val isError: Boolean = false)

private val DIFFICULTIES = listOf("peaceful", "easy", "normal", "hard")
private val GAMEMODES = listOf("survival", "creative", "adventure", "spectator")

/** Documented vanilla defaults, shown as hints only — they are never invented into the file. */
private val PROPERTY_DEFAULTS = mapOf(
    "server-port" to "25565",
    "motd" to "A Minecraft Server",
    "max-players" to "20",
    "view-distance" to "10",
    "simulation-distance" to "10",
    "difficulty" to "easy",
    "gamemode" to "survival",
    "level-name" to "world"
)

/* -------------------------------------------------------------------------- */
/* Screen                                                                      */
/* -------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesScreen(
    state: AppUiState,
    actions: AppActions,
    instance: ServerInstance,
    onBack: () -> Unit
) {
    if (!instance.isMinecraft) {
        ScreenScaffold(title = "server.properties", subtitle = instance.name, onBack = onBack) { padding ->
            ScreenContent(padding) {
                EmptyState(
                    title = "Minecraft-only feature",
                    body = "server.properties editing is available for Minecraft instances. " +
                        "Generic Java servers get raw file access instead — open Files to edit configuration files."
                )
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var loadKey by remember(instance.id) { mutableStateOf(0) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var loaded by remember(instance.id) { mutableStateOf(false) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    var missing by remember(instance.id) { mutableStateOf(false) }
    var saving by remember(instance.id) { mutableStateOf(false) }
    var saveNotice by remember(instance.id) { mutableStateOf<PropertiesNotice?>(null) }
    var rawExpanded by remember(instance.id) { mutableStateOf(false) }
    var rawText by remember(instance.id) { mutableStateOf("") }
    var eulaChecked by remember(instance.id) { mutableStateOf(false) }

    val draft = remember(instance.id) { mutableStateMapOf<String, String>() }

    val running = state.runtime.instanceId == instance.id && state.runtime.state == RunState.RUNNING

    LaunchedEffect(instance.id, loadKey) {
        loading = true
        loadError = null
        saveNotice = null
        actions.readServerProperties(instance.id)
            .onSuccess { snapshot ->
                draft.clear()
                draft.putAll(snapshot.entries)
                rawText = serializeProperties(snapshot.entries)
                // The codec/view-model treat a missing file as an empty document,
                // so distinguish "not created yet" from "loaded and empty".
                missing = snapshot.raw.isBlank() && snapshot.entries.isEmpty()
                loaded = true
                loading = false
            }
            .onFailure { error ->
                val message = error.readableMessage()
                missing = looksLikeMissingFile(message)
                loadError = if (missing) null else message
                draft.clear()
                rawText = ""
                loaded = true
                loading = false
            }
    }

    fun saveProperties() {
        if (saving) return
        val candidate: Map<String, String> = if (rawExpanded) {
            parseRawProperties(rawText)
        } else {
            draft.toMap()
        }
        val validationError = validateProperties(candidate)
        if (validationError != null) {
            saveNotice = PropertiesNotice(validationError, isError = true)
            return
        }
        val wasMissing = missing
        saving = true
        saveNotice = null
        scope.launch {
            actions.saveServerProperties(instance.id, candidate)
                .onSuccess {
                    draft.clear()
                    draft.putAll(candidate)
                    rawText = serializeProperties(candidate)
                    missing = false
                    loadError = null
                    saving = false
                    saveNotice = PropertiesNotice(
                        if (wasMissing) {
                            "Created server.properties. Restart the server for the change to take effect."
                        } else {
                            "Saved. Restart the server for changes to take effect."
                        }
                    )
                }
                .onFailure {
                    saving = false
                    saveNotice = PropertiesNotice("Could not save: ${it.readableMessage()}", isError = true)
                }
        }
    }

    ScreenScaffold(
        title = "server.properties",
        subtitle = instance.name,
        onBack = onBack,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (loadError != null) {
                        Text(
                            text = "Fix the read error above before saving, so unknown keys are not lost.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = { saveProperties() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = loaded && !saving && loadError == null
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Saving…")
                        } else {
                            Text(if (missing) "Create server.properties" else "Save changes")
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (loading) {
            ScreenContent(padding) {
                LoadingRow("Loading server.properties…")
            }
        } else {
            ScreenContent(padding) {
                if (loadError != null) {
                    MessageBanner(
                        text = "Could not read server.properties: $loadError",
                        isError = true
                    )
                    TextButton(onClick = { loadKey++ }) { Text("Retry") }
                }

                if (missing) {
                    SectionCard(
                        title = "server.properties not found",
                        subtitle = "This instance does not have one yet."
                    ) {
                        Text(
                            "Edit the values below and tap \"Create server.properties\" to write a fresh file. " +
                                "Only the properties you set here are written.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (!instance.eulaAccepted) {
                    SectionCard(
                        title = "Minecraft EULA",
                        subtitle = "Required before Minecraft server software will run."
                    ) {
                        Text(
                            "Minecraft server software requires accepting Mojang's End User License Agreement. " +
                                "Without it the server exits immediately after start.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            EULA_URL,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp
                        )
                        TextButton(
                            onClick = { runCatching { uriHandler.openUri(EULA_URL) } }
                        ) { Text("Open the Minecraft EULA") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = eulaChecked,
                                onCheckedChange = { eulaChecked = it }
                            )
                            Text(
                                "I accept the Minecraft EULA",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Button(
                            onClick = { actions.acceptEula(instance.id) },
                            enabled = eulaChecked
                        ) { Text("I accept") }
                    }
                } else {
                    SectionCard(title = "Minecraft EULA") {
                        SuccessRow("EULA accepted for this instance.")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = InfoBlue.copy(alpha = 0.10f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = InfoBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (running) {
                                "Changes take effect after the server is restarted. The server is currently running."
                            } else {
                                "Changes take effect the next time the server starts."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (saveNotice != null) {
                    MessageBanner(
                        text = saveNotice!!.text,
                        isError = saveNotice!!.isError
                    ) { saveNotice = null }
                }

                SectionCard(
                    title = "Common settings",
                    subtitle = "Typed editors for the properties most people change. Defaults are shown for keys that are not set yet."
                ) {
                    LabeledField(
                        label = "Server port",
                        value = draft["server-port"] ?: "",
                        onValueChange = { draft["server-port"] = it },
                        keyboardType = KeyboardType.Number,
                        supportingText = "Default ${PROPERTY_DEFAULTS["server-port"]} · 1–65535"
                    )
                    LabeledField(
                        label = "Bind address (server-ip)",
                        value = draft["server-ip"] ?: "",
                        onValueChange = { draft["server-ip"] = it },
                        supportingText = "Leave empty to accept connections from other devices. " +
                            "127.0.0.1 = this phone only."
                    )
                    if (ServerPropertiesCodec.isLoopbackBind(draft["server-ip"])) {
                        Text(
                            "This address only accepts connections from this phone: other devices on " +
                                "your network will not be able to connect. Leave the field empty so the " +
                                "server listens on all interfaces.",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarnAmber
                        )
                    }
                    LabeledField(
                        label = "MOTD",
                        value = draft["motd"] ?: "",
                        onValueChange = { draft["motd"] = it },
                        supportingText = "Message shown in the multiplayer server list"
                    )
                    LabeledField(
                        label = "Max players",
                        value = draft["max-players"] ?: "",
                        onValueChange = { draft["max-players"] = it },
                        keyboardType = KeyboardType.Number,
                        supportingText = "Default ${PROPERTY_DEFAULTS["max-players"]}"
                    )
                    SwitchRow(
                        title = "Online mode",
                        subtitle = "Require Mojang account authentication",
                        checked = draft.boolProperty("online-mode", fallback = true),
                        onCheckedChange = { draft["online-mode"] = it.toString() }
                    )
                    SwitchRow(
                        title = "PvP",
                        subtitle = "Allow player-versus-player combat",
                        checked = draft.boolProperty("pvp", fallback = true),
                        onCheckedChange = { draft["pvp"] = it.toString() }
                    )
                    SwitchRow(
                        title = "Whitelist",
                        subtitle = "Only allow whitelisted players to join",
                        checked = draft.boolProperty("white-list", fallback = false),
                        onCheckedChange = { draft["white-list"] = it.toString() }
                    )
                    SwitchRow(
                        title = "Enable command blocks",
                        checked = draft.boolProperty("enable-command-block", fallback = false),
                        onCheckedChange = { draft["enable-command-block"] = it.toString() }
                    )
                    LabeledField(
                        label = "View distance",
                        value = draft["view-distance"] ?: "",
                        onValueChange = { draft["view-distance"] = it },
                        keyboardType = KeyboardType.Number,
                        supportingText = "Default ${PROPERTY_DEFAULTS["view-distance"]} · 3–32"
                    )
                    LabeledField(
                        label = "Simulation distance",
                        value = draft["simulation-distance"] ?: "",
                        onValueChange = { draft["simulation-distance"] = it },
                        keyboardType = KeyboardType.Number,
                        supportingText = "Default ${PROPERTY_DEFAULTS["simulation-distance"]} · 3–32"
                    )
                    OptionChips(
                        label = "Difficulty",
                        options = DIFFICULTIES,
                        selected = draft["difficulty"] ?: PROPERTY_DEFAULTS.getValue("difficulty"),
                        onSelect = { draft["difficulty"] = it }
                    )
                    OptionChips(
                        label = "Gamemode",
                        options = GAMEMODES,
                        selected = draft["gamemode"] ?: PROPERTY_DEFAULTS.getValue("gamemode"),
                        onSelect = { draft["gamemode"] = it }
                    )
                    LabeledField(
                        label = "Level name",
                        value = draft["level-name"] ?: "",
                        onValueChange = { draft["level-name"] = it },
                        supportingText = "Default ${PROPERTY_DEFAULTS["level-name"]}"
                    )
                }

                SectionCard(
                    title = "All properties",
                    subtitle = "Advanced: edit the raw key=value text."
                ) {
                    TextButton(
                        onClick = {
                            if (!rawExpanded) {
                                rawText = serializeProperties(draft)
                            }
                            rawExpanded = !rawExpanded
                        }
                    ) {
                        Text(if (rawExpanded) "Hide raw editor" else "Show raw editor")
                    }
                    if (rawExpanded) {
                        Text(
                            "While the raw editor is open it overrides the typed fields above when saving. " +
                                "Only key=value lines from this editor are saved.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = rawText,
                            onValueChange = { rawText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 360.dp),
                            singleLine = false,
                            enabled = !saving,
                            textStyle = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            placeholder = { Text("key=value") }
                        )
                        Text(
                            "${parseRawProperties(rawText).size} keys parsed from the raw editor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Pieces                                                                      */
/* -------------------------------------------------------------------------- */

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OptionChips(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = selected.equals(option, ignoreCase = true),
                    onClick = { onSelect(option) },
                    label = { Text(option, fontSize = 11.sp) }
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                     */
/* -------------------------------------------------------------------------- */

private fun Map<String, String>.boolProperty(key: String, fallback: Boolean): Boolean {
    val raw = this[key]?.trim()?.lowercase() ?: return fallback
    return when (raw) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> fallback
    }
}

private fun parseRawProperties(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return@forEach
        val separator = trimmed.indexOf('=')
        if (separator <= 0) return@forEach
        val key = trimmed.substring(0, separator).trim()
        val value = trimmed.substring(separator + 1).trim()
        if (key.isNotEmpty()) result[key] = value
    }
    return result
}

private fun serializeProperties(entries: Map<String, String>): String =
    entries.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

private fun looksLikeMissingFile(message: String): Boolean {
    val lower = message.lowercase()
    return listOf(
        "no such file",
        "not found",
        "does not exist",
        "doesn't exist",
        "missing",
        "no existe"
    ).any { it in lower }
}

/** Validates the numeric / enum properties this screen exposes; null means "ok". */
private fun validateProperties(entries: Map<String, String>): String? {
    val numeric = listOf(
        Triple("server-port", 1, 65535),
        Triple("max-players", 0, 100000),
        Triple("view-distance", 3, 32),
        Triple("simulation-distance", 3, 32)
    )
    numeric.forEach { (key, min, max) ->
        val raw = entries[key]?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val value = raw.toIntOrNull()
                ?: return "\"$key\" must be a whole number (got \"$raw\")."
            if (value !in min..max) {
                return "\"$key\" must be between $min and $max (got $value)."
            }
        }
    }
    val difficulty = entries["difficulty"]?.trim()?.lowercase().orEmpty()
    if (difficulty.isNotEmpty() && difficulty !in DIFFICULTIES) {
        return "\"difficulty\" must be one of ${DIFFICULTIES.joinToString(", ")}."
    }
    val gamemode = entries["gamemode"]?.trim()?.lowercase().orEmpty()
    if (gamemode.isNotEmpty() && gamemode !in GAMEMODES) {
        return "\"gamemode\" must be one of ${GAMEMODES.joinToString(", ")}."
    }
    val serverIp = entries["server-ip"]?.trim().orEmpty()
    if (serverIp.isNotEmpty() && !SERVER_IP_PATTERN.matches(serverIp)) {
        return "\"server-ip\" may only contain letters, digits, dots, colons, hyphens and " +
            "underscores, or be left empty to listen on all interfaces."
    }
    return null
}

/** A bind address is a host name or literal IP; Java rejects whitespace and `=`. */
private val SERVER_IP_PATTERN = Regex("^[A-Za-z0-9._:-]+$")
