package dev.javahost.core.server

import dev.javahost.core.storage.Paths
import java.io.File
import java.time.Instant

/**
 * Writes and reads Minecraft's `eula.txt`.
 *
 * The acceptance itself is a user action owned by the ViewModel: it calls
 * [accept] when the user agrees, which both writes the file and (via
 * [dev.javahost.core.server.ServerManager.acceptEula]) flips the persisted
 * `eulaAccepted` flag.  The launcher refuses to start a Minecraft server whose
 * flag is false, and re-asserts `eula=true` at launch so a hand-edited or
 * restored instance directory cannot fall back to the vanilla EULA prompt.
 */
object EulaManager {

    const val EULA_URL = "https://aka.ms/MinecraftEULA"

    /** Accepts the EULA for an instance, writing `eula.txt`. */
    fun accept(paths: Paths, instanceId: String, timestamp: Long = System.currentTimeMillis()): Boolean =
        write(paths.eulaFile(instanceId), accepted = true, timestamp = timestamp)

    /** True when `eula.txt` exists and contains `eula=true`. */
    fun isAccepted(paths: Paths, instanceId: String): Boolean =
        readAccepted(paths.eulaFile(instanceId))

    /** Low-level writer used by tests and the Manager. */
    fun write(eulaFile: File, accepted: Boolean, timestamp: Long = System.currentTimeMillis()): Boolean =
        try {
            eulaFile.parentFile?.mkdirs()
            eulaFile.writeText(buildText(accepted, timestamp), Charsets.UTF_8)
            true
        } catch (t: Throwable) {
            false
        }

    /** Convenience overload for direct file access. */
    fun accept(eulaFile: File, timestamp: Long = System.currentTimeMillis()): Boolean =
        write(eulaFile, accepted = true, timestamp = timestamp)

    /** Reads the `eula=true` line, ignoring comments/blank lines. */
    fun readAccepted(eulaFile: File): Boolean = try {
        eulaFile.isFile && eulaFile.readLines(Charsets.UTF_8).any { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("#") && trimmed.lowercase().replace(" ", "") == "eula=true"
        }
    } catch (t: Throwable) {
        false
    }

    fun buildText(accepted: Boolean, timestamp: Long = System.currentTimeMillis()): String {
        val value = if (accepted) "true" else "false"
        return buildString {
            appendLine("#By changing the setting below to TRUE you are indicating your agreement to our EULA ($EULA_URL).")
            appendLine("#${Instant.ofEpochMilli(timestamp)}")
            appendLine("eula=$value")
        }
    }
}
