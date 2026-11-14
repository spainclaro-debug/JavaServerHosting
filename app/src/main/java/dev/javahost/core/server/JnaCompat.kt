package dev.javahost.core.server

import android.content.Context
import java.io.File

/**
 * Supplies the bionic build of JNA's dispatcher to Minecraft-family servers.
 *
 * Paper/CraftBukkit initialise `com.sun.jna.Native` while collecting hardware
 * information (oshi).  The stock `libjnidispatch.so` bundled inside the server
 * JAR is built for glibc, so on Android the class initialisation fails with
 * `NoClassDefFoundError: Could not initialize class com.sun.jna.Native` and the
 * server never reaches "Done".  Pointing `-Djna.boot.library.path` at a bionic
 * `libjnidispatch.so` fixes that; the loader checks that directory before the
 * JAR's own copy.
 *
 * JNA's native library and its Java classes negotiate an ABI version.  A 5.13
 * Java library refuses a 5.19 native (ABI 6.1.6 vs 7.0.0) with an "incompatible
 * JNA native library" warning, so the packaged dispatcher must match the JNA
 * version the server actually ships.  Paper downloads `libraries/` on first
 * boot; before that directory exists we simply don't add the property (Paper
 * still boots, it just warns).
 *
 * The dispatchers are packaged as assets (arm64-v8a, Apache-2.0) and copied
 * into the instance's working directory, because app-private storage is the
 * only place the child JVM may execute libraries from.  Every failure here is
 * non-fatal: the launcher simply does not add the system property.
 */
object JnaCompat {

    private const val DIR_NAME = "jna"
    private const val FILE_NAME = "libjnidispatch.so"
    private const val MARKER_NAME = ".jna-version"

    /** Asset used for JNA 5.x up to and including 5.13 (native ABI 6.1.6). */
    private const val ASSET_5_13 = "jna/5.13/libjnidispatch.so"

    /** Asset used for JNA 5.14+ (native ABI 7.0.0). */
    private const val ASSET_5_19 = "jna/5.19/libjnidispatch.so"

    private val JNA_JAR: Regex = Regex("""^jna(?:-platform)?-(\d+)\.(\d+)(?:\.\d+)?\.jar$""")

    /**
     * Ensures `<instanceDir>/jna/libjnidispatch.so` exists, executable and of a
     * version matching the server's bundled JNA.
     *
     * @return the `jna` directory to pass to `-Djna.boot.library.path`, or null
     *   when no dispatcher could be prepared (caller keeps launching anyway).
     */
    @Synchronized
    fun prepare(context: Context, instanceDir: File): File? {
        return try {
            val version = detectJnaVersion(File(instanceDir, "libraries")) ?: return null
            val assetPath = assetFor(version) ?: return null
            val dir = File(instanceDir, DIR_NAME)
            val target = File(dir, FILE_NAME)
            val marker = File(dir, MARKER_NAME)
            val tag = versionTag(assetPath)
            val assetLength = assetLength(context, assetPath)
            val markerMatches = runCatching {
                marker.isFile && marker.readText(Charsets.UTF_8).trim() == tag
            }.getOrDefault(false)
            val sizeMatches = assetLength == null || target.length() == assetLength
            if (target.isFile && target.length() > 0L && markerMatches && sizeMatches) {
                target.setExecutable(true, false)
                return dir
            }
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val tmp = File(dir, "$FILE_NAME.tmp")
            context.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() <= 0L) {
                tmp.delete()
                return null
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.setExecutable(true, false)
            runCatching { marker.writeText(tag, Charsets.UTF_8) }
            dir
        } catch (t: Throwable) {
            // Missing asset, read-only storage, weird ROM... launching without
            // the property is still correct for vanilla and generic servers.
            null
        }
    }

    /**
     * Finds the JNA version a Paper/Spigot server ships under `libraries/`.
     *
     * Handles the Maven layout Paper uses
     * (`libraries/net/java/dev/jna/jna/<ver>/jna-<ver>.jar`) as well as a plain
     * `libraries/jna-<ver>.jar`.  Returns the highest version found, or null
     * while the server has not downloaded JNA yet.
     */
    fun detectJnaVersion(libraries: File): Pair<Int, Int>? {
        if (!libraries.isDirectory) return null
        var best: Pair<Int, Int>? = null
        val files = runCatching {
            libraries.walkTopDown().filter { it.isFile }.toList()
        }.getOrDefault(emptyList())
        for (file in files) {
            val match = JNA_JAR.matchEntire(file.name.lowercase()) ?: continue
            val major = match.groupValues[1].toIntOrNull() ?: continue
            val minor = match.groupValues[2].toIntOrNull() ?: continue
            val candidate = major to minor
            val current = best
            if (current == null ||
                candidate.first > current.first ||
                (candidate.first == current.first && candidate.second > current.second)
            ) {
                best = candidate
            }
        }
        return best
    }

    /** Maps a detected JNA version to the matching packaged dispatcher asset. */
    fun assetFor(version: Pair<Int, Int>): String? {
        val (major, minor) = version
        if (major != 5) return null
        return if (minor <= 13) ASSET_5_13 else ASSET_5_19
    }

    /** True when an instance-local JNA dispatcher already exists. */
    fun hasLocalDispatcher(instanceDir: File): Boolean =
        File(File(instanceDir, DIR_NAME), FILE_NAME).let { it.isFile && it.length() > 0 }

    /** Version tag stored next to the copied dispatcher, e.g. "5.13". */
    private fun versionTag(assetPath: String): String =
        assetPath.substringAfter("jna/").substringBefore('/')

    /** Asset length when it can be determined; null when assets are compressed. */
    private fun assetLength(context: Context, assetPath: String): Long? = try {
        context.assets.openFd(assetPath).use { it.length }
    } catch (t: Throwable) {
        null
    }
}
