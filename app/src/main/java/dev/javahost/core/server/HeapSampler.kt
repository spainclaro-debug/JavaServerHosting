package dev.javahost.core.server

import java.io.File
import java.util.concurrent.TimeUnit

/** Where a [HeapSample] came from. */
enum class HeapSource { JCMD, PROC_RSS, NONE }

/**
 * One heap/RSS observation of the child JVM.
 *
 * [usedMb]/[totalMb] are only set when `jcmd GC.heap_info` answered; the RSS
 * fallback reports [rssMb] alone because resident memory is not heap memory.
 */
data class HeapSample(
    val usedMb: Int? = null,
    val totalMb: Int? = null,
    val rssMb: Long? = null,
    val source: HeapSource = HeapSource.NONE
)

/**
 * Samples the child JVM's heap without blocking the caller for long.
 *
 * Preferred source is `<jre>/bin/jcmd <pid> GC.heap_info` (strict timeout,
 * process forcibly destroyed on overrun); on any failure it falls back to
 * `/proc/<pid>/status` VmRSS, which is cheap but does not distinguish heap from
 * native memory.  The caller is expected to cache results (the manager samples
 * every ~15 s and reuses the sample for its ~2 s status ticks).
 */
class HeapSampler(
    private val javaHome: File?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Injectable for tests: (command, env, timeoutMs) -> stdout or null. */
    private val commandRunner: ((List<String>, Map<String, String>, Long) -> String?)? = null
) {

    /** Returns a sample, or null when neither jcmd nor /proc could be read. */
    fun sample(pid: Long): HeapSample? {
        if (pid <= 0L) return null
        val jcmd = javaHome?.let { File(it, "bin/jcmd") }
        if (jcmd != null && jcmd.isFile) {
            val runner = commandRunner ?: ::runCommand
            val output = runner(
                listOf(jcmd.absolutePath, pid.toString(), "GC.heap_info"),
                environment(),
                timeoutMs
            )
            parseHeapInfo(output)?.let { heap ->
                return heap.copy(rssMb = readRssMb(pid), source = HeapSource.JCMD)
            }
        }
        val rss = readRssMb(pid) ?: return null
        return HeapSample(rssMb = rss, source = HeapSource.PROC_RSS)
    }

    private fun environment(): Map<String, String> {
        val home = javaHome ?: return emptyMap()
        val path = home.absolutePath
        return mapOf(
            "JAVA_HOME" to path,
            "LD_LIBRARY_PATH" to "$path/lib:$path/lib/server",
            "PATH" to "$path/bin:/system/bin"
        )
    }

    private fun runCommand(command: List<String>, env: Map<String, String>, timeout: Long): String? {
        var process: Process? = null
        return try {
            val builder = ProcessBuilder(command)
            builder.redirectErrorStream(true)
            if (env.isNotEmpty()) builder.environment().putAll(env)
            process = builder.start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process?.inputStream?.bufferedReader()?.use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            if (output.length < MAX_CAPTURE_CHARS) {
                                output.append(line).append('\n')
                            }
                            line = reader.readLine()
                        }
                    }
                } catch (ignored: Throwable) {
                    // Reading may race with destroyForcibly; the output we got so
                    // far is still useful.
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                null
            } else {
                reader.join(READER_JOIN_MS)
                output.toString()
            }
        } catch (t: Throwable) {
            runCatching { process?.destroyForcibly() }
            null
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 2500L

        private const val MAX_CAPTURE_CHARS = 256 * 1024
        private const val READER_JOIN_MS = 300L

        private val TOTAL_USED: Regex = Regex("""total\s+(\d+)\s*K,\s*used\s+(\d+)\s*K""")
        private val USED_ONLY: Regex = Regex("""used\s+(\d+)\s*K""")
        private val VM_RSS: Regex = Regex("""VmRSS:\s+(\d+)\s+kB""")

        /** Parses `jcmd GC.heap_info` output; public for tests. */
        fun parseHeapInfo(output: String?): HeapSample? {
            if (output.isNullOrBlank()) return null
            val matches = TOTAL_USED.findAll(output).toList()
            if (matches.isNotEmpty()) {
                var totalK = 0L
                var usedK = 0L
                for (match in matches) {
                    totalK += match.groupValues[1].toLongOrNull() ?: 0L
                    usedK += match.groupValues[2].toLongOrNull() ?: 0L
                }
                return HeapSample(
                    usedMb = (usedK / 1024L).toInt(),
                    totalMb = (totalK / 1024L).toInt(),
                    source = HeapSource.JCMD
                )
            }
            val usedOnly = USED_ONLY.find(output) ?: return null
            val usedK = usedOnly.groupValues[1].toLongOrNull() ?: return null
            return HeapSample(usedMb = (usedK / 1024L).toInt(), source = HeapSource.JCMD)
        }

        /** Parses VmRSS from `/proc/<pid>/status` (kB); public for tests. */
        fun parseVmRssKb(statusText: String?): Long? {
            if (statusText.isNullOrEmpty()) return null
            val match = VM_RSS.find(statusText) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        /** Reads the child's RSS in MiB from /proc; null when unavailable. */
        fun readRssMb(pid: Long): Long? {
            if (pid <= 0L) return null
            val status = File("/proc/$pid/status")
            if (!status.isFile) return null
            val kb = parseVmRssKb(runCatching { status.readText() }.getOrNull()) ?: return null
            return (kb + 1023L) / 1024L
        }
    }
}
