package dev.javahost.core.jar

import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.JarInspection
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerKind
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Conservative, dependency-free JAR classifier used by the "add server" wizard.
 *
 * Design constraints:
 *  - Only `java.util.zip` / `java.io` are used, so the whole class runs on a
 *    plain JVM and is hermetically unit-testable (`:app:testDebugUnitTest`).
 *  - Never throws: every failure is reported as [InspectionResult.Failure] with
 *    a message that tells the user what to do next.
 *  - Hostile input is bounded: at most [MAX_ENTRIES_SCANNED] entries are
 *    enumerated, the first bytes of at most [MAX_CLASS_SAMPLES] class files are
 *    read and text metadata entries are capped at [MAX_TEXT_ENTRY_BYTES].
 *
 * Classification order (first match wins):
 *  1. Forge / NeoForge / Fabric *installer* jars (must be run once, not hosted)
 *  2. Paperclip (Paper/Purpur launcher) and Paper/Spigot/CraftBukkit family
 *  3. Fabric server launcher
 *  4. Forge / NeoForge server launcher
 *  5. Vanilla (classic `net.minecraft.server.Main`, or modern bundler jar)
 *  6. Bukkit API / server family
 *  7. Generic Java program (`Main-Class` present)
 *  8. Anything else: Failure with an actionable hint.
 */
object JarInspector {

    /** Hard cap on ZIP entries enumerated; protects against zip bombs. */
    const val MAX_ENTRIES_SCANNED: Int = 5_000

    /** Maximum number of `.class` entries whose headers are sampled. */
    const val MAX_CLASS_SAMPLES: Int = 32

    /** Maximum accepted size for the small text metadata entries we read. */
    const val MAX_TEXT_ENTRY_BYTES: Int = 256 * 1024

    /** Class headers are only 8 bytes, but cap obviously oversized "class" entries. */
    private const val MAX_CLASS_FILE_BYTES: Long = 64L * 1024L

    private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
    private const val VERSION_JSON_PATH = "version.json"
    private const val VERSIONS_LIST_PATH = "META-INF/versions.list"
    private const val BUNDLER_MAIN_CLASS_PATH = "META-INF/main-class"
    private const val PATCH_PROPERTIES_PATH = "patch.properties"
    private const val PATCH_PROPERTIES_ALT_PATH = "META-INF/patch.properties"
    private const val INSTALL_PROFILE_PATH = "install_profile.json"
    private const val PLUGIN_YML_PATH = "plugin.yml"
    private const val PAPER_PLUGIN_YML_PATH = "paper-plugin.yml"
    private const val FABRIC_MOD_JSON_PATH = "fabric.mod.json"
    private const val FORGE_MODS_TOML_PATH = "META-INF/mods.toml"
    private const val FORGE_MODS_TOML_ALT_PATH = "mods.toml"
    private const val NEOFORGE_MODS_TOML_PATH = "META-INF/neoforge.mods.toml"

    /**
     * Paths probed with [ZipFile.getEntry] even when the archive is too large to
     * enumerate fully. Keeps detection correct for big jars whose signature
     * classes sit past the enumeration cap.
     */
    private val PROBED_PATHS: List<String> = listOf(
        MANIFEST_PATH,
        VERSION_JSON_PATH,
        VERSIONS_LIST_PATH,
        BUNDLER_MAIN_CLASS_PATH,
        PATCH_PROPERTIES_PATH,
        PATCH_PROPERTIES_ALT_PATH,
        INSTALL_PROFILE_PATH,
        PLUGIN_YML_PATH,
        PAPER_PLUGIN_YML_PATH,
        FABRIC_MOD_JSON_PATH,
        FORGE_MODS_TOML_PATH,
        FORGE_MODS_TOML_ALT_PATH,
        NEOFORGE_MODS_TOML_PATH,
        // Launchers / server entry points.
        "net/minecraft/server/Main.class",
        "net/minecraft/server/MinecraftServer.class",
        "net/minecraft/bundler/Main.class",
        "io/papermc/paperclip/Main.class",
        "com/destroystokyo/paperclip/Paperclip.class",
        "org/bukkit/craftbukkit/Main.class",
        "org/bukkit/Bukkit.class",
        "org/bukkit/plugin/java/JavaPlugin.class",
        "net/fabricmc/loader/impl/launch/server/FabricServerLauncher.class",
        "net/fabricmc/loader/impl/launch/knot/KnotServer.class",
        "net/fabricmc/installer/Main.class",
        "net/minecraftforge/server/ServerMain.class",
        "net/minecraftforge/installer/Main.class",
        "net/minecraftforge/installer/ClientInstall.class",
        "cpw/mods/bootstraplauncher/BootstrapLauncher.class",
        "net/neoforged/installer/Main.class",
        "net/neoforged/neoforge/server/ServerMain.class"
    )

