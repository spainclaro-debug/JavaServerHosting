package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DebExtractorTest {

    @get:Rule
    var tmp = TemporaryFolder()

    private val fakeFs = FakeFileSystemOps()
    private val warnings = ArrayList<String>()
    private val extractor = DebExtractor(fakeFs) { warnings += it }

    private val usr = "data/data/com.termux/files/usr"
    private val jreRoot = "$usr/lib/jvm/java-21-openjdk"

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun stage(): File = File(tmp.root, "stage").apply { mkdirs() }

    private fun openJdkDeb(): File = write(
        "openjdk-21.deb",
        SyntheticArchives.deb(
            SyntheticArchives.tarXz(
                listOf(
                    SyntheticArchives.dir("./$usr"),
                    SyntheticArchives.dir("./$jreRoot"),
                    SyntheticArchives.dir("./$jreRoot/bin"),
                    SyntheticArchives.file("./$jreRoot/bin/java", "JAVA-BINARY", 0b111_101_101),
                    SyntheticArchives.file("./$jreRoot/release", "JAVA_VERSION=\"21.0.13\"\n"),
                    SyntheticArchives.dir("./$jreRoot/lib"),
                    SyntheticArchives.dir("./$jreRoot/lib/server"),
                    SyntheticArchives.file("./$jreRoot/lib/server/libjvm.so", "JVM"),
                    SyntheticArchives.dir("./$jreRoot/lib/security"),
                    SyntheticArchives.symlink(
                        "./$jreRoot/lib/security/cacerts",
                        "/data/data/com.termux/files/usr/etc/tls/certs/java/cacerts"
                    ),
                    SyntheticArchives.dir("./$jreRoot/conf"),
                    SyntheticArchives.file("./$jreRoot/conf/security.java", "config"),
                    SyntheticArchives.dir("./$jreRoot/jmods"),
                    SyntheticArchives.file("./$jreRoot/jmods/java.base.jmod", "jmod"),
                    SyntheticArchives.dir("./$jreRoot/demo"),
                    SyntheticArchives.file("./$jreRoot/demo/jfc", "demo"),
                    SyntheticArchives.dir("./$jreRoot/include"),
                    SyntheticArchives.file("./$jreRoot/include/jni.h", "jni"),
                    SyntheticArchives.dir("./$jreRoot/man"),
                    SyntheticArchives.file("./$jreRoot/man/man1/java.1", "man"),
                    SyntheticArchives.file("./$jreRoot/lib/ct.sym", "symbols"),
                    // Not part of the JRE home: must be ignored.
                    SyntheticArchives.file("./$usr/bin/java", "wrapper")
                )
            )
        )
    )

    @Test
    fun extractsOpenJdkHomeAndTrimsDevelopmentContent() {
        val dest = stage()
        extractor.extract(openJdkDeb(), PackageExtraction.OpenJdk(21), dest)

        assertEquals("JAVA-BINARY", File(dest, "bin/java").readText())
        assertEquals("JVM", File(dest, "lib/server/libjvm.so").readText())
        assertEquals("config", File(dest, "conf/security.java").readText())
        assertTrue(File(dest, "release").isFile)

        assertFalse("jmods must be trimmed", File(dest, "jmods").exists())
        assertFalse("demo must be trimmed", File(dest, "demo").exists())
        assertFalse("include must be trimmed", File(dest, "include").exists())
        assertFalse("man must be trimmed", File(dest, "man").exists())
        assertFalse("lib/ct.sym must be trimmed", File(dest, "lib/ct.sym").exists())
        assertFalse("outer Termux bin/ must be ignored", File(dest, "bin/wrapper").exists())

        assertTrue("bin/java must be chmod 755", fakeFs.hasChmod(File(dest, "bin/java"), 0b111_101_101))
        assertNull(
            "the cacerts absolute symlink must not be recreated",
            fakeFs.linkTarget(File(dest, "lib/security/cacerts"))
        )
    }

    @Test
    fun overlaysSharedLibrariesFlattenedPreservingRelativeLinks() {
        val dest = stage()
        extractor.extract(openJdkDeb(), PackageExtraction.OpenJdk(21), dest)
        val libDeb = write(
            "zlib.deb",
            SyntheticArchives.deb(
                SyntheticArchives.tarXz(
                    listOf(
                        SyntheticArchives.dir("./$usr/lib"),
                        SyntheticArchives.file("./$usr/lib/libz.so.1.4", "ZLIB"),
                        SyntheticArchives.symlink("./$usr/lib/libz.so.1", "libz.so.1.4"),
                        SyntheticArchives.symlink("./$usr/lib/libz.so", "libz.so.1"),
                        SyntheticArchives.dir("./$usr/include"),
                        SyntheticArchives.file("./$usr/include/zlib.h", "header"),
                        SyntheticArchives.dir("./$usr/lib/pkgconfig"),
                        SyntheticArchives.file("./$usr/lib/pkgconfig/zlib.pc", "pc")
                    )
                )
            )
        )
        extractor.extract(libDeb, PackageExtraction.SharedLibraries, dest)

        assertEquals("ZLIB", File(dest, "lib/libz.so.1.4").readText())
        assertEquals("libz.so.1.4", fakeFs.linkTarget(File(dest, "lib/libz.so.1")))
        assertEquals("libz.so.1", fakeFs.linkTarget(File(dest, "lib/libz.so")))
        assertFalse("headers must not be installed", File(dest, "include").exists())
        assertFalse("lib/pkgconfig must not be flattened", File(dest, "lib/pkgconfig").exists())
    }

    @Test
    fun overlaysCaCertificatesJavaCacertsIntoTheJre() {
        val dest = stage()
        extractor.extract(openJdkDeb(), PackageExtraction.OpenJdk(21), dest)
        assertFalse("cacerts is missing before the overlay", File(dest, "lib/security/cacerts").exists())

        val caDeb = write(
            "ca-certificates-java.deb",
            SyntheticArchives.deb(
                SyntheticArchives.tarXz(
                    listOf(
                        SyntheticArchives.dir("./$usr/etc"),
                        SyntheticArchives.dir("./$usr/etc/tls"),
                        SyntheticArchives.dir("./$usr/etc/tls/certs"),
                        SyntheticArchives.dir("./$usr/etc/tls/certs/java"),
                        SyntheticArchives.file(
                            "./$usr/etc/tls/certs/java/cacerts", "TRUST-STORE"
                        )
                    )
                )
            )
        )
        extractor.extract(caDeb, PackageExtraction.JavaCacerts, dest)
        assertEquals("TRUST-STORE", File(dest, "lib/security/cacerts").readText())
    }

    @Test
    fun rejectsPathTraversalEntries() {
        val evilDeb = write(
            "evil.deb",
            SyntheticArchives.deb(
                SyntheticArchives.tarXz(
                    listOf(
                        SyntheticArchives.file(
                            "./$jreRoot/../../../../../evil.txt", "pwned"
                        )
                    )
                )
            )
        )
        val dest = stage()
        val error = assertThrows(JreInstallException::class.java) {
            extractor.extract(evilDeb, PackageExtraction.OpenJdk(21), dest)
        }
        assertTrue(error.message!!.contains("traversal", ignoreCase = true) ||
            error.message!!.contains("unsafe", ignoreCase = true))
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun skipsUnsafeSymlinkTargetsInsteadOfCreatingThem() {
        val deb = write(
            "link.deb",
            SyntheticArchives.deb(
                SyntheticArchives.tarXz(
                    listOf(
                        SyntheticArchives.dir("./$jreRoot"),
                        SyntheticArchives.dir("./$jreRoot/lib"),
                        SyntheticArchives.symlink("./$jreRoot/lib/escape", "../../../../etc/passwd"),
                        SyntheticArchives.symlink("./$jreRoot/lib/absolute", "/etc/passwd")
                    )
                )
            )
        )
        val dest = stage()
        extractor.extract(deb, PackageExtraction.OpenJdk(21), dest)
        assertNull(fakeFs.linkTarget(File(dest, "lib/escape")))
        assertNull(fakeFs.linkTarget(File(dest, "lib/absolute")))
        assertEquals(2, warnings.count { it.contains("unsafe symbolic link") })
    }

    @Test
    fun ignoresFilesOutsideTheTermuxPrefix() {
        val deb = write(
            "other.deb",
            SyntheticArchives.deb(
                SyntheticArchives.tarXz(
                    listOf(
                        SyntheticArchives.file("./etc/passwd", "root"),
                        SyntheticArchives.file("./usr/share/doc/foo", "doc")
                    )
                )
            )
        )
        val dest = stage()
        extractor.extract(deb, PackageExtraction.OpenJdk(21), dest)
        assertFalse(File(dest, "etc/passwd").exists())
    }

    @Test
    fun rejectsPackageWithoutDataArchive() {
        val deb = write(
            "empty.deb",
            SyntheticArchives.ar(listOf("debian-binary" to "2.0\n".toByteArray()))
        )
        val error = assertThrows(ArchiveFormatException::class.java) {
            extractor.extract(deb, PackageExtraction.OpenJdk(21), stage())
        }
        assertTrue(error.message!!.contains("data.tar"))
    }

    @Test
    fun rejectsCorruptPackage() {
        val deb = write("corrupt.deb", ByteArray(128) { 0x41 })
        val error = assertThrows(ArchiveFormatException::class.java) {
            extractor.extract(deb, PackageExtraction.OpenJdk(21), stage())
        }
        assertTrue(error.message!!.contains("not a Debian package", ignoreCase = true))
    }

    @Test
    fun rejectsUnsupportedDataCompression() {
        val deb = write(
            "zstd.deb",
            SyntheticArchives.ar(
                listOf(
                    "debian-binary" to "2.0\n".toByteArray(),
                    "data.tar.zst" to ByteArray(32) { 0x13 }
                )
            )
        )
        val error = assertThrows(ArchiveFormatException::class.java) {
            extractor.extract(deb, PackageExtraction.OpenJdk(21), stage())
        }
        assertTrue(error.message!!.contains("Unsupported package data format"))
    }

    @Test
    fun reportsMonotonicProgress() {
        val samples = ArrayList<Pair<Long, Long>>()
        extractor.extract(openJdkDeb(), PackageExtraction.OpenJdk(21), stage()) { done, total ->
            samples += done to total
        }
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.second > 0 })
        assertEquals(samples.map { it.first }, samples.map { it.first }.sorted())
        assertTrue(samples.last().first > 0)
    }
}
