package dev.javahost.core.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Archive formats accepted by [JreArchiveImporter.importArchive]. */
enum class JreArchiveFormat { TAR_XZ, TAR_GZ, TAR, ZIP, UNKNOWN }

/**
 * Offline escape hatch: imports a user-supplied JRE/JDK archive (`.tar.xz`,
 * `.tar.gz`, `.zip`) into a directory that the caller then atomically swaps into
 * place.
 *
 * The archive is unpacked below a private work directory, its JRE home is
 * located by searching (bounded depth) for `bin/java`, and the detected version
 * label is read from the home's `release` file. Extraction reuses
 * [SafeTarExtractor] so path traversal and unsafe symlinks are handled exactly
 * as for packaged Termux debs.
 */
class JreArchiveImporter(
    private val fs: FileSystemOps,
    private val onWarning: (String) -> Unit = {}
) {

    data class ImportedRuntime(
        /** Directory that contains `bin/java`; nested inside the work directory. */
        val home: File,
        /** `JAVA_VERSION` from the home's `release` file, if present. */
        val versionLabel: String?,
        val format: JreArchiveFormat
    )

    /**
     * Extracts [archive] into `<workDir>/extract` and returns the detected JRE.
     * The caller owns [workDir] and is responsible for deleting it afterwards.
     */
    fun extract(archive: File, workDir: File): ImportedRuntime {
        if (!archive.isFile) {
            throw JreInstallException("The selected JRE archive no longer exists: ${archive.path}")
        }
        if (archive.length() < 8) {
            throw JreInstallException("The selected JRE archive is empty or truncated: ${archive.name}")
        }
        if (!workDir.isDirectory && !workDir.mkdirs()) {
            throw JreInstallException("Cannot create a temporary directory for the import (${workDir.path}).")
        }
        val extractRoot = File(workDir, "extract")
        extractRoot.deleteRecursively()
        if (!extractRoot.mkdirs()) {
            throw JreInstallException("Cannot create a temporary directory for the import (${extractRoot.path}).")
        }
        val format = detectFormat(archive)
        try {
            when (format) {
                JreArchiveFormat.TAR_XZ -> extractTar(archive, extractRoot, Compression.XZ)
                JreArchiveFormat.TAR_GZ -> extractTar(archive, extractRoot, Compression.GZIP)
                JreArchiveFormat.TAR -> extractTar(archive, extractRoot, Compression.NONE)
                JreArchiveFormat.ZIP -> extractZip(archive, extractRoot)
                JreArchiveFormat.UNKNOWN -> throw ArchiveFormatException(
                    "Unsupported archive format for '${archive.name}'. " +
                        "JavaHost can import .tar.xz, .tar.gz and .zip JRE archives. " +
                        "If this is a .deb file, use the built-in installer instead."
                )
            }
        } catch (e: JreInstallException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveFormatException(
                "Failed to unpack '${archive.name}': ${e.message ?: e.javaClass.simpleName}", e
            )
        }
        val home = locateJavaHome(extractRoot)
            ?: throw JreInstallException(
                "This archive does not contain a usable Java runtime: no bin/java was found " +
                    "inside '${archive.name}'. Make sure it is an unmodified JRE/JDK distribution."
            )
        return ImportedRuntime(
            home = home,
            versionLabel = ReleaseFile.versionLabel(home),
            format = format
        )
    }

    fun detectFormat(archive: File): JreArchiveFormat {
        val header = ByteArray(512)
        var read = 0
        FileInputStream(archive).use { input ->
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
        }
        fun b(index: Int): Int = header[index].toInt() and 0xff
        if (read >= 6) {
            val xz = b(0) == 0xFD && b(1) == 0x37 && b(2) == 0x7A &&
                b(3) == 0x58 && b(4) == 0x5A && b(5) == 0x00
            if (xz) return JreArchiveFormat.TAR_XZ
        }
        if (read >= 2 && b(0) == 0x1F && b(1) == 0x8B) {
            return JreArchiveFormat.TAR_GZ
        }
        if (read >= 4 && b(0) == 0x50 && b(1) == 0x4B &&
            (b(2) == 0x03 || b(2) == 0x05 || b(2) == 0x07)
        ) {
            return JreArchiveFormat.ZIP
        }
        if (read >= 262) {
            val ustar = String(header, 257, 5, Charsets.US_ASCII)
            if (ustar == "ustar") return JreArchiveFormat.TAR
        }
        return JreArchiveFormat.UNKNOWN
    }

    fun locateJavaHome(root: File, maxDepth: Int = 4): File? {
        if (isJavaHome(root)) return root
        var level = listOf(root)
        repeat(maxDepth) {
            val next = ArrayList<File>()
            for (dir in level) {
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    if (!isRealDirectory(child)) continue
                    if (isJavaHome(child)) return child
                    next += child
                }
            }
            if (next.isEmpty()) return null
            level = next
        }
        return null
    }

    private fun isJavaHome(dir: File): Boolean = File(dir, "bin/java").isFile

    private fun isRealDirectory(file: File): Boolean =
        runCatching { Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)

    private fun extractTar(archive: File, destination: File, compression: Compression) {
        val raw = BufferedInputStream(FileInputStream(archive), 64 * 1024)
        val decompressed: InputStream = when (compression) {
            Compression.XZ -> XZInputStream(raw)
            Compression.GZIP -> GZIPInputStream(raw)
            Compression.NONE -> raw
        }
        try {
            TarArchiveInputStream(decompressed).use { tar ->
                SafeTarExtractor(fs, onWarning).extract(tar, destination, importPolicy())
            }
        } catch (e: JreInstallException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveFormatException(
                "Failed to read the tar archive '${archive.name}': " +
                    "${e.message ?: e.javaClass.simpleName}", e
            )
        }
    }

    private fun importPolicy(): TarEntryPolicy = TarEntryPolicy { entry ->
        if (entry.name.replace('\\', '/').startsWith("/")) {
            throw ArchiveFormatException(
                "Refusing to extract absolute path '${entry.name}' from the tar archive. " +
                    "This archive is not safe to import."
            )
        }
        val relative = ArchivePaths.normalizeEntryName(entry.name)
        when {
            relative.isEmpty() -> TarAction.Skip
            entry.isSymbolicLink -> TarAction.MakeSymlink(relative, entry.linkName)
            entry.isLink -> {
                onWarning("Skipped hard link '$relative' while importing the JRE archive.")
                TarAction.Skip
            }
            entry.isDirectory || entry.name.endsWith("/") -> TarAction.MakeDirectory(relative)
            entry.isFile -> TarAction.WriteFile(relative, executable = false)
            else -> TarAction.Skip
        }
    }

    private fun extractZip(archive: File, destination: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive), 64 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val rawName = entry.name
                if (rawName.replace('\\', '/').startsWith("/")) {
                    throw ArchiveFormatException(
                        "Refusing to extract absolute path '$rawName' from the zip archive."
                    )
                }
                val relative = ArchivePaths.normalizeEntryName(rawName)
                if (relative.isEmpty()) continue
                val out = ArchivePaths.resolveChild(destination, relative)
                if (entry.isDirectory) {
                    if (!out.isDirectory && !out.mkdirs()) {
                        throw JreInstallException("Cannot create directory ${out.path}")
                    }
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fileOut ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            fileOut.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private enum class Compression { XZ, GZIP, NONE }
}
