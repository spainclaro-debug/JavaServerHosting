package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JreArchiveImporterTest {

    @get:Rule
    var tmp = TemporaryFolder()

    private val fakeFs = FakeFileSystemOps()
    private val warnings = ArrayList<String>()
    private val importer = JreArchiveImporter(fakeFs) { warnings += it }

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun workDir(): File = File(tmp.root, "work").apply { mkdirs() }

    private fun javaHomeItems(prefix: String = "jdk-21.0.5") = listOf(
        SyntheticArchives.dir(prefix),
        SyntheticArchives.dir("$prefix/bin"),
        SyntheticArchives.file("$prefix/bin/java", "JAVA"),
        SyntheticArchives.dir("$prefix/lib"),
        SyntheticArchives.file("$prefix/lib/server/libjvm.so", "JVM"),
        SyntheticArchives.file("$prefix/release", "JAVA_VERSION=\"21.0.5\"\nIMPLEMENTOR=\"Temurin\"\n")
    )

    @Test
    fun extractsTarGzAndLocatesNestedJavaHome() {
        val archive = write(
            "jdk.tar.gz",
            SyntheticArchives.tarGz(javaHomeItems())
        )
        val imported = importer.extract(archive, workDir())
        assertEquals("jdk-21.0.5", imported.home.name)
        assertEquals("21.0.5", imported.versionLabel)
        assertEquals(JreArchiveFormat.TAR_GZ, imported.format)
        assertEquals("JAVA", File(imported.home, "bin/java").readText())
        assertTrue(imported.home.absolutePath.startsWith(workDir().absolutePath))
    }

    @Test
    fun extractsTarXzWithNestedTopLevelDirectory() {
        val archive = write("jdk.tar.xz", SyntheticArchives.tarXz(javaHomeItems("zulu21")))
        val imported = importer.extract(archive, workDir())
        assertEquals("zulu21", imported.home.name)
        assertEquals("21.0.5", imported.versionLabel)
    }

    @Test
    fun findsJavaHomeAtArchiveRoot() {
        val archive = write(
            "flat.tar.gz",
            SyntheticArchives.tarGz(
                listOf(
                    SyntheticArchives.dir("bin"),
                    SyntheticArchives.file("bin/java", "JAVA"),
                    SyntheticArchives.file("release", "JAVA_VERSION=\"17.0.11\"\n")
                )
            )
        )
        val imported = importer.extract(archive, workDir())
        assertEquals("extract", imported.home.name)
        assertEquals("17.0.11", imported.versionLabel)
    }

    @Test
    fun extractsZipArchives() {
        val archive = write(
            "jdk.zip",
            SyntheticArchives.zip(
                mapOf(
                    "jdk-17.0.9/bin/java" to "JAVA".toByteArray(),
                    "jdk-17.0.9/release" to "JAVA_VERSION=\"17.0.9\"\n".toByteArray()
                )
            )
        )
        val imported = importer.extract(archive, workDir())
        assertEquals("jdk-17.0.9", imported.home.name)
        assertEquals("17.0.9", imported.versionLabel)
        assertEquals(JreArchiveFormat.ZIP, imported.format)
    }

    @Test
    fun rejectsTraversalInTarArchive() {
        val archive = write(
            "evil.tar.gz",
            SyntheticArchives.tarGz(
                listOf(SyntheticArchives.file("jdk/bin/../../../evil.txt", "pwned"))
            )
        )
        val error = assertThrows(JreInstallException::class.java) {
            importer.extract(archive, workDir())
        }
        assertTrue(error.message!!.contains("traversal", ignoreCase = true) ||
            error.message!!.contains("unsafe", ignoreCase = true))
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test
    fun rejectsAbsolutePathsInZipArchive() {
        val archive = write(
            "abs.zip",
            SyntheticArchives.zip(
                mapOf(
                    "jdk-21/bin/java" to "JAVA".toByteArray(),
                    "/etc/passwd" to "root".toByteArray()
                )
            )
        )
        val error = assertThrows(ArchiveFormatException::class.java) {
            importer.extract(archive, workDir())
        }
        assertTrue(error.message!!.contains("absolute path"))
    }

    @Test
    fun rejectsArchiveWithoutJavaRuntime() {
        val archive = write(
            "notjre.tar.gz",
            SyntheticArchives.tarGz(
                listOf(SyntheticArchives.file("README.txt", "not a jre"))
            )
        )
        val error = assertThrows(JreInstallException::class.java) {
            importer.extract(archive, workDir())
        }
        assertTrue(error.message!!.contains("bin/java"))
    }

    @Test
    fun rejectsUnsupportedFormat() {
        val archive = write("random.bin", ByteArray(64) { it.toByte() })
        val error = assertThrows(ArchiveFormatException::class.java) {
            importer.extract(archive, workDir())
        }
        assertTrue(error.message!!.contains("Unsupported archive format"))
    }

    @Test
    fun detectsFormatsByMagicBytes() {
        val dir = tmp.newFolder()
        val xz = File(dir, "a.bin").apply { writeBytes(SyntheticArchives.tarXz(emptyList())) }
        val gz = File(dir, "b.bin").apply { writeBytes(SyntheticArchives.tarGz(emptyList())) }
        val zip = File(dir, "c.bin").apply { writeBytes(SyntheticArchives.zip(emptyMap())) }
        val tar = File(dir, "d.bin").apply {
            writeBytes(SyntheticArchives.tar(listOf(SyntheticArchives.file("readme.txt", "x"))))
        }
        assertEquals(JreArchiveFormat.TAR_XZ, importer.detectFormat(xz))
        assertEquals(JreArchiveFormat.TAR_GZ, importer.detectFormat(gz))
        assertEquals(JreArchiveFormat.ZIP, importer.detectFormat(zip))
        assertEquals(JreArchiveFormat.TAR, importer.detectFormat(tar))
    }

    @Test
    fun skipsUnsafeSymlinksWhenImporting() {
        val archive = write(
            "links.tar.gz",
            SyntheticArchives.tarGz(
                javaHomeItems() + listOf(
                    SyntheticArchives.symlink("jdk-21.0.5/lib/escape", "../../../../etc/passwd")
                )
            )
        )
        val imported = importer.extract(archive, workDir())
        assertEquals(null, fakeFs.linkTarget(File(imported.home, "lib/escape")))
        assertTrue(warnings.any { it.contains("unsafe symbolic link") })
    }
}
