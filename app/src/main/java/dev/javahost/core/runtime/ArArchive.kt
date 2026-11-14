package dev.javahost.core.runtime

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** One member of a Unix `ar` archive (the outer container of a `.deb`). */
data class ArMember(
    val name: String,
    val size: Long,
    /** Raw textual mode field, e.g. `100644`; useful only for diagnostics. */
    val mode: String = ""
) {
    val isDebianBinary: Boolean get() = name == "debian-binary"
    val isControlArchive: Boolean get() = name.startsWith("control.tar")
    val isDataArchive: Boolean get() = name.startsWith("data.tar")
}

/**
 * Minimal, streaming reader for the Unix `ar` format used by Debian packages.
 *
 * Format: 8-byte magic (`!<arch>\n`) followed by fixed 60-byte member headers.
 * Member data is padded to an even offset. Debian packages use short names
 * (`debian-binary`, `control.tar.xz`, `data.tar.xz`) so GNU long-name tables do
 * not occur in practice; we still skip `/` and `//` members defensively.
 *
 * Streaming matters: the OpenJDK `.deb` is ~106 MB and `data.tar.xz` must be
 * decompressed on the fly rather than loaded into memory.
 */
class ArArchiveReader(input: InputStream) : AutoCloseable {

    private val stream: InputStream = input
    private var started = false
    private var currentRemaining = 0L
    private var currentPadding = 0
    private var closed = false

    /** Advances to and returns the next member header, or null at end of archive. */
    fun nextEntry(): ArMember? {
        check(!closed) { "ArArchiveReader is closed" }
        skipCurrent()
        if (!started) {
            val magic = ByteArray(MAGIC.size)
            readFully(magic, 0, magic.size)
            if (!magic.contentEquals(MAGIC)) {
                throw ArchiveFormatException(
                    "This file is not a Debian package: missing the 'ar' archive header."
                )
            }
            started = true
        }
        val header = ByteArray(HEADER_SIZE)
        val read = readUpTo(header, 0, HEADER_SIZE)
        if (read == 0) return null
        if (read < HEADER_SIZE) {
            throw ArchiveFormatException("Truncated Debian package: incomplete 'ar' member header.")
        }
        if (header[58] != '`'.code.toByte() || header[59] != '\n'.code.toByte()) {
            throw ArchiveFormatException(
                "Corrupt Debian package: bad 'ar' member header terminator."
            )
        }
        val rawName = ascii(header, 0, 16).trim()
        val size = ascii(header, 48, 10).trim().toLongOrNull()
            ?: throw ArchiveFormatException("Corrupt Debian package: invalid member size.")
        if (size < 0) throw ArchiveFormatException("Corrupt Debian package: negative member size.")
        val mode = ascii(header, 40, 8).trim()
        val name = normalizeMemberName(rawName)
        currentRemaining = size
        currentPadding = (size % 2L).toInt()
        return ArMember(name = name, size = size, mode = mode)
    }

    /**
     * Streams the current member's payload. The returned stream is bounded to
     * the member size; callers may close it early (the reader then skips to the
     * next header on the following [nextEntry] call).
     */
    fun entryData(): InputStream = BoundedInputStream(currentRemaining)

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stream.close() }
    }

    private fun skipCurrent() {
        if (currentRemaining > 0) skipFully(currentRemaining)
        currentRemaining = 0
        if (currentPadding > 0) {
            skipFully(currentPadding.toLong())
            currentPadding = 0
        }
    }

    private fun normalizeMemberName(raw: String): String = when {
        raw == "/" || raw == "//" -> raw // symbol table / long-name table: skipped by callers
        raw.startsWith("/") -> raw.trimStart('/') // GNU long name reference: not needed for .deb
        raw.endsWith("/") -> raw.dropLast(1)
        else -> raw
    }

    private fun readUpTo(buffer: ByteArray, offset: Int, length: Int): Int {
        var done = 0
        while (done < length) {
            val read = stream.read(buffer, offset + done, length - done)
            if (read < 0) break
            done += read
        }
        return done
    }

    private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        if (readUpTo(buffer, offset, length) != length) {
            throw EOFException("Truncated Debian package: unexpected end of file.")
        }
    }

    private fun skipFully(count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val read = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read < 0) throw ArchiveFormatException("Truncated Debian package: unexpected end of file.")
            remaining -= read
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private inner class BoundedInputStream(private var remaining: Long) : FilterInputStream(stream) {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) consume(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(length.toLong(), remaining).toInt()
            val read = super.read(buffer, offset, toRead)
            if (read > 0) consume(read.toLong())
            return read
        }

        override fun skip(count: Long): Long {
            val toSkip = minOf(count, remaining)
            val skipped = super.skip(toSkip)
            if (skipped > 0) consume(skipped)
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

        override fun close() {
            // Drain the remainder so the parent reader can continue safely, but
            // never close the underlying stream (it belongs to the ar reader).
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val read = super.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (read < 0) break
                consume(read.toLong())
            }
        }

        /**
         * Keeps the outer reader's accounting in sync so a following
         * [nextEntry] skips only the (already drained) padding, never the whole
         * member a second time.
         */
        private fun consume(count: Long) {
            remaining -= count
            currentRemaining = (currentRemaining - count).coerceAtLeast(0)
        }
    }

    private companion object {
        val MAGIC = byteArrayOf(
            '!'.code.toByte(), '<'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(),
            'c'.code.toByte(), 'h'.code.toByte(), '>'.code.toByte(), '\n'.code.toByte()
        )
        const val HEADER_SIZE = 60
    }
}
