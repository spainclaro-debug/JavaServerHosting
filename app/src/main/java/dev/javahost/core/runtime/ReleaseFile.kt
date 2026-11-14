package dev.javahost.core.runtime

import java.io.File

/**
 * Parses the `release` file that every JDK/JRE home contains, e.g.
 *
 * ```
 * JAVA_VERSION="21.0.13"
 * IMPLEMENTOR="Termux"
 * ```
 *
 * The version label is what the UI shows next to an installed runtime; the major
 * version decides which `jreN` directory an imported archive maps to.
 */
object ReleaseFile {

    fun versionLabel(home: File): String? = readMap(home)["JAVA_VERSION"]

    fun readMap(home: File): Map<String, String> {
        val file = File(home, "release")
        if (!file.isFile) return emptyMap()
        return parse(runCatching { file.readText() }.getOrNull().orEmpty())
    }

    fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim()
            var value = trimmed.substring(eq + 1).trim()
            if (value.length >= 2 &&
                ((value.startsWith('"') && value.endsWith('"')) ||
                    (value.startsWith('\'') && value.endsWith('\'')))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    /**
     * Extracts the Java major from a version label or archive name.
     *
     * Handles modern labels (`21.0.13`, `25-ea`) and legacy ones
     * (`1.8.0_402` -> 8). Returns null when no plausible number is present.
     */
    fun majorVersion(versionLabel: String?): Int? {
        if (versionLabel.isNullOrBlank()) return null
        val match = Regex("(\\d+)(?:\\.(\\d+))?").find(versionLabel.trim()) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        if (first == 1) {
            val second = match.groupValues[2].toIntOrNull() ?: return null
            return second
        }
        return first
    }

    /** Finds a major version mentioned in a user-supplied display name, e.g. `jdk-21.0.5`. */
    fun majorVersionFromName(displayName: String): Int? {
        val match = Regex("(?i)(?:jdk|jre|java)[- _]*(\\d+)").find(displayName) ?: return null
        val candidate = match.groupValues[1].toIntOrNull() ?: return null
        return candidate
    }
}
