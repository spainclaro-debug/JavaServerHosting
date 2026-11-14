package dev.javahost.core.server

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile

/**
 * Per-instance app-owned console transcript with rotation and crash-report
 * writing.
 *
 * The app writes every console line it reads from the child JVM here so a
 * post-mortem is still possible after the process is gone (and so the user can
 * browse/share the log).  This is **not** the server's own log: Minecraft/log4j
 * and Paper write `logs/latest.log` themselves, and rotating/truncating that
 * file from the app would destroy the real server log.  The transcript lives at
 * `logs/console.log` (see `Paths.transcriptLog`) and at ~2 MB is rotated to
 * `logs/console.previous.log`; `<instance>/logs/latest.log` is never touched.
 *
 * All methods are thread-safe and do only small, bounded IO.  Heavy callers
 * (the console reader, the crash handler) already run on a background
 * dispatcher; [readTail]/[writeCrashReport] are intentionally callable from any
 * thread because the public [dev.javahost.core.api.ServerController] API is not
 * suspend.
 */
class LogTailStore(
    private val transcriptFile: File,
    private val previousTranscriptFile: File,
    private val crashDir: File,
    private val lastCrashFile: File,
    private val rotationBytes: Long = DEFAULT_ROTATION_BYTES,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var bytesWritten = 0L

    /**
     * Starts a new logging session: an existing transcript is moved to
     * `console.previous.log` (so each run has its own file) and a fresh writer
     * opened.  The server's own `logs/latest.log` is never touched.
     */
    fun startSession() {
        synchronized(lock) {
            closeWriterLocked()
            transcriptFile.parentFile?.mkdirs()
            rotateExistingLocked()
            openWriterLocked()
        }
    }

    /** Appends one console line.  Never throws; drops the line on IO failure. */
    fun append(line: String) {
        if (line.isEmpty()) return
        synchronized(lock) {
            try {
                if (writer == null) {
                    transcriptFile.parentFile?.mkdirs()
                    rotateExistingLocked()
                    openWriterLocked()
                }
                val w = writer ?: return
                w.write(line)
                w.newLine()
                // Flushed per line so a force-killed JVM still leaves a usable
                // tail behind for the crash report.
                w.flush()
                bytesWritten += line.length + 1L
                if (bytesWritten >= rotationBytes) rotateNowLocked()
            } catch (t: Throwable) {
                closeWriterLocked()
            }
        }
    }

    /** Returns the last [maxLines] lines, falling back to the previous transcript. */
    fun readTail(maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        synchronized(lock) { runCatching { writer?.flush() } }
        val latest = tailOf(transcriptFile, maxLines)
        if (latest.isNotEmpty()) return latest
        return tailOf(previousTranscriptFile, maxLines)
    }

    /** Last modification time of the log file (0 when absent). */
    fun lastModified(): Long = runCatching {
        if (transcriptFile.isFile) transcriptFile.lastModified() else previousTranscriptFile.lastModified()
    }.getOrDefault(0L)

    fun close() {
        synchronized(lock) { closeWriterLocked() }
    }

    /**
     * Writes `crashes/crash-<timestamp>.txt` and refreshes `crashes/last-crash.txt`.
     *
     * @return the crash report file on success, null on IO failure (the caller
     *   still surfaces the in-memory tail).
     */
    fun writeCrashReport(timestamp: Long, header: List<String>, tail: List<String>): File? {
        synchronized(lock) {
            return try {
                runCatching { writer?.flush() }
                crashDir.mkdirs()
                lastCrashFile.parentFile?.mkdirs()
                val reportFile = File(crashDir, "crash-$timestamp.txt")
                val text = buildString {
                    header.forEach { appendLine(it) }
                    appendLine()
                    appendLine("---- console tail (last ${tail.size} lines) ----")
                    tail.forEach { appendLine(it) }
                }
                reportFile.writeText(text, Charsets.UTF_8)
                if (lastCrashFile.absolutePath != reportFile.absolutePath) {
                    lastCrashFile.writeText(text, Charsets.UTF_8)
                }
                reportFile
            } catch (t: Throwable) {
                null
            }
        }
    }

    private fun rotateExistingLocked() {
        bytesWritten = 0L
        val existing = transcriptFile
        if (!existing.isFile || existing.length() <= 0L) {
            runCatching { existing.delete() }
            return
        }
        runCatching { previousTranscriptFile.delete() }
        if (!existing.renameTo(previousTranscriptFile)) {
            runCatching {
                existing.copyTo(previousTranscriptFile, overwrite = true)
                existing.delete()
            }
        }
        transcriptFile.parentFile?.mkdirs()
    }

    private fun rotateNowLocked() {
        closeWriterLocked()
        runCatching { previousTranscriptFile.delete() }
        if (!transcriptFile.renameTo(previousTranscriptFile)) {
            runCatching {
                transcriptFile.copyTo(previousTranscriptFile, overwrite = true)
                transcriptFile.delete()
            }
        }
        bytesWritten = 0L
        openWriterLocked()
    }

    private fun openWriterLocked() {
        runCatching {
            transcriptFile.parentFile?.mkdirs()
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(transcriptFile, true), Charsets.UTF_8)
            )
        }.onFailure { writer = null }
    }

    private fun closeWriterLocked() {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
    }

    companion object {
        const val DEFAULT_ROTATION_BYTES: Long = 2L * 1024L * 1024L
        const val DEFAULT_TAIL_BYTES: Long = 512L * 1024L

        /** Static form used by the UI to read a log without constructing a store. */
        fun tailOf(file: File, maxLines: Int, maxBytes: Long = DEFAULT_TAIL_BYTES): List<String> {
            if (maxLines <= 0 || !file.isFile) return emptyList()
            val length = runCatching { file.length() }.getOrDefault(0L)
            if (length <= 0L) return emptyList()
            return runCatching {
                val start = (length - maxBytes).coerceAtLeast(0L)
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray((length - start).toInt())
                    raf.readFully(buffer)
                    var text = String(buffer, Charsets.UTF_8)
                    if (start > 0L) {
                        // Only discard the first line when the chunk starts in
                        // the middle of a line; if it starts exactly on a line
                        // boundary every line in the chunk is complete.
                        raf.seek(start - 1L)
                        val previousByte = raf.read()
                        if (previousByte != '\n'.code) {
                            val firstNewline = text.indexOf('\n')
                            text = if (firstNewline >= 0) text.substring(firstNewline + 1) else ""
                        }
                    }
                    text.split('\n')
                        .map { it.trimEnd('\r') }
                        .filter { it.isNotEmpty() }
                        .takeLast(maxLines)
                }
            }.getOrDefault(emptyList())
        }
    }
}
