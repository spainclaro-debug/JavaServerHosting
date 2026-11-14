package dev.javahost.core.launch

import dev.javahost.core.model.ServerInstance
import java.io.File

/**
 * Immutable description of one child-JVM launch.
 *
 * Everything the runtime needs is resolved ahead of time: the java binary, the
 * exact argv list (never a shell string), the environment additions that make a
 * Termux OpenJDK work from app-private storage, and the working directory.
 */
data class LaunchPlan(
    /** Full argv, index 0 is the java executable. */
    val command: List<String>,
    /** Environment variables to add on top of the inherited process env. */
    val environment: Map<String, String>,
    val workingDir: File,
    val javaExecutable: File,
    val javaHome: File,
    val tmpDir: File,
    val memoryMb: Int
) {
    /** The launch command rendered for logs / crash reports (no shell quoting). */
    fun commandLine(): String = command.joinToString(" ")
}

/**
 * Builds [LaunchPlan]s for generic and Minecraft servers.
 *
 * Kept free of Android types so it can be unit-tested on a plain JVM.  Memory is
 * resolved/clamped by [clampMemoryMb]; everything else is taken from the
 * instance as-is.
 */
object LaunchPlanBuilder {

    /** Never start a JVM with a smaller heap than this. */
    const val MIN_HEAP_MB = 256

    /** Hard upper bound; the UI/device adviser should keep the default lower. */
    const val MAX_HEAP_MB = 16384

    /** Vanilla Minecraft accepts `nogui` as the console argument. */
    const val MINECRAFT_DEFAULT_ARGS = "nogui"

    /** Clamps a requested heap to the platform-safe range. */
    fun clampMemoryMb(requestedMb: Int, ceilingMb: Int = MAX_HEAP_MB): Int {
        val ceiling = ceilingMb.coerceIn(MIN_HEAP_MB, MAX_HEAP_MB)
        return requestedMb.coerceIn(MIN_HEAP_MB, ceiling)
    }

    /**
     * Resolves the heap for an instance: the instance value wins when positive,
     * otherwise the app default.  Always clamped to the sane range.
     */
    fun resolvedMemoryMb(instance: ServerInstance, defaultMb: Int, ceilingMb: Int = MAX_HEAP_MB): Int {
        val requested = when {
            instance.memoryMb > 0 -> instance.memoryMb
            defaultMb > 0 -> defaultMb
            else -> 1536
        }
        return clampMemoryMb(requested, ceilingMb)
    }

    /**
     * Builds the launch plan.
     *
     * @param memoryMb resolved heap; when null the instance/default value is
     *   used and clamped (pass an explicit value when the caller already knows
     *   the device ceiling).
     */
    fun build(
        instance: ServerInstance,
        workingDir: File,
        jarFile: File,
        javaExecutable: File,
        javaHome: File,
        memoryMb: Int? = null,
        /** Directory holding a bionic `libjnidispatch.so`; null when unavailable. */
        jnaDir: File? = null
    ): LaunchPlan {
        val heapMb = memoryMb?.let { clampMemoryMb(it) }
            ?: resolvedMemoryMb(instance, instance.memoryMb)

        val tmpDir = File(workingDir, "tmp")
        if (!tmpDir.isDirectory) tmpDir.mkdirs()
        val effectiveJnaDir = jnaDir ?: File(workingDir, "jna").takeIf { it.isDirectory }

        val command = ArrayList<String>(24)
        command.add(javaExecutable.absolutePath)
        command.add("-Xms${heapMb}M")
        command.add("-Xmx${heapMb}M")
        command.add("-Djava.awt.headless=true")
        command.add("-Dfile.encoding=UTF-8")
        command.add("-Dstdout.encoding=UTF-8")
        command.add("-Dstderr.encoding=UTF-8")
        command.add("-Duser.home=${workingDir.absolutePath}")
        command.add("-Djava.io.tmpdir=${tmpDir.absolutePath}")
        // JNA is used by several Minecraft libraries (oshi hardware info); only
        // point the JVM at the instance-local Android/bionic copy when present.
        if (effectiveJnaDir != null) {
            command.add("-Djna.boot.library.path=${effectiveJnaDir.absolutePath}")
        }
        command.addAll(ArgTokenizer.tokenize(instance.jvmArgs))
        command.add("-jar")
        command.add(jarFile.absolutePath)

        val serverArgs = when {
            instance.isMinecraft && instance.serverArgs.isBlank() ->
                ArgTokenizer.tokenize(MINECRAFT_DEFAULT_ARGS)
            else -> ArgTokenizer.tokenize(instance.serverArgs)
        }
        command.addAll(serverArgs)

        val home = javaHome.absolutePath
        val environment = linkedMapOf(
            "JAVA_HOME" to home,
            "LD_LIBRARY_PATH" to "$home/lib:$home/lib/server",
            "PATH" to "$home/bin:/system/bin",
            "HOME" to workingDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "LANG" to "C.UTF-8"
        )

        return LaunchPlan(
            command = command,
            environment = environment,
            workingDir = workingDir,
            javaExecutable = javaExecutable,
            javaHome = javaHome,
            tmpDir = tmpDir,
            memoryMb = heapMb
        )
    }
}
