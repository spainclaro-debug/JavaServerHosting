package dev.javahost.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.javahost.core.model.JarInspection
import dev.javahost.core.model.NewInstanceDraft
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerKind
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.Bullet
import dev.javahost.ui.components.InfoRow
import dev.javahost.ui.components.LabeledField
import dev.javahost.ui.components.LabeledSlider
import dev.javahost.ui.components.LoadingRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenContent
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.Tag
import dev.javahost.ui.components.formatMegabytes
import dev.javahost.ui.theme.TealGreen
import dev.javahost.ui.theme.WarnAmber

private data class FlavorChoice(val flavor: ServerFlavor?, val label: String)

private val flavorChoices = listOf(
    FlavorChoice(null, "Auto"),
    FlavorChoice(ServerFlavor.VANILLA, "Vanilla"),
    FlavorChoice(ServerFlavor.PAPER, "Paper"),
    FlavorChoice(ServerFlavor.FABRIC, "Fabric"),
    FlavorChoice(ServerFlavor.FORGE, "Forge"),
    FlavorChoice(ServerFlavor.BUKKIT, "Bukkit"),
    FlavorChoice(ServerFlavor.GENERIC, "Generic")
)

private const val CREATE_EULA_URL = "https://aka.ms/MinecraftEULA"

/**
 * Create-instance flow: SAF pick a JAR, show the classifier's evidence, let the
 * user override the flavor, then configure runtime and memory. Navigation to
 * the new instance is driven by [AppUiState.lastCreatedInstanceId].
 */
