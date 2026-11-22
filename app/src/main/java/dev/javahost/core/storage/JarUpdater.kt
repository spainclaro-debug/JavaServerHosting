package dev.javahost.core.storage

import dev.javahost.core.jar.JarInspector
import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.JarInspection
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Outcome of replacing an instance's server JAR.
 *
 * @property jarFileName target JAR file name inside the instance directory.
 * @property inspection inspection of the new JAR (kind, flavour, Minecraft
 *   version, suggested JRE), taken before the file is moved into place.
 * @property backupFileName name of the backup of the previous JAR inside the
 *   instance directory, or `null` when the instance had no JAR yet.
 */
data class JarUpdateResult(
    val jarFileName: String,
    val inspection: JarInspection,
    /** Name of the backup of the previous JAR inside the instance directory, or null. */
    val backupFileName: String?
)

/**
 * Atomic in-place replacement of an instance's server JAR.
 *
 * The update is deliberately conservative: it validates the picked file and
 * inspects it as a server *before* touching the instance directory, keeps the
 * previous JAR as a single `<name>.bak`, and rolls back if anything fails. Only
 * the target JAR, its backup and a temporary copy are ever written; the world,
 * plugins/mods, `server.properties`, logs and every other file stay untouched.
 */
object JarUpdater {

    /** Name suffix for the single rollback copy of the previous JAR. */
    const val BACKUP_SUFFIX = ".bak"

    private const val TEMP_PREFIX = ".jarupdate-"
    private const val TEMP_SUFFIX = ".tmp"

    /**
     * Replaces `[instanceDir]/[targetJarName]` with the content of [sourceJar].
     *
     * - validates [sourceJar] exists and is a file, [targetJarName] is a plain
     *   file name (no separators, not `.`/`..`), the resolved target stays
     *   inside [instanceDir], and [sourceJar] is not the same file as the target;
     * - inspects the source with [inspect] (default [JarInspector.inspect]):
     *   a [InspectionResult.Failure] or an installer JAR (`needsInstaller`) fails
     *   the update, and nothing in the instance directory is touched;
     * - sweeps stale `.jarupdate-*.tmp` files left behind by a previous hard kill
     *   (the current stamp's temp name is left alone) at the start of the update;
     * - copies the source to `.jarupdate-<stamp>.tmp` inside [instanceDir], then
     *   copies the current target to `<targetJarName>.bak` while the target stays
     *   in place (replacing an older backup), and finally moves the temp file over
     *   the target with an atomic `ATOMIC_MOVE` (`REPLACE_EXISTING` fallback when
     *   the filesystem cannot move atomically). A crash at any point therefore
     *   always leaves a usable `server.jar` in place, at worst with a fresh `.bak`;
     * - on any failure the temp file is removed and the fresh `.bak` copy is
     *   removed while the old target is still in place; a pre-existing `.bak` is
     *   never deleted when no fresh copy was made;
     * - never touches any other file in the instance directory.
     *
     * @param stamp timestamp used in the temp-file name (tests pass a fixed value).
     * @return [JarUpdateResult] on success, or a failure whose exception message
     *   is ready to show to the user.
     */
    fun update(
        instanceDir: File,
        targetJarName: String,
        sourceJar: File,
        stamp: Long = System.currentTimeMillis(),
        inspect: (File) -> InspectionResult = JarInspector::inspect
    ): Result<JarUpdateResult> = runCatching {
        replace(instanceDir, targetJarName, sourceJar, stamp, inspect)
    }

    // ---------------------------------------------------------------------
    // Implementation
    // ---------------------------------------------------------------------