    /**
     * Inspect [file] and classify it. Always returns a value; malformed,
     * non-ZIP, empty and missing files come back as [InspectionResult.Failure].
     */
    fun inspect(file: File): InspectionResult {
        if (!file.exists()) {
            return InspectionResult.Failure(
                "File not found: \"${file.name}\". Choose the server .jar file again."
            )
        }
        if (file.isDirectory) {
            return InspectionResult.Failure(
                "\"${file.name}\" is a folder, not a .jar file. Choose the server .jar file."
            )
        }
        if (file.length() <= 0L) {
            return InspectionResult.Failure(
                "\"${file.name}\" is empty (0 bytes), so it cannot be a runnable server. " +
                    "Download or copy the .jar again."
            )
        }
        val facts = try {
            readFacts(file)
        } catch (e: ZipException) {
            // Android's java.util.zip can reject archives that contain duplicate
            // entry names (common in Spring Boot / Suwayomi fat JARs) even though
            // the JVM runs them fine. Retry with Commons Compress, which reads the
            // central directory tolerantly; the actual server process still uses
            // the JVM's own launcher.
            readFactsWithCommonsZip(file)
                ?: return InspectionResult.Failure(notAJarMessage(file, e))
        } catch (e: IOException) {
            readFactsWithCommonsZip(file)
                ?: return InspectionResult.Failure(notAJarMessage(file, e))
        } catch (e: SecurityException) {
            return InspectionResult.Failure(
                "JavaHost is not allowed to read \"${file.name}\". Copy the .jar into a " +
                    "folder the app can read and try again."
            )
        }
        return classify(facts)
    }

    // ---------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------

    private fun readFacts(file: File): JarFacts {
        ZipFile(file).use { zip ->
            var scanned = 0
            var baseClassSamples = 0
            var multiReleaseClassSamples = 0
            var baseMaxMajor: Int? = null
            var baseMaxMajorEntry: String? = null
            var multiReleaseMaxMajor: Int? = null
            var multiReleaseMaxMajorEntry: String? = null
            val names = LinkedHashSet<String>()
            var manifestText: String? = null
            var versionJson: String? = null
            var versionsList: String? = null
            var bundlerMainClass: String? = null
            var patchProperties: String? = null

            val totalEntries = zip.size()

            val entries = zip.entries()
            while (entries.hasMoreElements() && scanned < MAX_ENTRIES_SCANNED) {
                val entry = entries.nextElement()
                scanned++
                val name = entry.name
                if (entry.isDirectory) continue
                names.add(name)

                when (name) {
                    MANIFEST_PATH -> manifestText = readEntryText(zip, entry)
                    VERSION_JSON_PATH -> versionJson = readEntryText(zip, entry)
                    VERSIONS_LIST_PATH -> versionsList = readEntryText(zip, entry)
                    BUNDLER_MAIN_CLASS_PATH -> bundlerMainClass = readEntryText(zip, entry)
                    PATCH_PROPERTIES_PATH, PATCH_PROPERTIES_ALT_PATH ->
                        patchProperties = patchProperties ?: readEntryText(zip, entry)
                }

                // Multi-release JARs ship optional `META-INF/versions/<N>/` classes.
                // Those are NOT the minimum runtime: the base classes are. Sample
                // both pools separately so e.g. h2 (base Java 8, MR Java 21) is
                // mapped to Java 8 rather than 21.
                val isMultiRelease = name.startsWith("META-INF/versions/")
                if (name.endsWith(".class") && !entry.isDirectory) {
                    if (isMultiRelease) {
                        if (multiReleaseClassSamples < MAX_CLASS_SAMPLES / 2) {
                            multiReleaseClassSamples++
                            val major = readClassMajor(zip, entry)
                            val currentMax = multiReleaseMaxMajor
                            if (major != null && (currentMax == null || major > currentMax)) {
                                multiReleaseMaxMajor = major
                                multiReleaseMaxMajorEntry = name
                            }
                        }
                    } else if (baseClassSamples < MAX_CLASS_SAMPLES / 2) {
                        baseClassSamples++
                        val major = readClassMajor(zip, entry)
                        val currentMax = baseMaxMajor
                        if (major != null && (currentMax == null || major > currentMax)) {
                            baseMaxMajor = major
                            baseMaxMajorEntry = name
                        }
                    }
                }
            }

            // Targeted probes make detection robust even when the archive is
            // larger than the enumeration cap (entries order is not guaranteed).
            for (path in PROBED_PATHS) {
                if (names.contains(path)) continue
                val entry = zip.getEntry(path) ?: continue
                names.add(path)
                when (path) {
                    MANIFEST_PATH -> manifestText = manifestText ?: readEntryText(zip, entry)
                    VERSION_JSON_PATH -> versionJson = versionJson ?: readEntryText(zip, entry)
                    VERSIONS_LIST_PATH -> versionsList = versionsList ?: readEntryText(zip, entry)
                    BUNDLER_MAIN_CLASS_PATH -> bundlerMainClass =
                        bundlerMainClass ?: readEntryText(zip, entry)
                    PATCH_PROPERTIES_PATH, PATCH_PROPERTIES_ALT_PATH -> patchProperties =
                        patchProperties ?: readEntryText(zip, entry)
                }
            }

            val manifest = parseMainManifest(manifestText)
            return JarFacts(
                fileName = file.name,
                hasManifest = manifestText != null,
                manifestPresent = manifestText != null,
                manifestMainClass = manifest[ATTR_MAIN_CLASS],
                implementationVersion = manifest[ATTR_IMPLEMENTATION_VERSION],
                implementationTitle = manifest[ATTR_IMPLEMENTATION_TITLE],
                specificationVersion = manifest[ATTR_SPECIFICATION_VERSION],
                bundlerMainClass = bundlerMainClass?.trim()?.takeIf { it.isNotEmpty() },
                entryNames = names,
                totalEntryCount = totalEntries,
                scannedEntryCount = scanned,
                maxClassMajor = baseMaxMajor ?: multiReleaseMaxMajor,
                maxClassMajorEntry = baseMaxMajorEntry ?: multiReleaseMaxMajorEntry,
                multiReleaseClassMajor = multiReleaseMaxMajor,
                versionJson = versionJson,
                versionsList = versionsList,
                patchProperties = patchProperties
            )
        }
    }