@Composable
fun CreateInstanceScreen(
    state: AppUiState,
    actions: AppActions,
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val pendingJar = state.pendingJar
    val jarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // The ViewModel takes a persistable read grant when it inspects the URI.
        if (uri != null) actions.inspectJar(uri)
    }

    var overrideFlavor by remember { mutableStateOf<ServerFlavor?>(null) }
    var name by remember { mutableStateOf("") }
    var jreMajor by remember { mutableStateOf(state.recommendedJreMajor) }
    var memoryMb by remember { mutableStateOf(state.device.autoHeapMb) }
    var autoMemory by remember { mutableStateOf(state.settings.autoMemoryByDefault) }
    var jvmArgs by remember { mutableStateOf("") }
    var serverArgs by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var eulaAccepted by remember { mutableStateOf(state.settings.minecraftEulaAcknowledged) }
    var creating by remember { mutableStateOf(false) }
    var initialCount by remember { mutableStateOf(state.instances.size) }
    var initialCreatedId by remember { mutableStateOf(state.lastCreatedInstanceId) }
    var navigated by remember { mutableStateOf(false) }

    val safeMaxMb = maxOf(512, (state.device.safeMaxHeapMb / 128) * 128)
    val autoHeapMb = state.device.autoHeapMb.coerceIn(256, safeMaxMb)

    LaunchedEffect(pendingJar?.jarFile) {
        val jar = pendingJar ?: return@LaunchedEffect
        overrideFlavor = null
        name = jar.displayName.substringBeforeLast('.', jar.displayName).ifBlank { "Server" }
        val suggested = jar.inspection.suggestedJreMajor
        jreMajor = if (suggested in state.supportedJreMajors) suggested else state.recommendedJreMajor
        autoMemory = state.settings.autoMemoryByDefault
        memoryMb = if (autoMemory) autoHeapMb else state.settings.defaultMemoryMb.coerceIn(256, safeMaxMb)
        jvmArgs = ""
        serverArgs = if (jar.inspection.isMinecraft) "nogui" else ""
        eulaAccepted = state.settings.minecraftEulaAcknowledged
        portText = ""
        creating = false
        initialCreatedId = state.lastCreatedInstanceId
    }

    val baseInspection: JarInspection = pendingJar?.inspection ?: JarInspection()
    val effectiveInspection: JarInspection = overrideFlavor?.let { flavor ->
        baseInspection.copy(
            kind = if (flavor == ServerFlavor.GENERIC) ServerKind.GENERIC else ServerKind.MINECRAFT,
            flavor = flavor,
            confidence = 1f
        )
    } ?: baseInspection
    val effectiveMinecraft = effectiveInspection.kind == ServerKind.MINECRAFT
    val jreInstalled = state.isInstalled(jreMajor)

    LaunchedEffect(effectiveMinecraft, pendingJar?.jarFile) {
        if (effectiveMinecraft) {
            if (serverArgs.isBlank()) serverArgs = "nogui"
        } else if (serverArgs.trim() == "nogui") {
            serverArgs = ""
        }
    }

    val portValue = portText.trim().toIntOrNull()
    val portError = when {
        portText.isBlank() -> null
        portValue == null -> "Enter a whole number."
        portValue !in 1..65535 -> "Port must be between 1 and 65535."
        else -> null
    }
    val canCreate = pendingJar != null && name.isNotBlank() && portError == null &&
        !creating && (!effectiveMinecraft || eulaAccepted)
    val validationHint = when {
        pendingJar == null -> "Choose a .jar file first."
        name.isBlank() -> "Give this server a name."
        portError != null -> portError
        effectiveMinecraft && !eulaAccepted -> "Accept the Minecraft EULA to create a Minecraft server."
        else -> null
    }

    // Navigate once the ViewModel reports a newly created instance.
    LaunchedEffect(state.lastCreatedInstanceId) {
        val created = state.lastCreatedInstanceId
        if (!navigated && created != null && created != initialCreatedId) {
            navigated = true
            onCreated(created)
        }
    }
    // Fallback for a ViewModel that only updates the list.
    LaunchedEffect(state.instances.size) {
        if (!navigated && creating && state.instances.size > initialCount && state.lastCreatedInstanceId == null) {
            state.instances.lastOrNull()?.let { created ->
                navigated = true
                onCreated(created.id)
            }
        }
    }
    LaunchedEffect(state.message?.id) {
        if (state.message?.isError == true) creating = false
    }

    ScreenScaffold(
        title = "New server",
        subtitle = "Pick a JAR, review the detection, then configure",
        onBack = onBack,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    if (validationHint != null) {
                        Text(
                            validationHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = WarnAmber
                        )
                    }
                    Button(
                        onClick = {
                            val jar = pendingJar
                            if (jar != null) {
                                initialCount = state.instances.size
                                creating = true
                                actions.createInstance(
                                    NewInstanceDraft(
                                        name = name.trim(),
                                        jarFile = jar.jarFile,
                                        inspection = effectiveInspection,
                                        jreMajor = jreMajor,
                                        memoryMb = if (autoMemory) autoHeapMb else memoryMb,
                                        autoMemory = autoMemory,
                                        jvmArgs = jvmArgs.trim(),
                                        serverArgs = serverArgs.trim(),
                                        eulaAccepted = if (effectiveMinecraft) eulaAccepted else true,
                                        serverPort = portValue
                                    )
                                )
                            }
                        },
                        enabled = canCreate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (creating) "Creating…" else "Create server")
                    }
                }
            }
        }
    ) { padding ->
        ScreenContent(padding) {
            state.message?.let { message ->
                MessageBanner(message.text, message.isError, actions::dismissMessage)
            }

            /* ---------------- JAR picker ---------------- */
            SectionCard(title = "Server JAR", subtitle = "Selected with the Android file picker") {
                if (pendingJar == null) {
                    Text(
                        "Pick a server JAR you already have. JavaHost copies it into app-private " +
                            "storage, so the original file is never modified.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { jarLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose .jar file") }
                    Bullet("Minecraft: vanilla, Paper/Spigot, Fabric or Forge server JARs.")
                    Bullet("Other Java servers: any executable JAR with a main class — Advanced / Experimental.")
                } else {
                    InfoRow("File", pendingJar.displayName)
                    InfoRow("Working copy", pendingJar.jarFile)
                    if (state.importingJar) {
                        LoadingRow("Inspecting JAR…")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { jarLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Choose another") }
                        TextButton(onClick = { actions.clearPendingJar() }) { Text("Remove") }
                    }
                }
                if (state.importingJar && pendingJar == null) {
                    LoadingRow("Inspecting JAR…")
                }
            }

            if (pendingJar != null) {
                DetectionCard(
                    inspection = baseInspection,
                    overrideFlavor = overrideFlavor,
                    onOverride = { overrideFlavor = it }
                )
                if (baseInspection.needsInstaller) {
                    MessageBanner(
                        "This looks like an installer JAR. Forge and some Fabric downloads must be run once " +
                            "before a real server JAR exists. JavaHost will store and run this file as-is.",
                        isError = true
                    )
                }
                if (overrideFlavor == ServerFlavor.GENERIC && baseInspection.isMinecraft) {
                    MessageBanner(
                        "Manual override: treated as a generic Java server. Minecraft-only screens " +
                            "(server.properties, mods, plugins) will be hidden.",
                        isError = false
                    )
                } else if (overrideFlavor != null && !effectiveMinecraft) {
                    MessageBanner(
                        "Manual override: JavaHost will not create eula.txt or show Minecraft-only tools.",
                        isError = false
                    )
                }
            }

            /* ---------------- Name ---------------- */
            SectionCard(title = "Server name") {
                LabeledField(
                    label = "Name",
                    value = name,
                    onValueChange = { name = it },
                    supportingText = "Shown on the home screen and in notifications."
                )
            }

            /* ---------------- Runtime ---------------- */
            SectionCard(title = "Java runtime") {
                JavaMajorPicker(
                    label = "Run this server with",
                    selected = jreMajor,
                    majors = state.supportedJreMajors,
                    installedMajors = state.jres.map { it.major }.toSet(),
                    onSelect = { jreMajor = it }
                )
                if (!jreInstalled) {
                    MessageBanner(
                        "Java $jreMajor is not installed yet. You can create the server now, " +
                            "but it cannot start until the runtime is installed in Settings → Java runtimes.",
                        isError = false
                    )
                }
                if (baseInspection.suggestedJreMajor != jreMajor) {
                    Text(
                        "Detected minimum: Java ${baseInspection.suggestedJreMajor}. " +
                            "Using a lower version may fail at startup.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarnAmber
                    )
                }
            }

            /* ---------------- Memory ---------------- */
            SectionCard(title = "Memory") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatic memory", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "JavaHost picks ${formatMegabytes(autoHeapMb)} based on this device. " +
                                "Safe ceiling: ${formatMegabytes(safeMaxMb)}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = autoMemory,
                        onCheckedChange = {
                            autoMemory = it
                            if (it) memoryMb = autoHeapMb
                        }
                    )
                }
                LabeledSlider(
                    label = "Heap size",
                    value = if (autoMemory) autoHeapMb else memoryMb,
                    range = 256..safeMaxMb,
                    step = 128,
                    enabled = !autoMemory,
                    onValueChange = { memoryMb = it }
                )
                if (!autoMemory && memoryMb > state.device.autoHeapMb) {
                    MessageBanner(
                        "Above the automatic pick (${formatMegabytes(state.device.autoHeapMb)}). " +
                            "The slider is capped at this device's safe ceiling of ${formatMegabytes(safeMaxMb)}; " +
                            "Android may still kill the server if other apps need memory.",
                        isError = false
                    )
                }
            }

            /* ---------------- Advanced ---------------- */
            SectionCard(title = "Advanced", subtitle = "Optional") {
                LabeledField(
                    label = "JVM arguments (before -jar)",
                    value = jvmArgs,
                    onValueChange = { jvmArgs = it },
                    singleLine = false,
                    supportingText = "Example: -XX:+UseG1GC -Dfile.encoding=UTF-8. Leave empty for defaults."
                )
                LabeledField(
                    label = "Server arguments (after the JAR)",
                    value = serverArgs,
                    onValueChange = { serverArgs = it },
                    singleLine = false,
                    supportingText = if (effectiveMinecraft) {
                        "Minecraft defaults to \"nogui\". Only change this if you know the server's flags."
                    } else {
                        "Arguments passed to the Java server's main class."
                    }
                )
                LabeledField(
                    label = "Server port (optional)",
                    value = portText,
                    onValueChange = { portText = it },
                    keyboardType = KeyboardType.Number,
                    supportingText = portError
                        ?: "Recorded as metadata. Minecraft expects 25565; change it in server.properties."
                )
            }

            /* ---------------- EULA gate ---------------- */
            if (effectiveMinecraft) {
                SectionCard(title = "Minecraft EULA") {
                    Text(
                        "Mojang's End User License Agreement applies to Minecraft servers. " +
                            "JavaHost writes eula=true for you only after you accept.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { uriHandler.openUri(CREATE_EULA_URL) }) {
                        Text("Open the Minecraft EULA (aka.ms/MinecraftEULA)")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            if (!eulaAccepted) {
                                eulaAccepted = true
                                if (!state.settings.minecraftEulaAcknowledged) {
                                    actions.acknowledgeMinecraftEula()
                                }
                            }
                        }
                    ) {
                        Checkbox(
                            checked = eulaAccepted,
                            onCheckedChange = { checked ->
                                eulaAccepted = checked
                                if (checked) actions.acknowledgeMinecraftEula()
                            }
                        )
                        Column {
                            Text("I accept the Minecraft EULA", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Required before a Minecraft server can start.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!effectiveMinecraft) {
                SectionCard(title = "Advanced / Experimental server") {
                    Text(
                        "Non-Minecraft servers are supported on a best-effort basis: JavaHost starts the JAR, " +
                            "streams its console and manages files, but cannot manage game state, players or mods.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag("Experimental", WarnAmber)
                        if (effectiveInspection.mainClass != null) {
                            Tag("Java ${effectiveInspection.suggestedJreMajor}")
                        }
                    }
                }
            }

            if (creating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LoadingRow("Creating server…")
            }
        }
    }
}

