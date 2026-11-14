package dev.javahost.core.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** What to take out of a Termux `.deb`'s `data.tar.*`. */
sealed interface PackageExtraction {
    /** The `openjdk-<major>` package: map the JRE home into the staging root. */
    data class OpenJdk(val major: Int) : PackageExtraction

    /** A native library package: keep only the Termux `lib/` shared objects. */
    data object SharedLibraries : PackageExtraction

    /** `ca-certificates-java`: overlay its `cacerts` as `lib/security/cacerts`. */
    data object JavaCacerts : PackageExtraction
}

/**
 * Extracts Termux `.deb` packages into a JRE staging directory.
 *
 * A `.deb` is an `ar` archive with (usually compressed) `data.tar.*` inside.
 * [ArArchiveReader] streams that inner member, then Commons Compress + XZ parse
 * the tarball. Only paths under `data/data/com.termux/files/usr/` are
 * considered, and each package type maps into the JRE layout:
 *
 *  - `openjdk-N`   -> everything below `<prefix>/lib/jvm/java-N-openjdk/`
 *                     becomes the JRE home; jmods/demo/include/man/lib/ct.sym
 *                     are trimmed.
 *  - libraries     -> `<prefix>/lib/libfoo.so*` are flattened into `<home>/lib`.
 *  - ca certs      -> `<prefix>/etc/tls/certs/java/cacerts` replaces the JRE's
 *                     absolute symlink at `<home>/lib/security/cacerts`.
 *
 * Everything runs on the caller's thread; callers wrap this in
 * `Dispatchers.IO`. Progress is reported as compressed bytes consumed inside
 * the inner archive.
 */
