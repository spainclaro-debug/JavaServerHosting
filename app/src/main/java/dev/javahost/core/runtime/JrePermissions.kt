package dev.javahost.core.runtime

import java.io.File
import java.nio.file.Files

/**
 * Permission normalization for an installed JRE tree.
 *
 * Archive extraction preserves explicit modes for the files it knows about, but
 * user imports and "restored from backup" trees can lose execute bits. This
 * object centralizes the rules so `ensureExecutable()` and the installer agree.
 */
object JrePermissions {

    fun chmodTree(fs: FileSystemOps, home: File) {
        if (!home.isDirectory) return
        fs.chmod(home, AndroidFileSystemOps.MODE_DIR)
        home.walkTopDown().forEach { file ->
            if (file == home || isSymbolicLink(file)) return@forEach
            if (file.isDirectory) {
                fs.chmod(file, AndroidFileSystemOps.MODE_DIR)
            } else if (needsExecuteBit(home, file)) {
                fs.chmod(file, AndroidFileSystemOps.MODE_EXEC)
            }
        }
    }

    /**
     * Files that must be executable for the JVM to start: everything in `bin/`,
     * the native `jexec`/`jspawnhelper` helpers and the bundled shared objects.
     */
    fun needsExecuteBit(home: File, file: File): Boolean {
        val rel = runCatching { file.relativeTo(home).invariantSeparatorsPath }.getOrNull() ?: return false
        return rel.startsWith("bin/") ||
            rel == "lib/jexec" ||
            rel == "lib/jspawnhelper" ||
            rel.endsWith(".so") ||
            rel.contains(".so.")
    }

    fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /** Sum of regular-file sizes; symlinks are not followed twice. */
    fun installedBytes(home: File): Long {
        if (!home.isDirectory) return 0L
        var total = 0L
        home.walkTopDown().forEach { file ->
            if (file.isFile && !isSymbolicLink(file)) total += file.length()
        }
        return total
    }
}
