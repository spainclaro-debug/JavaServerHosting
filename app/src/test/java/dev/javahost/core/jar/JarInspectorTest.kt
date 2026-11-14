package dev.javahost.core.jar

import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.JarInspection
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Hermetic JAR-classifier tests. Synthetic JARs are built at test runtime with
 * [JarOutputStream], so nothing large or third-party is committed. A separate
 * optional test validates against the real JARs when [TARGETS_ENV] points at a
 * directory containing them.
 */
class JarInspectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var jarCounter = 0

    /* ------------------------------------------------------------------ */
    /* Minecraft flavours                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    fun `modern vanilla bundler jar is detected with version and JRE 17`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest("net.minecraft.bundler.Main"),
            "version.json" to
                """{"id":"1.20.4","name":"1.20.4","java_version":17,"stable":true}""".toByteArray(),
            "META-INF/versions.list" to "cbdcda0c\t1.20.4\t1.20.4/server-1.20.4.jar".toByteArray(),
            "META-INF/main-class" to "net.minecraft.server.Main".toByteArray(),
            "net/minecraft/bundler/Main.class" to classFile(61),
            "net/minecraft/bundler/Main\$FileEntry.class" to classFile(61)
        )
        val inspection = success(jar)
        assertEquals(ServerKind.MINECRAFT, inspection.kind)
        assertEquals(ServerFlavor.VANILLA, inspection.flavor)
        assertEquals("net.minecraft.bundler.Main", inspection.mainClass)
        assertEquals("1.20.4", inspection.minecraftVersion)
        assertEquals(61, inspection.classFileMajor)
        assertEquals(17, inspection.suggestedJreMajor)
        assertFalse(inspection.needsInstaller)
        assertTrue(inspection.confidence >= 0.9f)
        assertTrue(inspection.evidence.any { it.contains("version.json") })
        assertTrue(inspection.warnings.any { it.contains("bundler") })
    }

    @Test
    fun `classic vanilla jar maps Java 8 classes to supported JRE 17`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                mainClass = "net.minecraft.server.Main",
                implementationVersion = "1.12.2"
            ),
            "net/minecraft/server/Main.class" to classFile(52),
            "net/minecraft/server/MinecraftServer.class" to classFile(52)
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.VANILLA, inspection.flavor)
        assertEquals("1.12.2", inspection.minecraftVersion)
        assertEquals(52, inspection.classFileMajor)
        assertEquals(17, inspection.suggestedJreMajor)
    }

    @Test
    fun `paperclip jar is paper with manifest MC version and Java 21 classes`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                mainClass = "io.papermc.paperclip.Main",
                implementationVersion = "git-Paper-196 (MC: 1.20.4)"
            ),
            "patch.properties" to
                "originalHash=abc\npatchedHash=def\nsourceUrl=https://example/server.jar\nversion=1.20.4\n"
                    .toByteArray(),
            "io/papermc/paperclip/Main.class" to classFile(65)
        )
        val inspection = success(jar)
        assertEquals(ServerKind.MINECRAFT, inspection.kind)
        assertEquals(ServerFlavor.PAPER, inspection.flavor)
        assertEquals("1.20.4", inspection.minecraftVersion)
        assertEquals(65, inspection.classFileMajor)
        assertEquals(21, inspection.suggestedJreMajor)
        assertFalse(inspection.needsInstaller)
        assertTrue(inspection.warnings.any { it.contains("Paperclip") })
    }

    @Test
    fun `craftbukkit jar is paper with clean MC version from SNAPSHOT build`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                mainClass = "org.bukkit.craftbukkit.Main",
                implementationVersion = "1.20.4-R0.1-SNAPSHOT"
            ),
            "org/bukkit/craftbukkit/Main.class" to classFile(61)
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.PAPER, inspection.flavor)
        assertEquals("1.20.4", inspection.minecraftVersion)
        assertEquals(17, inspection.suggestedJreMajor)
    }

    @Test
    fun `fabric server launcher is detected and version falls back to filename`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                "net.fabricmc.loader.impl.launch.server.FabricServerLauncher"
            ),
            "net/fabricmc/loader/impl/launch/server/FabricServerLauncher.class" to classFile(65),
            fileName = "fabric-server-mc.1.20.4-loader.0.15.0-launcher.1.0.1.jar"
        )
        val inspection = success(jar)
        assertEquals(ServerKind.MINECRAFT, inspection.kind)
        assertEquals(ServerFlavor.FABRIC, inspection.flavor)
        assertEquals("1.20.4", inspection.minecraftVersion)
        assertEquals(21, inspection.suggestedJreMajor)
        assertTrue(inspection.evidence.any { it.contains("file name") })
    }

    @Test
    fun `fabric launcher without filename version reports unknown version honestly`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                "net.fabricmc.loader.impl.launch.server.FabricServerLauncher"
            ),
            "net/fabricmc/loader/impl/launch/server/FabricServerLauncher.class" to classFile(61)
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.FABRIC, inspection.flavor)
        assertNull(inspection.minecraftVersion)
        assertTrue(inspection.evidence.any { it.contains("could not be determined") })
    }

    @Test
    fun `forge server jar maps 49 dot x implementation version to no MC version`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                "net.minecraftforge.server.ServerMain",
                implementationVersion = "49.0.30"
            ),
            "net/minecraftforge/server/ServerMain.class" to classFile(61),
            "cpw/mods/bootstraplauncher/BootstrapLauncher.class" to classFile(61)
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.FORGE, inspection.flavor)
        assertNull(inspection.minecraftVersion)
        assertEquals(17, inspection.suggestedJreMajor)
    }

    @Test
    fun `bukkit family jar with main class is bukkit not vanilla`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest("com.example.CustomBukkitServer"),
            "org/bukkit/Bukkit.class" to classFile(61),
            "com/example/CustomBukkitServer.class" to classFile(61)
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.BUKKIT, inspection.flavor)
        assertEquals(ServerKind.MINECRAFT, inspection.kind)
    }

    /* ------------------------------------------------------------------ */
    /* Installers                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    fun `forge installer is flagged needsInstaller with MC version`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest("net.minecraftforge.installer.Main"),
            "net/minecraftforge/installer/Main.class" to classFile(61),
            "install_profile.json" to """{"spec":1,"minecraft":"1.20.4"}""".toByteArray(),
            "version.json" to
                """{"id":"1.20.4-forge-49.0.30","inheritsFrom":"1.20.4"}""".toByteArray()
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.FORGE, inspection.flavor)
        assertTrue(inspection.needsInstaller)
        assertEquals("1.20.4", inspection.minecraftVersion)
        assertTrue(inspection.warnings.any { it.contains("installer") })
    }

    @Test
    fun `fabric installer is flagged needsInstaller and installer version is not MC version`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                "net.fabricmc.installer.Main",
                implementationVersion = "1.0.1"
            ),
            "net/fabricmc/installer/Main.class" to classFile(52)
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.FABRIC, inspection.flavor)
        assertTrue(inspection.needsInstaller)
        assertNull(inspection.minecraftVersion)
        assertEquals(17, inspection.suggestedJreMajor)
        assertTrue(inspection.warnings.any { it.contains("installer") })
    }

    @Test
    fun `installer without Main-Class is still recognised`() {
        val jar = jar(
            "net/minecraftforge/installer/Main.class" to classFile(61),
            "install_profile.json" to "{}".toByteArray()
        )
        val inspection = success(jar)
        assertTrue(inspection.needsInstaller)
        assertEquals(ServerFlavor.FORGE, inspection.flavor)
    }

    /* ------------------------------------------------------------------ */
    /* Generic / failures                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    fun `generic executable jar is generic and never gets a MC version`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest(
                "wiremock.Run",
                implementationVersion = "3.9.2"
            ),
            "wiremock/Run.class" to classFile(55)
        )
        val inspection = success(jar)
        assertEquals(ServerKind.GENERIC, inspection.kind)
        assertEquals(ServerFlavor.GENERIC, inspection.flavor)
        assertEquals("wiremock.Run", inspection.mainClass)
        assertNull(inspection.minecraftVersion)
        assertEquals(17, inspection.suggestedJreMajor)
        assertTrue(inspection.confidence >= 0.8f)
    }

    @Test
    fun `generic jar without sampled classes defaults to JRE 21`() {
        val jar = jar("META-INF/MANIFEST.MF" to manifest("org.h2.tools.Console"))
        val inspection = success(jar)
        assertEquals(ServerFlavor.GENERIC, inspection.flavor)
        assertNull(inspection.classFileMajor)
        assertEquals(21, inspection.suggestedJreMajor)
    }

    @Test
    fun `no Main-Class library fails with actionable message`() {
        val jar = jar("com/example/Library.class" to classFile(61))
        val result = JarInspector.inspect(jar)
        assertTrue(result is InspectionResult.Failure)
        val message = (result as InspectionResult.Failure).message
        assertTrue(message, message.contains("Main-Class"))
        assertTrue(message, message.contains("java -jar"))
    }

    @Test
    fun `bukkit plugin fails and points at plugins folder`() {
        val jar = jar(
            "plugin.yml" to "name: TestPlugin\nmain: com.example.Plugin\n".toByteArray(),
            "com/example/Plugin.class" to classFile(61),
            "org/bukkit/Bukkit.class" to classFile(61)
        )
        val message = failure(jar)
        assertTrue(message, message.contains("plugin"))
        assertTrue(message, message.contains("plugins/"))
    }

    @Test
    fun `fabric mod fails and points at mods folder`() {
        val jar = jar(
            "fabric.mod.json" to """{"id":"testmod","version":"1.0"}""".toByteArray(),
            "com/example/Mod.class" to classFile(61)
        )
        val message = failure(jar)
        assertTrue(message, message.contains("Fabric mod"))
        assertTrue(message, message.contains("mods/"))
    }

    @Test
    fun `forge mod fails and points at mods folder`() {
        val jar = jar(
            "META-INF/mods.toml" to "modLoader=\"javafml\"\n".toByteArray(),
            "com/example/Mod.class" to classFile(61)
        )
        val message = failure(jar)
        assertTrue(message, message.contains("Forge/NeoForge mod"))
        assertTrue(message, message.contains("mods/"))
    }

    @Test
    fun `bukkit api library fails with API hint`() {
        val jar = jar("org/bukkit/Bukkit.class" to classFile(61))
        val message = failure(jar)
        assertTrue(message, message.contains("API"))
    }

    @Test
    fun `non zip file fails with corruption hint`() {
        val file = temp.newFile("not-a-jar.jar")
        file.writeText("this is definitely not a zip archive")
        val message = failure(file)
        assertTrue(message, message.contains("not a valid JAR"))
        assertTrue(message, message.contains("download"))
    }

    @Test
    fun `empty file fails`() {
        val file = temp.newFile("empty.jar")
        file.writeBytes(ByteArray(0))
        val message = failure(file)
        assertTrue(message, message.contains("empty"))
    }

    @Test
    fun `missing file fails`() {
        val file = File(temp.root, "does-not-exist.jar")
        val message = failure(file)
        assertTrue(message, message.contains("File not found"))
    }

    @Test
    fun `directory fails`() {
        val dir = temp.newFolder("server.jar")
        val message = failure(dir)
        assertTrue(message, message.contains("folder"))
    }

    /* ------------------------------------------------------------------ */
    /* Java version mapping / resilience                                   */
    /* ------------------------------------------------------------------ */

    @Test
    fun `class file majors map to the closest supported JRE`() {
        val expected = mapOf(
            52 to 17, // Java 8 -> 17
            55 to 17, // Java 11 -> 17
            61 to 17, // Java 17
            65 to 21, // Java 21
            69 to 25, // Java 25
            70 to 25  // Java 26 -> clamped to 25
        )
        for ((major, jre) in expected) {
            val jar = jar(
                "META-INF/MANIFEST.MF" to manifest("com.example.Main"),
                "com/example/Main.class" to classFile(major)
            )
            val inspection = success(jar)
            assertEquals("major $major", major, inspection.classFileMajor)
            assertEquals("major $major", jre, inspection.suggestedJreMajor)
        }
    }

    @Test
    fun `multi-release jar uses base class version not optional versioned classes`() {
        val jar = jar(
            "META-INF/MANIFEST.MF" to manifest("com.example.Main"),
            "com/example/Main.class" to classFile(52),
            "META-INF/versions/21/com/example/Utils.class" to classFile(65)
        )
        val inspection = success(jar)
        assertEquals(52, inspection.classFileMajor)
        assertEquals(17, inspection.suggestedJreMajor)
        assertTrue(inspection.evidence.any { it.contains("Multi-release") })
    }

    @Test
    fun `known launcher class without Main-Class is inferred with a warning`() {
        val jar = jar(
            "net/minecraft/server/Main.class" to classFile(61),
            "net/minecraft/server/MinecraftServer.class" to classFile(61)
        )
        val inspection = success(jar)
        assertEquals(ServerFlavor.VANILLA, inspection.flavor)
        assertEquals("net.minecraft.server.Main", inspection.mainClass)
        assertEquals(0.6f, inspection.confidence, 0.0001f)
        assertTrue(inspection.warnings.any { it.contains("no Main-Class") })
    }

    @Test
    fun `manifest continuation lines are parsed`() {
        val rawManifest = "Manifest-Version: 1.0\r\nMain-Class: com.example.\r\n Main\r\n\r\n"
        val jar = jar(
            "META-INF/MANIFEST.MF" to rawManifest.toByteArray(),
            "com/example/Main.class" to classFile(61)
        )
        val inspection = success(jar)
        assertEquals("com.example.Main", inspection.mainClass)
        assertEquals(ServerFlavor.GENERIC, inspection.flavor)
    }

    @Test
    fun `huge archives are capped and known launcher classes are still found`() {
        val entries = LinkedHashMap<String, ByteArray>()
        entries["META-INF/MANIFEST.MF"] = manifest("net.minecraft.server.Main")
        repeat(5_100) { entries["filler/file-$it.txt"] = ByteArray(1) }
        // This signature entry sits beyond the enumeration cap; the targeted
        // probe must still find it.
        entries["net/minecraft/server/Main.class"] = classFile(61)
        val jar = jar(entries, fileName = "huge.jar")
        val inspection = success(jar)
        assertEquals(ServerFlavor.VANILLA, inspection.flavor)
        assertEquals(JarInspector.MAX_ENTRIES_SCANNED, inspection.entriesScanned)
    }

    /* ------------------------------------------------------------------ */
    /* Optional real-JAR validation (skipped without the fixtures)         */
    /* ------------------------------------------------------------------ */

    @Test
    fun `real vanilla wiremock and h2 jars classify correctly when provided`() {
        val targets = targetsDirectory()
        assumeTrue("Set $TARGETS_ENV to the testtargets directory", targets != null)
        val dir = targets!!
        for (name in listOf("vanilla.jar", "wiremock.jar", "h2.jar")) {
            assumeTrue("$name not present in $dir", File(dir, name).isFile)
        }

        val vanilla = success(File(dir, "vanilla.jar"))
        assertEquals(ServerFlavor.VANILLA, vanilla.flavor)
        assertEquals("1.20.4", vanilla.minecraftVersion)
        assertEquals(17, vanilla.suggestedJreMajor)
        assertEquals("net.minecraft.bundler.Main", vanilla.mainClass)

        val wiremock = success(File(dir, "wiremock.jar"))
        assertEquals(ServerFlavor.GENERIC, wiremock.flavor)
        assertEquals("wiremock.Run", wiremock.mainClass)
        assertNull(wiremock.minecraftVersion)

        val h2 = success(File(dir, "h2.jar"))
        assertEquals(ServerFlavor.GENERIC, h2.flavor)
        assertEquals("org.h2.tools.Console", h2.mainClass)
        assertNull(h2.minecraftVersion)
        assertEquals(17, h2.suggestedJreMajor)
    }

    @Test
    fun `committed sample generic server jar classifies as generic`() {
        val candidates = listOf(
            File("../tools/sample-generic-server/generic-server.jar"),
            File("tools/sample-generic-server/generic-server.jar")
        )
        val sample = candidates.firstOrNull { it.isFile }
        assumeTrue("tools/sample-generic-server/generic-server.jar not built", sample != null)
        val inspection = success(sample!!)
        assertEquals(ServerKind.GENERIC, inspection.kind)
        assertEquals(ServerFlavor.GENERIC, inspection.flavor)
        assertEquals("dev.javahost.samples.generic.GenericServer", inspection.mainClass)
        assertEquals(17, inspection.suggestedJreMajor)
        assertFalse(inspection.needsInstaller)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun success(file: File): JarInspection {
        val result = JarInspector.inspect(file)
        assertTrue("expected success for ${file.name} but got $result", result is InspectionResult.Success)
        return (result as InspectionResult.Success).inspection
    }

    private fun failure(file: File): String {
        val result = JarInspector.inspect(file)
        assertTrue("expected failure for ${file.name} but got $result", result is InspectionResult.Failure)
        return (result as InspectionResult.Failure).message
    }

    private fun jar(
        vararg entries: Pair<String, ByteArray>,
        fileName: String? = null
    ): File = jar(linkedMapOf(*entries), fileName)

    private fun jar(entries: Map<String, ByteArray>, fileName: String? = null): File {
        val file = temp.newFile(fileName ?: "synthetic-${jarCounter++}.jar")
        JarOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun manifest(
        mainClass: String? = null,
        implementationVersion: String? = null,
        extra: Map<String, String> = emptyMap()
    ): ByteArray = buildString {
        append("Manifest-Version: 1.0\r\n")
        if (mainClass != null) append("Main-Class: $mainClass\r\n")
        if (implementationVersion != null) append("Implementation-Version: $implementationVersion\r\n")
        for ((key, value) in extra) append("$key: $value\r\n")
        append("\r\n")
    }.toByteArray()

    /** 12-byte class-file prefix: magic, minor 0, major, plus padding. */
    private fun classFile(major: Int): ByteArray = byteArrayOf(
        0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0, 0,
        (major ushr 8).toByte(), major.toByte(),
        0, 0, 0, 0
    )

    private fun targetsDirectory(): File? {
        val configured = System.getenv(TARGETS_ENV) ?: System.getProperty("javahost.testtargets")
        return configured?.let { File(it) }?.takeIf { it.isDirectory }
    }

    companion object {
        private const val TARGETS_ENV = "JAVAHOST_TESTTARGETS"
    }
}