    private fun replace(
        instanceDir: File,
        targetJarName: String,
        sourceJar: File,
        stamp: Long,
        inspect: (File) -> InspectionResult
    ): JarUpdateResult {
        val instanceDirectory = requireInstanceDirectory(instanceDir)
        val temp = File(instanceDirectory, tempName(stamp))
        // Garbage from a previous hard kill can never be needed; the current
        // stamp's temp is excluded because it is about to be written.
        sweepStaleTemps(instanceDirectory, temp)

        val target = resolveTarget(instanceDirectory, targetJarName)
        requireSourceFile(sourceJar)
        if (isSameFile(sourceJar, target)) {
            throw IllegalArgumentException(
                "The selected file is the current server JAR. Pick a different .jar file to update it."
            )
        }
        if (target.exists() && !target.isFile) {
            throw IllegalArgumentException(
                "\"$targetJarName\" exists in the instance folder but is not a file. " +
                    "Remove it and try again."
            )
        }

        // Inspect before touching anything so a rejected JAR never disturbs the instance.
        val inspection = inspectAsServer(sourceJar, inspect)
        if (inspection.needsInstaller) {
            throw IllegalArgumentException(
                "The selected file is an installer JAR, not a runnable server JAR. " +
                    "Run it once to produce the real server JAR, then pick that JAR instead."
            )
        }

        var backup: File? = null
        var backupWritten = false
        try {
            sourceJar.copyTo(temp, overwrite = true)
            if (target.exists()) {
                val backupFile = File(instanceDirectory, target.name + BACKUP_SUFFIX)
                backupWritten = true
                try {
                    // Copy, do not move: the target must stay in place until the
                    // atomic swap below, so a crash never loses the server JAR.
                    Files.copy(
                        target.toPath(),
                        backupFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: IOException) {
                    throw IOException(
                        "Could not create the backup \"${backupFile.name}\": " +
                            (e.message ?: e.javaClass.simpleName),
                        e
                    )
                }
                backup = backupFile
            }
            moveIntoPlace(temp, target)
            return JarUpdateResult(
                jarFileName = target.name,
                inspection = inspection,
                backupFileName = backup?.name
            )
        } catch (t: Throwable) {
            cleanupFailedUpdate(temp, target, backup, backupWritten)
            throw t
        }
    }

    /**
     * Moves [temp] over [target] atomically when the filesystem supports it, so a
     * process death can never leave the instance without a `server.jar`.
     */
    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // Same-directory moves are atomic on Android, but keep a fallback for
            // exotic filesystems where ATOMIC_MOVE is not offered.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Best-effort cleanup after a failed update; never throws. The old target is
     * still in place, so the fresh `.bak` copy created by this call is redundant
     * and removed. If the target is somehow gone, the fresh backup is kept as a
     * recovery copy.
     */
    private fun cleanupFailedUpdate(
        temp: File,
        target: File,
        backup: File?,
        backupWritten: Boolean
    ) {
        runCatching {
            if (temp.exists()) Files.deleteIfExists(temp.toPath())
        }
        if (!backupWritten || backup == null) return
        runCatching {
            if (target.exists() && !target.isDirectory) {
                Files.deleteIfExists(backup.toPath())
            }
        }
    }

    /**
     * Deletes `.jarupdate-*.tmp` files from a previous hard kill. [currentTemp] is
     * skipped: it is about to be written by this call.
     */
    private fun sweepStaleTemps(instanceDirectory: File, currentTemp: File) {
        val stale = instanceDirectory.listFiles()
            ?.filter { it.name != currentTemp.name && isTempName(it.name) }
            ?: return
        for (file in stale) {
            runCatching { Files.deleteIfExists(file.toPath()) }
        }
    }

    /** Validates and canonicalises the instance directory. */
    private fun requireInstanceDirectory(instanceDir: File): File {
        if (!instanceDir.exists()) {
            throw IllegalArgumentException(
                "The instance folder does not exist: \"${instanceDir.path}\". " +
                    "Open the server once and try again."
            )
        }
        if (!instanceDir.isDirectory) {
            throw IllegalArgumentException(
                "The instance path is not a folder: \"${instanceDir.path}\"."
            )
        }
        return instanceDir.canonicalFile
    }

    /**
     * Validates [targetJarName] as a plain file name and resolves it inside the
     * canonical instance directory. Separators and `.`/`..` are rejected so the
     * update can never escape the instance folder.
     */
    private fun resolveTarget(instanceDirectory: File, targetJarName: String): File {
        if (targetJarName.isBlank()) {
            throw IllegalArgumentException("The server JAR file name is blank.")
        }
        if (targetJarName == "." || targetJarName == "..") {
            throw IllegalArgumentException(
                "\"$targetJarName\" is not a server JAR file name."
            )
        }
        if (targetJarName.any { it == '/' || it == '\\' || it == '\u0000' }) {
            throw IllegalArgumentException(
                "\"$targetJarName\" is not a plain file name. " +
                    "The server JAR must stay inside the instance folder."
            )
        }
        val target = File(instanceDirectory, targetJarName)
        val resolvedParent = target.canonicalFile.parentFile
        if (resolvedParent != instanceDirectory) {
            throw IllegalArgumentException(
                "The server JAR path must stay inside the instance folder."
            )
        }
        return target
    }

    /** Validates that the picked file exists and is a regular file. */
    private fun requireSourceFile(sourceJar: File) {
        if (!sourceJar.exists()) {
            throw IllegalArgumentException(
                "The selected file does not exist: \"${sourceJar.name}\". " +
                    "Pick the server .jar file again."
            )
        }
        if (!sourceJar.isFile) {
            throw IllegalArgumentException(
                "\"${sourceJar.name}\" is a folder, not a .jar file. " +
                    "Pick the server .jar file."
            )
        }
    }

    /**
     * True when [sourceJar] and [target] point at the same file, including via
     * relative paths, symlinks or hard links.
     */
    private fun isSameFile(sourceJar: File, target: File): Boolean = try {
        if (sourceJar.canonicalFile == target.canonicalFile) {
            true
        } else if (target.exists()) {
            Files.isSameFile(sourceJar.canonicalFile.toPath(), target.canonicalFile.toPath())
        } else {
            false
        }
    } catch (e: IOException) {
        sourceJar.absoluteFile == target.absoluteFile
    }

    /** Runs [inspect] and turns its result into a usable inspection or a user-facing failure. */
    private fun inspectAsServer(
        sourceJar: File,
        inspect: (File) -> InspectionResult
    ): JarInspection {
        val result = try {
            inspect(sourceJar)
        } catch (e: Exception) {
            throw IOException(
                "Could not read the selected JAR \"${sourceJar.name}\": " +
                    (e.message ?: e.javaClass.simpleName),
                e
            )
        }
        return when (result) {
            is InspectionResult.Success -> result.inspection
            is InspectionResult.Failure -> throw IllegalArgumentException(
                "The selected file is not a usable server JAR: ${result.message}"
            )
        }
    }

    /** Deterministic temp name inside the instance directory. */
    private fun tempName(stamp: Long): String = "$TEMP_PREFIX$stamp$TEMP_SUFFIX"

    /** True for the namespaced temp files this helper writes. */
    private fun isTempName(name: String): Boolean =
        name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)
}
