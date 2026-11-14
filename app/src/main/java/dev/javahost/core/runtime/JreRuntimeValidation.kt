package dev.javahost.core.runtime

import java.io.File
import java.nio.file.Files

/**
 * Checks applied to user-supplied runtimes before they are installed.
 *
 * Android uses bionic, not glibc. An official Oracle/Adoptium **Linux** JDK is
 * an aarch64 ELF too, so a file-type check is not enough; its dynamic
 * interpreter is `/lib/ld-linux-aarch64.so.1`, which Android cannot load. The
 * bundled/imported runtime must have been built against bionic, in which case
 * its `bin/java` names `/system/bin/linker64` as the ELF interpreter.
 */
object JreRuntimeValidation {

    const val MAX_SEARCH_DEPTH = 5

    /** Finds the shallowest directory containing `bin/java`, bounded and symlink-safe. */
    fun locateJavaHome(root: File): File? {
        if (!root.isDirectory) return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrDefault(root)
        if (File(root, "bin/java").isFile) return root
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (depth >= MAX_SEARCH_DEPTH) continue
            val children = runCatching { dir.listFiles()?.sortedBy { it.name } }.getOrNull() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                val canonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
                if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) continue
                if (Files.isSymbolicLink(child.toPath())) continue
                if (File(child, "bin/java").isFile) return child
                queue.add(child to depth + 1)
            }
        }
        return null
    }

    /**
     * True when `bin/java` looks like an Android/bionic executable. Reads only
     * the first 8 KiB, so it is cheap and safe for a multi-hundred-MB runtime.
     */
    fun looksLikeAndroidBionic(javaExecutable: File): Boolean {
        if (!javaExecutable.isFile || javaExecutable.length() < 4) return false
        return runCatching {
            javaExecutable.inputStream().use { input ->
                val buffer = ByteArray(8192)
                val read = input.read(buffer)
                if (read <= 0) return false
                val text = String(buffer, 0, read, Charsets.ISO_8859_1)
                text.contains("/system/bin/linker")
            }
        }.getOrDefault(false)
    }

    /** `OS_ARCH` from the runtime's release file, if present. */
    fun architecture(home: File): String? =
        ReleaseFile.readMap(home)["OS_ARCH"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isArm64(architecture: String?): Boolean =
        architecture == null || architecture in setOf("aarch64", "arm64", "arm64-v8a")
}