class DebExtractor(
    private val fs: FileSystemOps = AndroidFileSystemOps,
    private val onWarning: (String) -> Unit = {}
) {

    fun extract(
        deb: File,
        extraction: PackageExtraction,
        destination: File,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> }
    ) {
        if (!deb.isFile) {
            throw JreInstallException("Package file is missing: ${deb.path}")
        }
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw JreInstallException("Cannot create staging directory ${destination.path}")
        }
        val policy = policyFor(extraction)
        var foundData = false
        try {
            ArArchiveReader(BufferedInputStream(FileInputStream(deb), BUFFER_SIZE)).use { reader ->
                while (true) {
                    val member = reader.nextEntry() ?: break
                    if (!member.isDataArchive) continue
                    if (member.size <= 0) {
                        throw ArchiveFormatException(
                            "${deb.name} contains an empty ${member.name}; the download is corrupted."
                        )
                    }
                    foundData = true
                    val counting = ProgressInputStream(
                        reader.entryData(), member.size, onProgress
                    )
                    val decompressed = openDecompressed(member.name, BufferedInputStream(counting, BUFFER_SIZE))
                    TarArchiveInputStream(decompressed).use { tar ->
                        SafeTarExtractor(fs, onWarning).extract(tar, destination, policy)
                    }
                    break
                }
            }
        } catch (e: JreInstallException) {
            throw e
        } catch (e: Exception) {
            throw ArchiveFormatException(
                "Failed to unpack ${deb.name}: ${e.message ?: e.javaClass.simpleName}", e
            )
        }
        if (!foundData) {
            throw ArchiveFormatException(
                "${deb.name} is not a valid Debian package: no data.tar.* member found."
            )
        }
    }

    private fun openDecompressed(memberName: String, raw: InputStream): InputStream = when {
        memberName.endsWith(".tar.xz") -> XZInputStream(raw)
        memberName.endsWith(".tar.gz") -> GZIPInputStream(raw)
        memberName.endsWith(".tar") -> raw
        else -> throw ArchiveFormatException(
            "Unsupported package data format '$memberName' in this mirror. " +
                "JavaHost expects .tar.xz packages from Termux."
        )
    }

    private fun policyFor(extraction: PackageExtraction): TarEntryPolicy = when (extraction) {
        is PackageExtraction.OpenJdk -> openJdkPolicy(extraction.major)
        PackageExtraction.SharedLibraries -> sharedLibraryPolicy()
        PackageExtraction.JavaCacerts -> javaCacertsPolicy()
    }

    private fun openJdkPolicy(major: Int): TarEntryPolicy {
        val jreDirInTermux = "lib/jvm/java-$major-openjdk"
        return TarEntryPolicy { entry ->
            val tarPath = ArchivePaths.normalizeEntryName(entry.name)
            if (tarPath.isEmpty() || !tarPath.startsWith(TERMUX_USR_PREFIX)) {
                TarAction.Skip
            } else {
                val rel = tarPath.removePrefix(TERMUX_USR_PREFIX)
                if (!ArchivePaths.isInsideOrEqual(rel, jreDirInTermux)) {
                    TarAction.Skip
                } else {
                    val homeRel = rel.removePrefix(jreDirInTermux).trimStart('/')
                    when {
                        homeRel.isEmpty() -> TarAction.Skip // the JRE root maps to staging itself
                        isTrimmed(homeRel) -> TarAction.Skip
                        // The deb ships this as an absolute symlink into Termux's
                        // prefix; ca-certificates-java provides the real file.
                        ArchivePaths.isInsideOrEqual(homeRel, "lib/security/cacerts") -> TarAction.Skip
                        entry.isSymbolicLink -> TarAction.MakeSymlink(homeRel, entry.linkName)
                        entry.isLink -> {
                            onWarning("Skipped hard link '$homeRel' inside the OpenJDK package.")
                            TarAction.Skip
                        }
                        entry.isDirectory || entry.name.endsWith("/") ->
                            TarAction.MakeDirectory(homeRel)
                        entry.isFile || entry.size > 0 ->
                            TarAction.WriteFile(homeRel, executable = isExecutableJrePath(homeRel))
                        else -> TarAction.Skip
                    }
                }
            }
        }
    }

    private fun sharedLibraryPolicy(): TarEntryPolicy = TarEntryPolicy { entry ->
        val tarPath = ArchivePaths.normalizeEntryName(entry.name)
        if (tarPath.isEmpty() || !tarPath.startsWith(TERMUX_USR_PREFIX)) {
            TarAction.Skip
        } else {
            val rel = tarPath.removePrefix(TERMUX_USR_PREFIX)
            if (!rel.startsWith("lib/")) {
                TarAction.Skip
            } else {
                val base = rel.removePrefix("lib/")
                when {
                    base.contains('/') || !base.contains(".so") || entry.isDirectory -> TarAction.Skip
                    entry.isSymbolicLink -> {
                        val target = entry.linkName.substringAfterLast('/')
                        if (target.isEmpty()) TarAction.Skip
                        else TarAction.MakeSymlink("lib/$base", target)
                    }
                    entry.isLink -> TarAction.Skip
                    entry.isFile -> TarAction.WriteFile("lib/$base", executable = false)
                    else -> TarAction.Skip
                }
            }
        }
    }

    private fun javaCacertsPolicy(): TarEntryPolicy = TarEntryPolicy { entry ->
        val tarPath = ArchivePaths.normalizeEntryName(entry.name)
        if (tarPath.isEmpty() || !tarPath.startsWith(TERMUX_USR_PREFIX)) {
            TarAction.Skip
        } else {
            val rel = tarPath.removePrefix(TERMUX_USR_PREFIX)
            when {
                rel != CACERTS_IN_TERMUX -> TarAction.Skip
                entry.isSymbolicLink -> {
                    onWarning("ca-certificates-java shipped cacerts as a symlink; ignoring it.")
                    TarAction.Skip
                }
                entry.isFile -> TarAction.WriteFile("lib/security/cacerts", executable = false)
                else -> TarAction.Skip
            }
        }
    }

    private fun isTrimmed(homeRel: String): Boolean =
        TRIMMED_PREFIXES.any { ArchivePaths.isInsideOrEqual(homeRel, it) }

    private fun isExecutableJrePath(homeRel: String): Boolean =
        homeRel.startsWith("bin/") ||
            homeRel == "lib/jexec" ||
            homeRel == "lib/jspawnhelper" ||
            homeRel.endsWith(".so") ||
            homeRel.contains(".so.")

    /** Forwards compressed-byte progress from the inner archive to the caller. */
    private class ProgressInputStream(
        input: InputStream,
        private val total: Long,
        private val onProgress: (Long, Long) -> Unit
    ) : FilterInputStream(input) {
        private var done = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) report(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) report(read.toLong())
            return read
        }

        override fun skip(count: Long): Long {
            val skipped = super.skip(count)
            if (skipped > 0) report(skipped)
            return skipped
        }

        private fun report(delta: Long) {
            done += delta
            onProgress(done, total)
        }
    }

    companion object {
        const val TERMUX_USR_PREFIX = "data/data/com.termux/files/usr/"
        const val CACERTS_IN_TERMUX = "etc/tls/certs/java/cacerts"
        private const val BUFFER_SIZE = 64 * 1024
        private val TRIMMED_PREFIXES = listOf(
            "jmods",
            "demo",
            "include",
            "man",
            "lib/ct.sym"
        )
    }
}
