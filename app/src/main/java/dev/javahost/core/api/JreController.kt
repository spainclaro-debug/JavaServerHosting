package dev.javahost.core.api

import dev.javahost.core.model.JobProgress
import dev.javahost.core.model.JreInstallation
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Downloads, verifies and installs aarch64 OpenJDK runtimes, and exposes the
 * installed ones to the launcher. Implementations must be safe to call from the
 * foreground service while the UI is also collecting progress.
 */
interface JreController {
    val installations: StateFlow<List<JreInstallation>>
    val jobs: StateFlow<Map<String, JobProgress>>

    /** Install (or repair) a runtime fetched from the configured Termux mirror. */
    suspend fun install(major: Int): Result<JreInstallation>

    /** Cancel an in-flight install for [major], if the implementation owns one. */
    fun cancelInstall(major: Int) = Unit

    /** Unpack a user-provided JRE archive (offline/self-hosted escape hatch). */
    suspend fun importArchive(file: File, displayName: String): Result<JreInstallation>

    /**
     * Import an already-extracted JRE/JDK directory (for example a runtime the
     * user downloaded and unzipped themselves). The directory may be the JRE
     * home itself or a parent that contains it.
     */
    suspend fun importDirectory(sourceDir: File, displayName: String): Result<JreInstallation>

    suspend fun uninstall(major: Int)

    fun isInstalled(major: Int): Boolean

    fun javaHome(major: Int): File?

    fun javaExecutable(major: Int): File?

    fun versionLabel(major: Int): String

    fun installedBytes(major: Int): Long

    /** Repair lost execute bits (e.g. after a restore from backup). */
    suspend fun ensureExecutable(major: Int): Boolean

    /** Supported major versions, newest first. */
    fun supportedMajors(): List<Int>
}