@Composable
private fun DetectionCard(
    inspection: JarInspection,
    overrideFlavor: ServerFlavor?,
    onOverride: (ServerFlavor?) -> Unit
) {
    SectionCard(
        title = "Detection",
        subtitle = if (overrideFlavor == null) "Heuristic result — always review and override if wrong"
        else "Manual override applied"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                inspection.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Tag(
                if (overrideFlavor == null) "${(inspection.confidence * 100).toInt()}% match" else "Manual",
                if (overrideFlavor == null) MaterialTheme.colorScheme.primary else TealGreen
            )
        }
        LinearProgressIndicator(
            progress = { inspection.confidence.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
        InfoRow("Main class", inspection.mainClass ?: "Not detected")
        inspection.classFileMajor?.let { major ->
            InfoRow("Class file", "major $major · needs Java ${(major - 44).coerceAtLeast(8)}+")
        }
        InfoRow("Suggested Java", "Java ${inspection.suggestedJreMajor}")
        inspection.minecraftVersion?.let { version -> InfoRow("Minecraft version", version) }
        if (inspection.evidence.isNotEmpty()) {
            Text("Evidence", style = MaterialTheme.typography.labelLarge)
            inspection.evidence.take(6).forEach { line -> Bullet(line) }
        }
        if (inspection.warnings.isNotEmpty()) {
            Text("Warnings", style = MaterialTheme.typography.labelLarge)
            inspection.warnings.take(6).forEach { warning ->
                MessageBanner(warning, isError = true)
            }
        }
        Text("What is it?", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            flavorChoices.forEach { choice ->
                FilterChip(
                    selected = overrideFlavor == choice.flavor,
                    onClick = { onOverride(choice.flavor) },
                    label = { Text(choice.label) }
                )
            }
        }
        Text(
            "Auto uses the detection above. Manual override always wins and is stored with the instance.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
