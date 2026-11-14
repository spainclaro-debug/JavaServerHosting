package dev.javahost.core.runtime

import android.system.Os
import java.io.File
import java.nio.file.Files

/**
 * Thin abstraction over the few OS-specific file operations the installer needs
 * (`chmod`, symlinks, execute-bit checks).
 *
 * Keeping this behind an interface is what lets the archive/extraction logic be
 * unit-tested on a plain JVM, where `android.system.Os` is not usable.
 */
interface FileSystemOps {
    fun chmod(file: File, mode: Int)

    /** Creates (or replaces) a symbolic link at [link] pointing at [target]. */
    fun createSymbolicLink(link: File, target: String)

    fun isExecutable(file: File): Boolean
}

/** Production implementation backed by [Os] and NIO. */
object AndroidFileSystemOps : FileSystemOps {

    const val MODE_DIR: Int = 0b111_101_101 // 0755
    const val MODE_EXEC: Int = 0b111_101_101 // 0755
    const val MODE_FILE: Int = 0b110_100_100 // 0644

    override fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (e: Exception) {
            // Best effort: permissions are repaired by ensureExecutable() later.
            // Reporting every failure would make installs fail on odd filesystems.
        }
    }

    override fun createSymbolicLink(link: File, target: String) {
        link.parentFile?.mkdirs()
        link.delete()
        Files.createSymbolicLink(link.toPath(), link.parentFile!!.toPath().fileSystem.getPath(target))
    }

    override fun isExecutable(file: File): Boolean = runCatching { file.canExecute() }.getOrDefault(false)
}
