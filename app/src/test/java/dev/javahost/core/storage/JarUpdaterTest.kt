package dev.javahost.core.storage

import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.JarInspection
import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerKind
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Hermetic tests for [JarUpdater]: everything happens in a [TemporaryFolder] with
 * real tiny JARs written through [ZipOutputStream]. No Android types, no network.
 */
class JarUpdaterTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun instanceDir(): File = folder.newFolder("instance")

    /**
     * Writes a real, tiny runnable JAR: a manifest with [mainClass] plus one class
     * entry whose 8-byte header declares Java 17 (class-file major 61), so the
     * real [dev.javahost.core.jar.JarInspector] fills in the JRE suggestion.
     */
    private fun writeJar(file: File, mainClass: String): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\r\nMain-Class: $mainClass\r\n\r\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("com/example/Main.class"))
            zip.write(
                byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                    0x00, 0x00, 0x00, 0x3D
                )
            )
            zip.closeEntry()
        }
        return file
    }

    /** Writes a JAR the real inspector classifies as an installer (Fabric marker + installer main). */
    private fun writeInstallerJar(file: File): File {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write(
                "Manifest-Version: 1.0\r\nMain-Class: net.fabricmc.installer.Main\r\n\r\n"
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("net/fabricmc/installer/Main.class"))
            zip.write(
                byteArrayOf(
                    0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                    0x00, 0x00, 0x00, 0x3D
                )
            )
            zip.closeEntry()
        }
        return file
    }

    private fun names(dir: File): List<String> =
        dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun tempFiles(dir: File): List<File> =
        dir.listFiles()?.filter {
            it.name.startsWith(".jarupdate-") && it.name.endsWith(".tmp")
        } ?: emptyList()

    private fun failureMessage(result: Result<*>): String {
        val exception = result.exceptionOrNull()
        assertNotNull("expected the update to fail", exception)
        val message = exception!!.message
        assertNotNull("failure must carry a user-facing message", message)
        assertTrue("failure message must not be blank", message!!.isNotBlank())
        return message
    }

    private fun assertInstanceUnchanged(dir: File, oldJarBytes: ByteArray, expectedNames: List<String>) {
        assertArrayEquals(oldJarBytes, File(dir, "server.jar").readBytes())
        assertEquals("no files may be added or removed by a failed update", expectedNames, names(dir))
        assertTrue("no temp file may be left behind", tempFiles(dir).isEmpty())
        assertFalse("no backup may be created by a failed update", File(dir, "server.jar.bak").exists())
    }

    // ------------------------------------------------------------------
    // Success path
    // ------------------------------------------------------------------

    @Test
    fun `update replaces the jar in place and keeps the previous jar as bak`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        // Unrelated instance content that must survive the update untouched.
        val world = File(dir, "world").apply { mkdirs() }
        val levelDat = File(world, "level.dat").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val properties = File(dir, "server.properties").apply { writeText("motd=Hello\n") }
        File(dir, "plugins").mkdirs()
        val pluginJar = File(dir, "plugins/Example.jar").apply { writeText("plugin-bytes") }
        File(dir, "logs").mkdirs()
        val log = File(dir, "logs/latest.log").apply { writeText("log line\n") }
        val before = names(dir)

        val downloads = folder.newFolder("downloads")
        val source = writeJar(File(downloads, "paper.jar"), "com.example.New")
        val sourceBytes = source.readBytes()

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 42L)

        assertTrue("update should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        val updated = result.getOrThrow()
        assertEquals("server.jar", updated.jarFileName)
        assertEquals("server.jar.bak", updated.backupFileName)
        // Real JarInspector: generic manifest JAR with a Java 17 class header.
        assertEquals(ServerKind.GENERIC, updated.inspection.kind)
        assertEquals(ServerFlavor.GENERIC, updated.inspection.flavor)
        assertEquals(17, updated.inspection.suggestedJreMajor)

        val target = File(dir, "server.jar")
        val backup = File(dir, "server.jar.bak")
        assertTrue(target.isFile)
        assertTrue(backup.isFile)
        assertArrayEquals("target must contain the new JAR", sourceBytes, target.readBytes())
        assertArrayEquals("backup must contain the previous JAR", oldBytes, backup.readBytes())

        // Other instance files untouched.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), levelDat.readBytes())
        assertEquals("motd=Hello\n", properties.readText())
        assertEquals("plugin-bytes", pluginJar.readText())
        assertEquals("log line\n", log.readText())
        assertEquals("only the backup may be added", (before + "server.jar.bak").sorted(), names(dir))
        assertTrue(tempFiles(dir).isEmpty())
        // The picked source is copied, never moved/deleted: the caller owns it.
        assertTrue(source.isFile)
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    @Test
    fun `second update replaces the single backup and does not grow the folder`() {
        val dir = instanceDir()
        writeJar(File(dir, "server.jar"), "com.example.Old")
        File(dir, "server.properties").writeText("motd=Keep\n")
        val first = writeJar(folder.newFile("first.jar"), "com.example.First")
        val second = writeJar(folder.newFile("second.jar"), "com.example.Second")

        assertTrue(JarUpdater.update(dir, "server.jar", first, stamp = 1L).isSuccess)
        val firstJarBytes = first.readBytes()
        val afterFirst = names(dir)
        assertEquals(listOf("server.jar", "server.jar.bak", "server.properties"), afterFirst)

        val result = JarUpdater.update(dir, "server.jar", second, stamp = 2L)

        assertTrue("second update should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("server.jar.bak", result.getOrThrow().backupFileName)
        assertArrayEquals("backup must be the previous JAR", firstJarBytes, File(dir, "server.jar.bak").readBytes())
        assertArrayEquals(second.readBytes(), File(dir, "server.jar").readBytes())
        assertEquals("folder listing must stay the same after a second update", afterFirst, names(dir))
        assertEquals("exactly one backup may exist", 1, dir.listFiles()!!.count { it.name.endsWith(".bak") })
        assertTrue(tempFiles(dir).isEmpty())
        assertEquals("motd=Keep\n", File(dir, "server.properties").readText())
    }

    @Test
    fun `update without a previous jar creates no backup`() {
        val dir = instanceDir()
        val source = writeJar(folder.newFile("fresh.jar"), "com.example.Fresh")

        val result = JarUpdater.update(dir, "brand-new.jar", source, stamp = 3L)

        assertTrue("update should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("brand-new.jar", result.getOrThrow().jarFileName)
        assertNull(result.getOrThrow().backupFileName)
        assertArrayEquals(source.readBytes(), File(dir, "brand-new.jar").readBytes())
        assertEquals(listOf("brand-new.jar"), names(dir))
        assertTrue(tempFiles(dir).isEmpty())
    }

    @Test
    fun `successful update reports the injected inspection`() {
        val dir = instanceDir()
        writeJar(File(dir, "server.jar"), "com.example.Old")
        val source = writeJar(folder.newFile("paper.jar"), "com.example.New")
        val expected = JarInspection(
            kind = ServerKind.MINECRAFT,
            flavor = ServerFlavor.PAPER,
            minecraftVersion = "1.20.4",
            suggestedJreMajor = 21,
            classFileMajor = 65
        )

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 4L) { InspectionResult.Success(expected) }

        assertTrue("update should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(expected, result.getOrThrow().inspection)
        assertEquals("Minecraft Paper / Spigot family", result.getOrThrow().inspection.label)
        assertTrue(source.isFile)
    }

    @Test
    fun `a stale temp file with the same stamp is overwritten`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")
        File(dir, ".jarupdate-5.tmp").writeText("stale partial copy")

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 5L)

        assertTrue("update should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertArrayEquals(source.readBytes(), File(dir, "server.jar").readBytes())
        assertArrayEquals(oldBytes, File(dir, "server.jar.bak").readBytes())
        assertTrue(tempFiles(dir).isEmpty())
    }

    // ------------------------------------------------------------------
    // Validation failures (nothing may be touched)
    // ------------------------------------------------------------------

    @Test
    fun `missing source is rejected and the instance is untouched`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val before = names(dir)
        val missing = File(folder.root, "missing.jar")

        val result = JarUpdater.update(dir, "server.jar", missing, stamp = 6L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("does not exist", ignoreCase = true))
        assertInstanceUnchanged(dir, oldBytes, before)
    }

    @Test
    fun `a folder picked as the source is rejected and the target stays intact`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        File(dir, "world").mkdirs()
        val before = names(dir)
        val sourceFolder = folder.newFolder("not-a-jar")

        val result = JarUpdater.update(dir, "server.jar", sourceFolder, stamp = 7L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("folder", ignoreCase = true))
        assertInstanceUnchanged(dir, oldBytes, before)
    }

    @Test
    fun `target names that are not plain file names are rejected`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")
        val before = names(dir)
        val invalidNames = listOf(
            "../x.jar",
            "sub/../x.jar",
            "/abs.jar",
            "a/b.jar",
            "a\\b.jar",
            ".",
            "..",
            "",
            "   "
        )

        for (name in invalidNames) {
            val result = JarUpdater.update(dir, name, source, stamp = 8L)
            assertTrue("target \"$name\" must be rejected", result.isFailure)
            assertTrue(failureMessage(result).isNotBlank())
        }

        assertInstanceUnchanged(dir, oldBytes, before)
        assertFalse("traversal must not create a sibling file", File(folder.root, "x.jar").exists())
        assertFalse("traversal must not create a nested file", File(dir, "x.jar").exists())
    }

    @Test
    fun `the current jar cannot be used as its own replacement`() {
        val dir = instanceDir()
        val target = writeJar(File(dir, "server.jar"), "com.example.Old")
        val oldBytes = target.readBytes()
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", target, stamp = 9L)
        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("current server JAR", ignoreCase = true))
        assertInstanceUnchanged(dir, oldBytes, before)

        // The same file reached through a relative path is still the same file.
        val relative = JarUpdater.update(dir, "server.jar", File(dir, "./server.jar"), stamp = 9L)
        assertTrue(relative.isFailure)
        assertInstanceUnchanged(dir, oldBytes, before)
    }

    @Test
    fun `missing instance folder is rejected`() {
        val dir = File(folder.root, "does-not-exist")
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 10L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).isNotBlank())
        assertFalse(dir.exists())
    }

    // ------------------------------------------------------------------
    // Inspection rejections
    // ------------------------------------------------------------------

    @Test
    fun `inspection failure leaves the target unchanged and cleans up`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val source = writeJar(folder.newFile("plugin.jar"), "com.example.Plugin")
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 11L) {
            InspectionResult.Failure("this JAR is a plugin, not a server")
        }

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("plugin, not a server"))
        assertTrue(failureMessage(result).contains("not a usable server JAR"))
        assertInstanceUnchanged(dir, oldBytes, before)
    }

    @Test
    fun `installer jars are rejected without touching the instance`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val source = writeJar(folder.newFile("installer.jar"), "net.fabricmc.installer.Main")
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 12L) {
            InspectionResult.Success(
                JarInspection(
                    kind = ServerKind.MINECRAFT,
                    flavor = ServerFlavor.FORGE,
                    needsInstaller = true
                )
            )
        }

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("installer", ignoreCase = true))
        assertInstanceUnchanged(dir, oldBytes, before)
    }

    @Test
    fun `real installer jar is rejected by the default inspector`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val installer = writeInstallerJar(folder.newFile("fabric-installer.jar"))
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", installer, stamp = 13L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("installer", ignoreCase = true))
        assertInstanceUnchanged(dir, oldBytes, before)
    }

    @Test
    fun `non jar source is rejected by the default inspector`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val notes = folder.newFile("notes.txt").apply { writeText("not a jar at all") }
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", notes, stamp = 14L)

        assertTrue(result.isFailure)
        val message = failureMessage(result)
        assertTrue(message.contains("not a usable server JAR"))
        assertTrue(message.contains("JAR", ignoreCase = true))
        assertInstanceUnchanged(dir, oldBytes, before)
    }

    // ------------------------------------------------------------------
    // Rollback / cleanup
    // ------------------------------------------------------------------

    @Test
    fun `failure after copying rolls back and removes the temp file`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")
        // A non-empty directory at the backup path makes the swap fail *after* the
        // new JAR has been copied to the temp file, exercising the rollback path.
        val blocker = File(dir, "server.jar.bak").apply { mkdirs() }
        val keep = File(blocker, "keep.txt").apply { writeText("keep me") }
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 15L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("backup", ignoreCase = true))
        assertArrayEquals("target must stay intact", oldBytes, File(dir, "server.jar").readBytes())
        assertTrue("temp file must be cleaned up", tempFiles(dir).isEmpty())
        assertTrue("other files must not be touched", keep.isFile)
        assertEquals("keep me", keep.readText())
        assertEquals("folder listing must be unchanged", before, names(dir))
        assertTrue(File(dir, "server.jar.bak").isDirectory)
    }

    @Test
    fun `failure while moving the new jar into place cleans the temp file`() {
        val dir = instanceDir()
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")
        // A name too long for the filesystem makes the final rename fail after the
        // source has been copied to the temp file, exercising that rollback path.
        val longName = "x".repeat(300) + ".jar"

        val result = JarUpdater.update(dir, longName, source, stamp = 16L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).isNotBlank())
        assertTrue("temp file must be cleaned up", tempFiles(dir).isEmpty())
        assertTrue("no partial target may be left behind", names(dir).isEmpty())
    }

    @Test
    fun `target path that is a directory is rejected`() {
        val dir = instanceDir()
        File(dir, "server.jar").mkdirs()
        File(dir, "server.jar/child.txt").writeText("child")
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 17L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("not a file", ignoreCase = true))
        assertEquals(before, names(dir))
        assertTrue("the directory must not be moved", File(dir, "server.jar").isDirectory)
        assertTrue(tempFiles(dir).isEmpty())
    }

    @Test
    fun `target symlink escaping the instance folder is rejected`() {
        val dir = instanceDir()
        val outside = folder.newFile("outside.jar").apply { writeText("outside") }
        val link = File(dir, "escape.jar")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            org.junit.Assume.assumeTrue("symlinks are not supported on this platform", false)
        }
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")

        val result = JarUpdater.update(dir, "escape.jar", source, stamp = 18L)

        assertTrue(result.isFailure)
        assertTrue(failureMessage(result).contains("inside the instance folder", ignoreCase = true))
        assertEquals("outside", outside.readText())
        assertTrue("the symlink itself must not be replaced", link.isFile)
    }

    @Test
    fun `stale temp files from a previous hard kill are swept`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val staleA = File(dir, ".jarupdate-111.tmp").apply { writeText("stale-a") }
        val staleB = File(dir, ".jarupdate-222.tmp").apply { writeText("stale-b") }
        val unrelated = File(dir, "notes.tmp").apply { writeText("keep me") }
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 333L)

        assertTrue("update should succeed: ${result.exceptionOrNull()}", result.isSuccess)
        assertFalse("stale temp 1 must be swept", staleA.exists())
        assertFalse("stale temp 2 must be swept", staleB.exists())
        assertTrue("unrelated files must not be swept", unrelated.isFile)
        assertEquals("keep me", unrelated.readText())
        assertTrue(tempFiles(dir).isEmpty())
        assertArrayEquals(oldBytes, File(dir, "server.jar.bak").readBytes())
    }

    @Test
    fun `the current stamp's temp is not swept before writing`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        // Simulates a leftover of a previous hard kill that happens to carry this
        // stamp; it must survive the sweep until this call writes over it.
        val currentTemp = File(dir, ".jarupdate-444.tmp").apply { writeText("previous partial copy") }
        val source = writeJar(folder.newFile("new.jar"), "com.example.New")

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 444L) {
            InspectionResult.Failure("rejected by inspector")
        }

        assertTrue(result.isFailure)
        assertTrue("the current stamp's temp must not be swept before writing", currentTemp.exists())
        assertArrayEquals(oldBytes, File(dir, "server.jar").readBytes())
    }

    @Test
    fun `failure before the backup is written keeps the existing backup`() {
        val dir = instanceDir()
        val oldBytes = writeJar(File(dir, "server.jar"), "com.example.Old").readBytes()
        val previousBackup = File(dir, "server.jar.bak").apply { writeText("previous backup bytes") }
        val source = writeJar(folder.newFile("bad.jar"), "com.example.Bad")
        val before = names(dir)

        val result = JarUpdater.update(dir, "server.jar", source, stamp = 555L) {
            InspectionResult.Failure("rejected by inspector")
        }

        assertTrue(result.isFailure)
        assertEquals("a pre-existing backup must not be deleted", "previous backup bytes", previousBackup.readText())
        assertArrayEquals(oldBytes, File(dir, "server.jar").readBytes())
        assertEquals(before, names(dir))
        assertTrue(tempFiles(dir).isEmpty())
    }

    @Test
    fun `backup suffix is the frozen value`() {
        assertEquals(".bak", JarUpdater.BACKUP_SUFFIX)
    }
}
