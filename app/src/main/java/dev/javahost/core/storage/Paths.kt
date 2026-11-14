package dev.javahost.core.storage

import android.content.Context
import java.io.File

/**
 * Single source of truth for the app-private directory layout.
 *
 * Everything a server can write lives below `files/javahost/instances/<id>`;
 * only JREs (`files/javahost/jre`) and transient downloads (cache) live
 * elsewhere. Because the whole tree is app-private, the SAF picker is only used
 * for importing a JAR / exporting logs, never as the server's working dir.
 */
class Paths(context: Context) {

    val root: File = File(context.filesDir, "javahost").apply { mkdirs() }
    val instancesDir: File = File(root, "instances").apply { mkdirs() }
    val jreDir: File = File(root, "jre").apply { mkdirs() }
    val logsRoot: File = File(root, "logs").apply { mkdirs() }
    val downloadsDir: File = File(context.cacheDir, "javahost-downloads").apply { mkdirs() }

    fun instanceDir(id: String): File = File(instancesDir, id)
    fun instanceMetaFile(id: String): File = File(instanceDir(id), "instance.json")

    /** Server working directory is the instance dir itself, so files are easy to browse. */
    fun workingDir(id: String): File = instanceDir(id).apply { mkdirs() }

    fun jarFile(id: String, fileName: String): File = File(instanceDir(id), fileName)
    fun eulaFile(id: String): File = File(instanceDir(id), "eula.txt")
    fun serverProperties(id: String): File = File(instanceDir(id), "server.properties")
    fun pluginsDir(id: String): File = File(instanceDir(id), "plugins").apply { mkdirs() }
    fun modsDir(id: String): File = File(instanceDir(id), "mods").apply { mkdirs() }
    fun logsDir(id: String): File = File(instanceDir(id), "logs").apply { mkdirs() }
    fun crashesDir(id: String): File = File(instanceDir(id), "crashes").apply { mkdirs() }
    fun latestLog(id: String): File = File(logsDir(id), "latest.log")
    fun previousLog(id: String): File = File(logsDir(id), "previous.log")

    /**
     * App-owned console transcript of the *child JVM's stdout*.
     *
     * Deliberately separate from the server's own `logs/latest.log`: Minecraft/
     * log4j and Paper write that file themselves, and the app must never rotate
     * or truncate it. The transcript is what the app tails, exports and quotes
     * in crash reports.
     */
    fun transcriptLog(id: String): File = File(logsDir(id), "console.log")

    /** Previous run of the app-owned transcript ([transcriptLog]). */
    fun transcriptPreviousLog(id: String): File = File(logsDir(id), "console.previous.log")
    fun lastCrashFile(id: String): File = File(crashDirSafe(id), "last-crash.txt")

    fun crashReport(id: String, timestamp: Long): File =
        File(crashDirSafe(id), "crash-$timestamp.txt")

    fun jreHome(major: Int): File = File(jreDir, "jre$major")
    fun jreJava(major: Int): File = File(jreHome(major), "bin/java")
    fun jreMarker(major: Int): File = File(jreDir, "jre$major.version")

    fun jreStageDir(major: Int): File = File(jreDir, "stage-jre$major")

    fun instanceLogsRoot(id: String): File = File(logsDir(id), "")

    fun isInstalled(id: String): Boolean = instanceDir(id).isDirectory

    fun newInstanceId(): String = java.util.UUID.randomUUID().toString()

    /** Delete all files belonging to an instance, including its working dir. */
    fun deleteInstanceFiles(id: String): Boolean {
        val dir = instanceDir(id)
        // Guard against path traversal: ids are UUIDs; refuse anything with a separator.
        if (id.contains('/') || id.contains('\\') || id == "." || id == "..") return false
        return !dir.exists() || dir.deleteRecursively()
    }

    private fun crashDirSafe(id: String): File = crashesDir(id)
}