    /**
     * Tolerant fallback for archives rejected by the platform ZipFile (notably
     * duplicate entry names). Fills the same [JarFacts] needed by [classify].
     * Returns null only when the archive is unreadable by both implementations.
     */
    internal fun readFactsWithCommonsZip(file: File): JarFacts? = try {
        CommonsZipFile.builder().setFile(file).get().use { zip ->
            val names = LinkedHashSet<String>()
            var scanned = 0
            var baseClassSamples = 0
            var multiReleaseClassSamples = 0
            var baseMaxMajor: Int? = null
            var baseMaxMajorEntry: String? = null
            var multiReleaseMaxMajor: Int? = null
            var multiReleaseMaxMajorEntry: String? = null
            var manifestText: String? = null
            var versionJson: String? = null
            var versionsList: String? = null
            var bundlerMainClass: String? = null
            var patchProperties: String? = null

            val entries = zip.entries
            while (entries.hasMoreElements() && scanned < MAX_ENTRIES_SCANNED) {
                val entry: ZipArchiveEntry = entries.nextElement()
                scanned++
                val name = entry.name
                if (entry.isDirectory) continue
                names.add(name)
                when (name) {
                    MANIFEST_PATH -> manifestText = readText(zip, entry)
                    VERSION_JSON_PATH -> versionJson = readText(zip, entry)
                    VERSIONS_LIST_PATH -> versionsList = readText(zip, entry)
                    BUNDLER_MAIN_CLASS_PATH -> bundlerMainClass = readText(zip, entry)
                    PATCH_PROPERTIES_PATH, PATCH_PROPERTIES_ALT_PATH ->
                        patchProperties = patchProperties ?: readText(zip, entry)
                }
                val isMultiRelease = name.startsWith("META-INF/versions/")
                if (name.endsWith(".class")) {
                    if (isMultiRelease) {
                        if (multiReleaseClassSamples < MAX_CLASS_SAMPLES / 2) {
                            multiReleaseClassSamples++
                            val major = readClassMajor(zip, entry)
                            if (major != null && (multiReleaseMaxMajor == null || major > multiReleaseMaxMajor!!)) {
                                multiReleaseMaxMajor = major
                                multiReleaseMaxMajorEntry = name
                            }
                        }
                    } else if (baseClassSamples < MAX_CLASS_SAMPLES / 2) {
                        baseClassSamples++
                        val major = readClassMajor(zip, entry)
                        if (major != null && (baseMaxMajor == null || major > baseMaxMajor!!)) {
                            baseMaxMajor = major
                            baseMaxMajorEntry = name
                        }
                    }
                }
            }

            // Duplicate names are common in fat JARs; getEntry returns one of them.
            for (path in PROBED_PATHS) {
                if (names.contains(path)) continue
                val entry = zip.getEntry(path) ?: continue
                names.add(path)
                when (path) {
                    MANIFEST_PATH -> manifestText = manifestText ?: readText(zip, entry)
                    VERSION_JSON_PATH -> versionJson = versionJson ?: readText(zip, entry)
                    VERSIONS_LIST_PATH -> versionsList = versionsList ?: readText(zip, entry)
                    BUNDLER_MAIN_CLASS_PATH -> bundlerMainClass =
                        bundlerMainClass ?: readText(zip, entry)
                    PATCH_PROPERTIES_PATH, PATCH_PROPERTIES_ALT_PATH -> patchProperties =
                        patchProperties ?: readText(zip, entry)
                }
            }

            val manifest = parseMainManifest(manifestText)
            JarFacts(
                fileName = file.name,
                hasManifest = manifestText != null,
                manifestPresent = manifestText != null,
                manifestMainClass = manifest[ATTR_MAIN_CLASS],
                implementationVersion = manifest[ATTR_IMPLEMENTATION_VERSION],
                implementationTitle = manifest[ATTR_IMPLEMENTATION_TITLE],
                specificationVersion = manifest[ATTR_SPECIFICATION_VERSION],
                bundlerMainClass = bundlerMainClass?.trim()?.takeIf { it.isNotEmpty() },
                entryNames = names,
                totalEntryCount = scanned,
                scannedEntryCount = scanned,
                maxClassMajor = baseMaxMajor ?: multiReleaseMaxMajor,
                maxClassMajorEntry = baseMaxMajorEntry ?: multiReleaseMaxMajorEntry,
                multiReleaseClassMajor = multiReleaseMaxMajor,
                versionJson = versionJson,
                versionsList = versionsList,
                patchProperties = patchProperties
            )
        }
    } catch (t: Throwable) {
        null
    }

