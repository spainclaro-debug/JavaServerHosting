package dev.javahost.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.javahost.core.model.JobState
import dev.javahost.core.runtime.TermuxMirrors
import dev.javahost.ui.AppActions
import dev.javahost.ui.AppUiState
import dev.javahost.ui.components.Bullet
import dev.javahost.ui.components.ConfirmDialog
import dev.javahost.ui.components.InfoRow
import dev.javahost.ui.components.JobProgressCard
import dev.javahost.ui.components.LoadingRow
import dev.javahost.ui.components.MessageBanner
import dev.javahost.ui.components.ScreenContent
import dev.javahost.ui.components.ScreenScaffold
import dev.javahost.ui.components.SectionCard
import dev.javahost.ui.components.Tag
import dev.javahost.ui.components.formatBytes
import dev.javahost.ui.components.formatTimestamp
import dev.javahost.ui.theme.TealGreen
import dev.javahost.ui.theme.WarnAmber

/**
 * Java runtime management. Runtimes are downloaded from Termux packages at the
 * user's request (nothing is bundled in the APK) or imported from a local
 * archive. Only arm64-v8a archives are supported.
 */
@Composable
fun JreScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    var importing by remember { mutableStateOf(false) }
    var importingFolder by remember { mutableStateOf(false) }
    var mirrorExpanded by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            actions.importJreArchive(uri)
        } else {
            importing = false
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            actions.importJreFolder(uri)
        } else {
            importingFolder = false
        }
    }
    LaunchedEffect(state.message?.id) {
        if (state.message != null) {
            importing = false
            importingFolder = false
        }
    }

    ScreenScaffold(
        title = "Java runtimes",
        subtitle = "Downloaded from Termux packages",
        onBack = onBack
    ) { padding ->
        ScreenContent(padding) {
            state.message?.let { message ->
                MessageBanner(message.text, message.isError, actions::dismissMessage)
            }

            SectionCard(title = "Device compatibility") {
                InfoRow("CPU architecture", state.device.abi.ifBlank { "unknown" })
                InfoRow("Android", "API ${state.device.androidSdk} · ${state.device.androidRelease}")
                InfoRow("Recommended", "Java ${state.recommendedJreMajor}")
                if (state.device.abi.isNotBlank() && state.device.abi != "arm64-v8a") {
                    MessageBanner(
                        "JavaHost only provides 64-bit ARM (arm64-v8a) runtimes. " +
                            "This device reports ${state.device.abi}; servers will probably not start.",
                        isError = true
                    )
                }
                Text(
                    "Android 8.0 (API 26) or newer is required to host a server. " +
                        "Only one runtime needs to be installed for servers that use it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.supportedJreMajors.forEach { major ->
                key(major) {
                    JreMajorCard(state = state, actions = actions, major = major)
                }
            }

            SectionCard(title = "Import a JRE archive", subtitle = "Advanced / offline install") {
                Text(
                    "Use this if downloading fails or you already have a Termux OpenJDK archive. " +
                        "The archive is copied into app-private storage and extracted by JavaHost.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (importing) {
                    LoadingRow("Importing archive…")
                }
                Button(
                    onClick = {
                        importing = true
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    enabled = !importing && !importingFolder,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Choose archive…") }
                Bullet("Supported: .zip / .tar.gz / .tar.xz archives that contain a java home (bin/java). Termux .deb packages are installed by the built-in installer, not imported here.")
                Bullet("The archive must match this device's architecture (arm64-v8a).")
                Bullet("Imported archives cannot be checksum-verified by JavaHost; only do this with files you trust.")
                Bullet("Import does not download anything. Nothing leaves your device.")
            }

            SectionCard(
                title = "Download mirror",
                subtitle = "Pick a faster Termux mirror if downloads are slow"
            ) {
                val currentMirror = state.settings.termuxMirror
                val currentLabel = when {
                    currentMirror.isBlank() -> "Automatic (probe fastest)"
                    else -> TermuxMirrors.presetLabel(currentMirror)
                        ?: "Custom mirror (set in Settings)"
                }
                InfoRow("Current", currentLabel)
                Box {
                    OutlinedButton(
                        onClick = { mirrorExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose mirror") }
                    DropdownMenu(
                        expanded = mirrorExpanded,
                        onDismissRequest = { mirrorExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Automatic (probe fastest)") },
                            onClick = {
                                mirrorExpanded = false
                                actions.setTermuxMirror("")
                            }
                        )
                        TermuxMirrors.PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(preset.label)
                                        Text(
                                            preset.note,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    mirrorExpanded = false
                                    actions.setTermuxMirror(preset.url)
                                }
                            )
                        }
                    }
                }
                Bullet("Automatic probes the mirrors and starts with the fastest reachable one. A pinned mirror is tried first and fallbacks are still available.")
                Bullet("All mirrors serve the same Termux OpenJDK packages and are checksum-verified. A custom URL can still be entered in Settings.")
            }

            SectionCard(
                title = "Import from a folder",
                subtitle = "Already downloaded Java with your browser?"
            ) {
                Text(
                    "Pick the folder that contains bin/java, or a parent folder such as an " +
                        "extracted jdk-21 directory. JavaHost copies it into app-private storage, " +
                        "checks that it is an Android/aarch64 runtime, and installs it without downloading anything.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (importingFolder) {
                    LoadingRow("Copying and importing folder…")
                }
                OutlinedButton(
                    onClick = {
                        importingFolder = true
                        folderLauncher.launch(null)
                    },
                    enabled = !importingFolder && !importing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Choose folder…") }
                Bullet("The folder must be an Android/bionic arm64 runtime (for example a Termux OpenJDK package extracted on-device).")
                Bullet("Oracle or Adoptium *Linux* builds are glibc, not bionic; JavaHost rejects them with a clear error instead of failing at server start.")
                Bullet("The folder must be visible in the system file picker (some Android/data and app-private directories are not).")
                Bullet("Folder imports are not checksum-verified by JavaHost; only use files you trust.")
            }

            SectionCard(title = "Technical notes") {
                Bullet("Source: Termux's OpenJDK packages for aarch64, downloaded from the configured package mirror.")
                Bullet("Download size is roughly 96–117 MB per runtime; installed size is about 170 MB after trimming.")
                Bullet("Downloads are verified with SHA-256 from the HTTPS package index before extraction.")
                Bullet("Runtimes are extracted into app-private storage. They are deleted when JavaHost is uninstalled.")
                Bullet("No Java runtime is bundled in the APK, which keeps the app small and avoids shipping third-party binaries.")
                Bullet("Runtimes are aarch64 only; x86_64 emulators and 32-bit devices are not supported.")
                Bullet("Oracle/Adoptium Linux aarch64 JDKs use glibc and cannot run on Android's bionic libc. Use a Termux/Pojav-style Android build, or import one from a folder.")
                Bullet("OpenJDK is licensed under GPLv2 with the Classpath Exception.")
                Text(
                    "Removing a runtime deletes its files from app storage. Servers configured to use it " +
                        "must be switched to another runtime before they can start.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun JreMajorCard(state: AppUiState, actions: AppActions, major: Int) {
    val installation = state.jres.firstOrNull { it.major == major }
    val job = state.jobFor(major)
    val isRecommended = major == state.recommendedJreMajor
    val isDefault = state.settings.defaultJreMajor == major
    var confirmUninstall by remember { mutableStateOf(false) }

    SectionCard(
        title = "Java $major",
        subtitle = if (isRecommended) "Recommended for most servers" else null,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (installation != null) Tag("Installed", TealGreen)
                if (isDefault) Tag("Default")
                if (job != null && job.state == JobState.RUNNING) Tag("Installing", WarnAmber)
            }
        }
    ) {
        when {
            job != null && job.state == JobState.RUNNING -> {
                JobProgressCard(job, onCancel = { actions.cancelJreJob(major) })
            }
            installation != null -> {
                InfoRow("Version", installation.versionLabel)
                InfoRow("Installed size", formatBytes(installation.installedBytes))
                InfoRow("Installed", formatTimestamp(installation.installedAt))
                InfoRow("Location", installation.javaHome)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isDefault) {
                        OutlinedButton(
                            onClick = { actions.setDefaultJre(major) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Make default") }
                    }
                    OutlinedButton(
                        onClick = { confirmUninstall = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Uninstall") }
                }
            }
            else -> {
                if (job != null && job.state == JobState.FAILED) {
                    MessageBanner(
                        job.message ?: "Installation failed. Check the connection or mirror and retry.",
                        isError = true
                    )
                }
                if (job != null && job.state == JobState.CANCELLED) {
                    MessageBanner("Installation cancelled. You can retry whenever you like.", isError = false)
                }
                Text(
                    "Not installed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InfoRow("Download", "≈106 MB")
                InfoRow("Installed size", "≈170 MB")
                InfoRow("Verification", "SHA-256 from HTTPS index")
                Button(
                    onClick = { actions.installJre(major) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Install Java $major") }
            }
        }
    }

    if (confirmUninstall) {
        ConfirmDialog(
            title = "Uninstall Java $major?",
            text = "The runtime files are removed from app storage. Servers configured to use " +
                "Java $major cannot start until another runtime is installed and selected.",
            confirmLabel = "Uninstall",
            destructive = true,
            onConfirm = { actions.uninstallJre(major) },
            onDismiss = { confirmUninstall = false }
        )
    }
}

/**
 * Shared runtime-major selector used by Create, Detail and Settings. A plain
 * dropdown keeps this dependency-free (no navigation library, no experimental
 * exposed-dropdown APIs).
 */
@Composable
internal fun JavaMajorPicker(
    label: String,
    selected: Int,
    majors: List<Int>,
    installedMajors: Set<Int>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    buildString {
                        append("Java $selected")
                        append(if (selected in installedMajors) "  ·  installed" else "  ·  not installed")
                    }
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                majors.forEach { major ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                buildString {
                                    append("Java $major")
                                    if (major !in installedMajors) append("  (not installed)")
                                }
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(major)
                        }
                    )
                }
            }
        }
    }
}
