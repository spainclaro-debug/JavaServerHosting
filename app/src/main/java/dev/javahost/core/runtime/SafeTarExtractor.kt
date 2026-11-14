package dev.javahost.core.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileOutputStream

/** What to do with one tar entry. Produced by a [TarEntryPolicy]. */
sealed interface TarAction {
    data class WriteFile(val relativePath: String, val executable: Boolean = false) : TarAction
    data class MakeDirectory(val relativePath: String) : TarAction
    data class MakeSymlink(val relativePath: String, val target: String) : TarAction
    data object Skip : TarAction
}

/** Maps an archive entry (path, type) to a safe write action. */
fun interface TarEntryPolicy {
    fun decide(entry: TarArchiveEntry): TarAction
}

/**
 * Writes a tar stream below [destination] while enforcing path safety.
 *
 * Security rules:
 *  - file/directory paths must be relative and may not contain `..`;
 *  - the resolved canonical path must stay below [destination];
 *  - symlinks with absolute or escaping targets are skipped (a broken absolute
 *    symlink is never followed/written on the device);
 *  - hard links and device nodes are skipped (not needed by JRE archives).
 *
 * All file types and permissions go through [FileSystemOps], which keeps this
 * class free of Android APIs and unit-testable on a plain JVM.
 */
class SafeTarExtractor(
    private val fs: FileSystemOps,
    private val onWarning: (String) -> Unit = {}
) {

    fun extract(
        tar: TarArchiveInputStream,
        destination: File,
        policy: TarEntryPolicy
    ) {
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw JreInstallException("Cannot create extraction directory ${destination.path}")
        }
        var entry: TarArchiveEntry? = tar.nextEntry
        while (entry != null) {
            applyAction(tar, destination, entry, policy.decide(entry))
            entry = tar.nextEntry
        }
    }

    private fun applyAction(
        tar: TarArchiveInputStream,
        destination: File,
        entry: TarArchiveEntry,
        action: TarAction
    ) {
        when (action) {
            is TarAction.Skip -> Unit

            is TarAction.MakeDirectory -> {
                val dir = ArchivePaths.resolveChild(destination, action.relativePath)
                if (dir.exists() && !dir.isDirectory) {
                    throw ArchiveFormatException(
                        "Cannot extract directory ${action.relativePath}: a file with that name already exists."
                    )
                }
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw JreInstallException("Cannot create directory ${dir.path}")
                }
                fs.chmod(dir, AndroidFileSystemOps.MODE_DIR)
            }

            is TarAction.MakeSymlink -> {
                val link = ArchivePaths.resolveChild(destination, action.relativePath)
                val target = ArchivePaths.safeLinkTarget(destination, action.relativePath, action.target)
                if (target == null) {
                    onWarning(
                        "Skipped unsafe symbolic link '${action.relativePath}' -> " +
                            "'${action.target}' (points outside the runtime directory)."
                    )
                    return
                }
                link.parentFile?.mkdirs()
                link.delete()
                fs.createSymbolicLink(link, target)
            }

            is TarAction.WriteFile -> {
                val out = ArchivePaths.resolveChild(destination, action.relativePath)
                out.parentFile?.mkdirs()
                if (out.exists()) {
                    if (out.isDirectory) {
                        throw ArchiveFormatException(
                            "Cannot extract file ${action.relativePath}: a directory with that name already exists."
                        )
                    }
                    if (!out.delete()) {
                        throw JreInstallException("Cannot replace existing file ${out.path}")
                    }
                }
                FileOutputStream(out).use { stream ->
                    copyBounded(tar, stream, entry.size, action.relativePath)
                }
                fs.chmod(
                    out,
                    if (action.executable) AndroidFileSystemOps.MODE_EXEC else AndroidFileSystemOps.MODE_FILE
                )
            }
        }
    }

    private fun copyBounded(
        tar: TarArchiveInputStream,
        out: FileOutputStream,
        expectedSize: Long,
        displayName: String
    ) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (written < expectedSize) {
            val toRead = minOf(buffer.size.toLong(), expectedSize - written).toInt()
            val read = tar.read(buffer, 0, toRead)
            if (read < 0) {
                throw ArchiveFormatException(
                    "Truncated archive: '${displayName}' ended after $written of $expectedSize bytes."
                )
            }
            out.write(buffer, 0, read)
            written += read
        }
    }
}