    private fun readText(zip: CommonsZipFile, entry: ZipArchiveEntry): String? {
        if (entry.size > MAX_TEXT_ENTRY_BYTES) return null
        return try {
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream(minOf(entry.size.coerceAtLeast(0L), MAX_TEXT_ENTRY_BYTES.toLong()).toInt())
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_TEXT_ENTRY_BYTES) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readClassMajor(zip: CommonsZipFile, entry: ZipArchiveEntry): Int? = try {
        if (entry.size > MAX_CLASS_FILE_BYTES) null else zip.getInputStream(entry).use { input ->
            val header = ByteArray(8)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (read < 8) null else {
                val magic = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (magic != 0xCAFEBABE.toInt()) null
                else ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun readEntryText(zip: ZipFile, entry: ZipEntry): String? {
        if (entry.size > MAX_TEXT_ENTRY_BYTES) return null
        return try {
            zip.getInputStream(entry).use { input ->
                readBounded(input, MAX_TEXT_ENTRY_BYTES)?.toString(Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        }
    }

    /** Reads at most [maxBytes]; returns null (and consumes nothing further) when larger. */
    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(minOf(1024, maxBytes))
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Reads the first 8 bytes of a class-file entry (magic, minor, major).
     * Returns the major version, or null when the entry is not a valid class.
     */
    private fun readClassMajor(zip: ZipFile, entry: ZipEntry): Int? {
        return try {
            zip.getInputStream(entry).use { input ->
                val header = ByteArray(8)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < 8) return null
                val magic =
                    ((header[0].toInt() and 0xFF) shl 24) or
                        ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                if (magic != 0xCAFEBABE.toInt()) return null
                ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun notAJarMessage(file: File, cause: Throwable): String {
        val detail = cause.message?.takeIf { it.isNotBlank() && it.length <= 120 }
        return buildString {
            append("\"${file.name}\" is not a valid JAR/ZIP archive")
            if (detail != null) append(" ($detail)")
            append(". It may be corrupted or only partially downloaded; download it again and retry.")
        }
    }

    // ---------------------------------------------------------------------
    // Pure classification (internal so tests can target it directly too)
    // ---------------------------------------------------------------------

    internal fun classify(facts: JarFacts): InspectionResult {
        val names = facts.entryNames
        val main = facts.manifestMainClass?.trim()?.takeIf { it.isNotEmpty() }
        val lowerMain = main?.lowercase()

        val evidence = ArrayList<String>(8)
        val warnings = ArrayList<String>(4)
        if (main != null) evidence += "Manifest Main-Class: $main"
        else if (facts.hasManifest) evidence += "Manifest found, but it has no Main-Class"

        // ---- Java requirement -------------------------------------------------
        val javaFromClassFile = facts.maxClassMajor?.let { classMajorToJavaMajor(it) }
        if (facts.maxClassMajor != null && javaFromClassFile != null) {
            evidence += "Highest class-file version ${facts.maxClassMajor} (Java $javaFromClassFile)" +
                (facts.maxClassMajorEntry?.let { " in $it" } ?: "")
        }
        val multiReleaseMajor = facts.multiReleaseClassMajor
        val multiReleaseJava = multiReleaseMajor?.let { classMajorToJavaMajor(it) }
        if (multiReleaseMajor != null && multiReleaseJava != null &&
            multiReleaseMajor > (facts.maxClassMajor ?: 0)
        ) {
            evidence += "Multi-release JAR also ships Java $multiReleaseJava classes " +
                "(optional for newer runtimes)"
        }
        val javaFromVersionJson = parseVersionJsonJavaVersion(facts.versionJson)
        if (javaFromVersionJson != null) {
            evidence += "version.json declares Java $javaFromVersionJson"
        }
        val requiredJava = maxOf(javaFromClassFile ?: 0, javaFromVersionJson ?: 0)
        val suggestedJre = suggestedJreMajor(requiredJava.takeIf { it > 0 })
        if (requiredJava > 25) {
            warnings += "This JAR targets Java $requiredJava, but JavaHost can only install " +
                "Java 17, 21 or 25. It may fail to start."
        }

        fun base(
            kind: ServerKind,
            flavor: ServerFlavor,
            confidence: Float,
            minecraftVersion: String?,
            needsInstaller: Boolean = false
        ): JarInspection = JarInspection(
            mainClass = main,
            kind = kind,
            flavor = flavor,
            confidence = confidence,
            evidence = evidence.toList(),
            warnings = warnings.toList(),
            classFileMajor = facts.maxClassMajor,
            suggestedJreMajor = suggestedJre,
            minecraftVersion = minecraftVersion,
            needsInstaller = needsInstaller,
            entriesScanned = facts.scannedEntryCount
        )

        fun success(inspection: JarInspection): InspectionResult = InspectionResult.Success(inspection)

        // ---- 1. Installer jars (must be run once, not hosted) -----------------
        val forgeInstallerMarkers =
            names.any { it.startsWith("net/minecraftforge/installer/") } ||
                names.any { it.startsWith("net/neoforged/installer/") }
        val fabricInstallerMarkers = names.any { it.startsWith("net/fabricmc/installer/") }
        val mcPackagesPresent =
            names.any {
                it.startsWith("net/minecraftforge/") || it.startsWith("cpw/mods/") ||
                    it.startsWith("net/fabricmc/") || it.startsWith("net/neoforged/")
            }
        val mainLooksLikeInstaller = lowerMain?.contains("installer") == true
        val hasInstallProfile = facts.has(INSTALL_PROFILE_PATH)
        val isMcInstaller =
            forgeInstallerMarkers || fabricInstallerMarkers ||
                (mainLooksLikeInstaller && (mcPackagesPresent || hasInstallProfile))
        if (isMcInstaller) {
            val flavor = when {
                fabricInstallerMarkers || lowerMain?.contains("fabric") == true -> ServerFlavor.FABRIC
                else -> ServerFlavor.FORGE
            }
            if (forgeInstallerMarkers) evidence += "Found Forge/NeoForge installer classes"
            if (fabricInstallerMarkers) evidence += "Found Fabric installer classes"
            if (hasInstallProfile) evidence += "Found install_profile.json"
            warnings += "This is an installer, not a server. Run it once (on a computer or " +
                "with a desktop Java) to produce the real server JAR, then add that JAR here."
            if (main != null && mainLooksLikeInstaller) {
                evidence += "Installer entry point: $main"
            }
            // Installer jars often carry the *installer's own* version in the
            // manifest (e.g. Fabric installer "1.0.1") — never treat that as a
            // Minecraft version. Only trust explicit embedded game metadata.
            val inheritsFrom = parseVersionJsonInheritsFrom(facts.versionJson)
            val embeddedMcVersion = inheritsFrom ?: parseVersionJsonId(facts.versionJson)
            if (inheritsFrom != null) {
                evidence += "version.json inherits from Minecraft $inheritsFrom"
            } else if (embeddedMcVersion != null) {
                evidence += "version.json reports Minecraft $embeddedMcVersion"
            }
            return success(
                base(
                    kind = ServerKind.MINECRAFT,
                    flavor = flavor,
                    confidence = 0.92f,
                    minecraftVersion = embeddedMcVersion,
                    needsInstaller = true
                )
            )
        }

        // ---- 2..6. Signature detection (installers handled above) -----------
        val detected = detectSignature(facts, main, lowerMain, evidence, warnings)
        if (detected != null) {
            if (main != null) {
                return success(
                    base(
                        kind = detected.kind,
                        flavor = detected.flavor,
                        confidence = detected.confidence,
                        minecraftVersion = detectMinecraftVersion(facts, detected.flavor, evidence)
                    )
                )
            }
            // No Main-Class: only plausible when a well-known launcher class is present.
            val inferred = INFERRED_LAUNCHERS.firstOrNull { (path, launcher) ->
                launcher.flavor == detected.flavor && names.contains(path)
            }
            if (inferred != null) {
                val (path, launcher) = inferred
                return success(
                    base(
                        kind = detected.kind,
                        flavor = detected.flavor,
                        confidence = 0.6f,
                        minecraftVersion = detectMinecraftVersion(facts, detected.flavor, evidence)
                    ).copy(
                        mainClass = launcher.mainClass,
                        evidence = evidence + "No Main-Class in manifest; inferred launcher $path",
                        warnings = warnings +
                            "The manifest has no Main-Class. JavaHost will assume " +
                            "${launcher.mainClass}; add it manually if the server does not start."
                    )
                )
            }
            return InspectionResult.Failure(noExecutableMessage(facts))
        }

        // ---- 7. Generic Java program ----------------------------------------
        if (main != null) {
            evidence += "Generic executable JAR detected from Main-Class"
            return success(
                base(
                    kind = ServerKind.GENERIC,
                    flavor = ServerFlavor.GENERIC,
                    confidence = 0.85f,
                    minecraftVersion = null
                )
            )
        }

        // ---- 8. Known launcher class without any manifest Main-Class --------
        val inferred = INFERRED_LAUNCHERS.firstOrNull { (path, _) -> names.contains(path) }
        if (inferred != null) {
            val (path, launcher) = inferred
            val inspection = base(
                kind = ServerKind.MINECRAFT,
                flavor = launcher.flavor,
                confidence = 0.6f,
                minecraftVersion = detectMinecraftVersion(facts, launcher.flavor, evidence)
            ).copy(
                mainClass = launcher.mainClass,
                evidence = evidence + "No Main-Class in manifest; inferred launcher $path",
                warnings = warnings +
                    "The manifest has no Main-Class. JavaHost will assume " +
                    "${launcher.mainClass}; add it manually if the server does not start."
            )
            return success(inspection)
        }

        // ---- 9. Failure with an actionable hint ------------------------------
        return InspectionResult.Failure(noExecutableMessage(facts))
    }

    private data class Signature(
        val kind: ServerKind,
        val flavor: ServerFlavor,
        val confidence: Float
    )

    /**
     * Pure signature matching, in priority order. Only decides what kind of
     * program the JAR contains; executability is checked by the caller.
     */
    private fun detectSignature(
        facts: JarFacts,
        main: String?,
        lowerMain: String?,
        evidence: MutableList<String>,
        warnings: MutableList<String>
    ): Signature? {
        val names = facts.entryNames

        // Paperclip (Paper/Purpur launcher) and the Paper/Spigot/CraftBukkit family.
        val paperclipMain = lowerMain != null &&
            (lowerMain.startsWith("io.papermc.paperclip.") ||
                lowerMain.startsWith("com.destroystokyo.paperclip."))
        val paperclipClasses = names.any {
            it.startsWith("io/papermc/paperclip/") || it.startsWith("com/destroystokyo/paperclip/")
        }
        val paperClasses = names.any {
            it.startsWith("io/papermc/paper/") || it.startsWith("com/destroystokyo/paper/")
        }
        val craftBukkitClasses = names.any { it.startsWith("org/bukkit/craftbukkit/") }
        if (paperclipMain || paperclipClasses || paperClasses || craftBukkitClasses) {
            if (paperclipMain || paperclipClasses) {
                evidence += "Found Paperclip launcher (Paper/Purpur family)"
                warnings += "Paperclip JAR: the first start downloads the vanilla server JAR " +
                    "and patches it. The first launch therefore needs network access and time."
            }
            if (paperClasses) evidence += "Found io.papermc.paper / paper API classes"
            if (craftBukkitClasses) evidence += "Found org.bukkit.craftbukkit classes"
            return Signature(
                kind = ServerKind.MINECRAFT,
                flavor = ServerFlavor.PAPER,
                confidence = if (paperclipMain || craftBukkitClasses) 0.94f else 0.85f
            )
        }

        // Fabric server launcher.
        val fabricLoaderMain = lowerMain != null && lowerMain.startsWith("net.fabricmc.loader.")
        val fabricLoaderClasses = names.any { it.startsWith("net/fabricmc/loader/") }
        if (fabricLoaderMain || fabricLoaderClasses) {
            evidence += "Found Fabric loader classes/launcher"
            return Signature(ServerKind.MINECRAFT, ServerFlavor.FABRIC, 0.9f)
        }

        // Forge / NeoForge server launcher.
        val forgeMain = lowerMain != null && (
            lowerMain.startsWith("net.minecraftforge.") ||
                lowerMain.startsWith("cpw.mods.") ||
                lowerMain.startsWith("net.neoforged.")
            )
        val forgeClasses = names.any {
            it.startsWith("net/minecraftforge/") || it.startsWith("cpw/mods/") ||
                it.startsWith("net/neoforged/")
        }
        if (forgeMain || forgeClasses) {
            evidence += "Found Forge/NeoForge classes or launcher"
            if (names.any { it.startsWith("net/neoforged/") }) {
                warnings += "NeoForge JAR: treated as Forge for launch purposes (same JVM layout)."
            }
            return Signature(ServerKind.MINECRAFT, ServerFlavor.FORGE, 0.9f)
        }

        // Vanilla (classic server or modern bundler).
        val classicVanilla = names.contains("net/minecraft/server/Main.class") ||
            names.contains("net/minecraft/server/MinecraftServer.class") ||
            main == "net.minecraft.server.Main" ||
            main == "net.minecraft.server.MinecraftServer"
        val bundlerMain = lowerMain == "net.minecraft.bundler.main" ||
            facts.bundlerMainClass != null ||
            names.contains("net/minecraft/bundler/Main.class") ||
            (facts.versionJson != null && facts.versionsList != null)
        if (classicVanilla || bundlerMain) {
            if (classicVanilla) evidence += "Found net.minecraft.server server classes"
            if (bundlerMain) {
                evidence += "Found modern Minecraft bundler layout (version.json/versions.list)"
                warnings += "Modern Minecraft bundler JAR: the server JAR and libraries are " +
                    "extracted on first launch; this can take a while."
            }
            return Signature(
                kind = ServerKind.MINECRAFT,
                flavor = ServerFlavor.VANILLA,
                confidence = if (classicVanilla) 0.97f else 0.95f
            )
        }

        // Bukkit family (no Paper/CraftBukkit markers).
        if (names.any { it.startsWith("org/bukkit/") }) {
            evidence += "Found org.bukkit classes"
            return Signature(
                kind = ServerKind.MINECRAFT,
                flavor = ServerFlavor.BUKKIT,
                confidence = if (main != null) 0.8f else 0.55f
            )
        }

        return null
    }

    private fun noExecutableMessage(facts: JarFacts): String {
        val name = "\"${facts.fileName}\""
        return when {
            facts.has(PLUGIN_YML_PATH) || facts.has(PAPER_PLUGIN_YML_PATH) ->
                "$name looks like a Bukkit/Paper plugin, not a server: it has no Main-Class. " +
                    "Add it to an existing Minecraft server's plugins/ folder instead."
            facts.has(FABRIC_MOD_JSON_PATH) ->
                "$name looks like a Fabric mod, not a server: it has no Main-Class. " +
                    "Add it to an existing Fabric server's mods/ folder instead."
            facts.has(FORGE_MODS_TOML_PATH) || facts.has(FORGE_MODS_TOML_ALT_PATH) ->
                "$name looks like a Forge/NeoForge mod, not a server: it has no Main-Class. " +
                    "Add it to an existing Forge server's mods/ folder instead."
            facts.has(NEOFORGE_MODS_TOML_PATH) ->
                "$name looks like a NeoForge mod, not a server: it has no Main-Class. " +
                    "Add it to an existing NeoForge server's mods/ folder instead."
            facts.has("org/bukkit/Bukkit.class") || facts.has("org/bukkit/plugin/java/JavaPlugin.class") ->
                "$name looks like the Bukkit/Paper API library, not a runnable server: it has " +
                    "no Main-Class. Choose the Paper/Spigot server JAR instead (the one you " +
                    "run with java -jar), not an API jar."
            else ->
                "$name cannot be started with java -jar: it has no Main-Class in " +
                    "META-INF/MANIFEST.MF and no known server launcher class. It is probably " +
                    "a library, a mod or a partial download. Choose the server JAR itself " +
                    "(for example the Vanilla/Paper/Fabric server jar), not a plugin/mod."
        }
    }

    // ---------------------------------------------------------------------
    // Minecraft version detection
    // ---------------------------------------------------------------------

    private fun detectMinecraftVersion(
        facts: JarFacts,
        flavor: ServerFlavor,
        evidence: MutableList<String>
    ): String? {
        // Modern vanilla bundler: version.json is authoritative.
        val fromVersionJson = parseVersionJsonId(facts.versionJson)
        if (fromVersionJson != null) {
            evidence += "version.json reports Minecraft $fromVersionJson"
            return fromVersionJson
        }
        // Modern vanilla bundler: META-INF/versions.list: "<hash>\t<version>\t<path>".
        val fromVersionsList = parseVersionsList(facts.versionsList)
        if (fromVersionsList != null) {
            evidence += "META-INF/versions.list bundles Minecraft $fromVersionsList"
            return fromVersionsList
        }
        // Paperclip patch.properties: "version=1.20.4".
        val fromPatchProperties = parsePatchPropertiesVersion(facts.patchProperties)
        if (fromPatchProperties != null) {
            evidence += "patch.properties reports Minecraft $fromPatchProperties"
            return fromPatchProperties
        }
        // Manifest Implementation-Version, e.g. "git-Paper-196 (MC: 1.20.4)",
        // "1.20.4-R0.1-SNAPSHOT" (CraftBukkit) or "1.20.4".
        val manifestVersion = facts.implementationVersion?.trim()
        if (!manifestVersion.isNullOrEmpty()) {
            val normalized = normalizeManifestMcVersion(manifestVersion)
            if (normalized != null) {
                evidence += "Manifest Implementation-Version: $manifestVersion"
                return normalized
            }
        }
        // Fabric server launcher file name: fabric-server-mc.1.20.4-loader....
        if (flavor == ServerFlavor.FABRIC) {
            val fromName = FABRIC_NAME_VERSION.find(facts.fileName)?.groupValues?.get(1)
            if (fromName != null && isPlausibleMcVersion(fromName)) {
                evidence += "JAR file name mentions Minecraft $fromName"
                return fromName
            }
        }
        if (flavor != ServerFlavor.GENERIC) {
            // Evidence, not a warning: the wizard can still prefill a default version.
            evidence += "Minecraft version could not be determined from the JAR"
        }
        return null
    }

    private fun parseVersionJsonId(text: String?): String? {
        if (text == null) return null
        val id = JSON_STRING_ID.find(text)?.groupValues?.get(1)
            ?: JSON_STRING_NAME.find(text)?.groupValues?.get(1)
            ?: return null
        return id.trim().takeIf { isPlausibleMcVersion(it) }
    }

    private fun parseVersionJsonInheritsFrom(text: String?): String? {
        if (text == null) return null
        val candidate = JSON_STRING_INHERITS_FROM.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return candidate.takeIf { isPlausibleMcVersion(it) }
    }

    private fun parseVersionJsonJavaVersion(text: String?): Int? {
        if (text == null) return null
        val raw = JSON_NUMBER_JAVA_VERSION.find(text)?.groupValues?.get(1) ?: return null
        return raw.toIntOrNull()?.takeIf { it in 1..99 }
    }

    private fun parseVersionsList(text: String?): String? {
        if (text == null) return null
        for (line in text.lineSequence()) {
            val parts = line.trim().split('\t')
            if (parts.size < 2) continue
            val candidate = parts[1].trim()
            if (isPlausibleMcVersion(candidate)) return candidate
        }
        return null
    }

    private fun parsePatchPropertiesVersion(text: String?): String? {
        if (text == null) return null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("version")) continue
            val value = trimmed.substringAfter('=', "").trim()
            if (isPlausibleMcVersion(value)) return value
        }
        return null
    }

    /**
     * Pulls a clean Minecraft version out of a manifest Implementation-Version.
     * Handles "git-Paper-196 (MC: 1.20.4)", plain "1.20.4", and build-suffixed
     * values such as CraftBukkit's "1.20.4-R0.1-SNAPSHOT" (returns "1.20.4").
     */
    private fun normalizeManifestMcVersion(value: String): String? {
        val inParens = MC_IN_PARENS.find(value)?.groupValues?.get(1)
        if (inParens != null && isPlausibleMcVersion(inParens)) return inParens
        if (!isPlausibleMcVersion(value)) return null
        val buildSuffix = MC_BUILD_SUFFIX.find(value)?.groupValues?.get(1)
        return buildSuffix ?: value
    }

    /**
     * Minecraft versions look like 1.20.4, 1.20.4-pre1, 1.21.5-rc1, 1.20.4-forge-49.0.30.
     * Avoids treating arbitrary library versions (e.g. "3.9.2") as game versions;
     * callers only use this for flavors that are already known to be Minecraft.
     */
    private fun isPlausibleMcVersion(value: String): Boolean {
        if (value.length > 48) return false
        val match = MC_VERSION_PATTERN.matchEntire(value) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        return major in 1..30
    }

    // ---------------------------------------------------------------------
    // Manifest parsing (hand-rolled: deterministic and Android-friendly)
    // ---------------------------------------------------------------------

    private fun parseMainManifest(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (rawLine in text.split("\r\n", "\n", "\r")) {
            if (rawLine.isEmpty()) break // main section ends at the first blank line
            if (rawLine.startsWith(" ")) {
                val key = lastKey ?: continue
                result[key] = (result[key] ?: "") + rawLine.substring(1)
                continue
            }
            val colon = rawLine.indexOf(':')
            if (colon <= 0) continue
            val key = rawLine.substring(0, colon).trim()
            var value = rawLine.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            result[key] = value
            lastKey = key
        }
        return result
    }

    // ---------------------------------------------------------------------
    // JRE mapping
    // ---------------------------------------------------------------------

    /**
     * Class-file major -> Java feature release. The mapping is linear from Java 1
     * (major 45) onwards: `major - 44`. Values above 69 (Java 25) are clamped by
     * [suggestedJreMajor].
     */
    internal fun classMajorToJavaMajor(classMajor: Int): Int = classMajor - 44

    /**
     * JavaHost only ships/installs JRE 17, 21 and 25. Anything newer than 17 needs
     * at least 21, anything newer than 21 needs 25. Unknown defaults to 21.
     */
    internal fun suggestedJreMajor(requiredJava: Int?): Int = when {
        requiredJava == null || requiredJava <= 0 -> 21
        requiredJava <= 17 -> 17
        requiredJava <= 21 -> 21
        else -> 25
    }

    // ---------------------------------------------------------------------

    private const val ATTR_MAIN_CLASS = "Main-Class"
    private const val ATTR_IMPLEMENTATION_VERSION = "Implementation-Version"
    private const val ATTR_IMPLEMENTATION_TITLE = "Implementation-Title"
    private const val ATTR_SPECIFICATION_VERSION = "Specification-Version"

    private data class Launcher(val flavor: ServerFlavor, val mainClass: String)

    private val INFERRED_LAUNCHERS: List<Pair<String, Launcher>> = listOf(
        "io/papermc/paperclip/Main.class" to Launcher(ServerFlavor.PAPER, "io.papermc.paperclip.Main"),
        "org/bukkit/craftbukkit/Main.class" to Launcher(ServerFlavor.PAPER, "org.bukkit.craftbukkit.Main"),
        "net/minecraft/server/Main.class" to Launcher(ServerFlavor.VANILLA, "net.minecraft.server.Main"),
        "net/minecraft/server/MinecraftServer.class" to
            Launcher(ServerFlavor.VANILLA, "net.minecraft.server.MinecraftServer"),
        "net/minecraft/bundler/Main.class" to Launcher(ServerFlavor.VANILLA, "net.minecraft.bundler.Main"),
        "net/fabricmc/loader/impl/launch/server/FabricServerLauncher.class" to
            Launcher(ServerFlavor.FABRIC, "net.fabricmc.loader.impl.launch.server.FabricServerLauncher"),
        "net/minecraftforge/server/ServerMain.class" to
            Launcher(ServerFlavor.FORGE, "net.minecraftforge.server.ServerMain"),
        "cpw/mods/bootstraplauncher/BootstrapLauncher.class" to
            Launcher(ServerFlavor.FORGE, "cpw.mods.bootstraplauncher.BootstrapLauncher")
    )

    private val MC_IN_PARENS = Regex("""\(\s*MC:\s*([^)\s]+)\s*\)""")
    private val MC_VERSION_PATTERN =
        Regex("""^(\d+)\.\d+(?:\.\d+)?(?:[-+_][0-9A-Za-z][0-9A-Za-z._+-]*)?$""")
    private val FABRIC_NAME_VERSION = Regex("""fabric-server-mc\.([0-9]+\.[0-9]+(?:\.[0-9]+)?)""")
    private val MC_BUILD_SUFFIX =
        Regex("""^(\d+\.\d+(?:\.\d+)?)[-+_](?:R\d|.*SNAPSHOT)""")
    private val JSON_STRING_ID = Regex("""["']id["']\s*:\s*["']([^"']+)["']""")
    private val JSON_STRING_NAME = Regex("""["']name["']\s*:\s*["']([^"']+)["']""")
    private val JSON_STRING_INHERITS_FROM = Regex("""["']inheritsFrom["']\s*:\s*["']([^"']+)["']""")
    private val JSON_NUMBER_JAVA_VERSION = Regex("""["']java_version["']\s*:\s*(\d+)""")
}

/**
 * Everything [JarInspector] learned while reading a JAR, before any heuristics
 * run. Pure data: [JarInspector.classify] can be exercised with hand-built facts.
 */
internal data class JarFacts(
    val fileName: String = "",
    val hasManifest: Boolean = false,
    /** Reserved for future manifests with sections; kept for clarity. */
    val manifestPresent: Boolean = false,
    val manifestMainClass: String? = null,
    val implementationVersion: String? = null,
    val implementationTitle: String? = null,
    val specificationVersion: String? = null,
    /** Contents of `META-INF/main-class` for modern Minecraft bundler jars. */
    val bundlerMainClass: String? = null,
    val entryNames: Set<String> = emptySet(),
    val totalEntryCount: Int = 0,
    val scannedEntryCount: Int = 0,
    val maxClassMajor: Int? = null,
    val maxClassMajorEntry: String? = null,
    /** Highest class major under META-INF/versions (multi-release JARs); diagnostics only. */
    val multiReleaseClassMajor: Int? = null,
    val versionJson: String? = null,
    val versionsList: String? = null,
    val patchProperties: String? = null
) {
    fun has(path: String): Boolean = entryNames.contains(path)
}
