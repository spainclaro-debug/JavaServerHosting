package dev.javahost.core.runtime

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds synthetic `.deb`/tar/zip archives on the fly so extraction tests are
 * fully hermetic: no network, no fixture blobs checked into the repo.
 *
 * The generated structures mirror real Termux packages: an `ar` container with
 * `debian-binary`, `control.tar.xz` and `data.tar.xz`, whose entries live under
 * `./data/data/com.termux/files/usr/...`.
 */
object SyntheticArchives {

    sealed interface TarItem {
        data class Dir(val name: String) : TarItem
        data class File(val name: String, val content: ByteArray, val mode: Int = 0b110_100_100) : TarItem
        data class Symlink(val name: String, val target: String) : TarItem
    }

    fun dir(name: String): TarItem = TarItem.Dir(name)
    fun file(name: String, content: String, mode: Int = 0b110_100_100): TarItem =
        TarItem.File(name, content.toByteArray(), mode)
    fun file(name: String, content: ByteArray, mode: Int = 0b110_100_100): TarItem =
        TarItem.File(name, content, mode)
    fun symlink(name: String, target: String): TarItem = TarItem.Symlink(name, target)

    fun tar(items: List<TarItem>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (item in items) {
                when (item) {
                    is TarItem.Dir -> {
                        val entry = TarArchiveEntry(item.name.trimEnd('/') + "/")
                        entry.mode = 0b111_101_101
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    }
                    is TarItem.File -> {
                        val entry = TarArchiveEntry(item.name)
                        entry.size = item.content.size.toLong()
                        entry.mode = item.mode
                        tar.putArchiveEntry(entry)
                        tar.write(item.content)
                        tar.closeArchiveEntry()
                    }
                    is TarItem.Symlink -> {
                        val entry = TarArchiveEntry(item.name, TarConstants.LF_SYMLINK)
                        entry.linkName = item.target
                        entry.mode = 0b111_111_111
                        tar.putArchiveEntry(entry)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return out.toByteArray()
    }

    fun tarXz(items: List<TarItem>): ByteArray = compressXz(tar(items))

    fun tarGz(items: List<TarItem>): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip -> gzip.write(tar(items)) }
        return out.toByteArray()
    }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun compressXz(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        XZCompressorOutputStream(out).use { xz -> xz.write(bytes) }
        return out.toByteArray()
    }

    fun ar(members: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ArArchiveOutputStream(out).use { ar ->
            for ((name, body) in members) {
                val entry = ArArchiveEntry(name, body.size.toLong())
                ar.putArchiveEntry(entry)
                ar.write(body)
                ar.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Writes a byte-exact `ar` archive, including names with the trailing `/`
     * that dpkg-deb uses (commons-compress normalizes those away).
     */
    fun rawAr(members: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray())
        for ((name, body) in members) {
            val header = ByteArray(60) { ' '.code.toByte() }
            writeField(header, 0, 16, name)
            writeField(header, 16, 12, "0")
            writeField(header, 28, 6, "0")
            writeField(header, 34, 6, "0")
            writeField(header, 40, 8, "100644")
            writeField(header, 48, 10, body.size.toString())
            header[58] = '`'.code.toByte()
            header[59] = '\n'.code.toByte()
            out.write(header)
            out.write(body)
            if (body.size % 2 == 1) out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun writeField(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray()
        val count = minOf(bytes.size, length)
        System.arraycopy(bytes, 0, target, offset, count)
    }

    /** A Debian package with the given `data.tar.xz` payload. */
    fun deb(dataTarXz: ByteArray, controlTarXz: ByteArray = compressXz(tar(emptyList()))): ByteArray =
        ar(
            listOf(
                "debian-binary" to "2.0\n".toByteArray(),
                "control.tar.xz" to controlTarXz,
                "data.tar.xz" to dataTarXz
            )
        )
}
