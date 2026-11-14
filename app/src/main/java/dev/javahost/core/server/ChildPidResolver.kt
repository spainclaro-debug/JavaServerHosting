package dev.javahost.core.server

import java.io.File

/**
 * Best-effort lookup of a child [Process]'s OS pid.
 *
 * `java.lang.Process.pid()` is not part of the Android SDK (the platform only
 * exposes `waitFor`, `isAlive` and `destroyForcibly`), but the launcher needs
 * the pid for `jcmd` heap sampling and `/proc` RSS reads.  Two independent
 * strategies are used:
 *
 *  1. reflectively read the hidden `pid` field of the platform's Process
 *     implementation (works on most builds, including when the field is only
 *     greylisted), and
 *  2. if that fails, scan `/proc` for a direct child of this process whose
 *     comm is `java` - which is exactly the server JVM we just spawned.
 *
 * Both are best-effort: null is a valid answer and stats simply omit the pid.
 */
internal object ChildPidResolver {

    /** Resolves the pid of [process], or null when it cannot be determined. */
    fun resolve(process: Process): Long? {
        reflectPid(process)?.let { if (it > 0L) return it }
        return scanProcForJavaChild()
    }

    private fun reflectPid(process: Process): Long? = try {
        var type: Class<*>? = process.javaClass
        var field: java.lang.reflect.Field? = null
        while (type != null && field == null) {
            field = runCatching { type.getDeclaredField("pid") }.getOrNull()
            type = type.superclass
        }
        if (field == null) {
            null
        } else {
            field.isAccessible = true
            (field.get(process) as? Number)?.toLong()
        }
    } catch (t: Throwable) {
        null
    }

    private fun scanProcForJavaChild(): Long? = try {
        val self = selfPid() ?: return null
        val proc = File("/proc")
        val entries = proc.listFiles { file -> file.name.all { it.isDigit() } } ?: return null
        var found: Long? = null
        for (entry in entries) {
            val stat = File(entry, "stat")
            val line = runCatching { stat.readText() }.getOrNull() ?: continue
            val close = line.lastIndexOf(')')
            if (close < 0) continue
            val comm = line.substringAfter('(').substringBeforeLast(')').trim('"')
            if (comm != "java") continue
            val fields = line.substring(close + 1).trim().split(Regex("\\s+"))
            val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
            if (ppid != self) continue
            found = entry.name.toLongOrNull()
            if (found != null) break
        }
        found
    } catch (t: Throwable) {
        null
    }

    /** This process's pid via the /proc/self symlink (no Android dependency). */
    private fun selfPid(): Int? = try {
        val self = File("/proc/self")
        val target = self.canonicalFile.name
        target.toIntOrNull()
    } catch (t: Throwable) {
        null
    }
}
