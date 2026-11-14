package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class TermuxPackagesParserTest {

    private val sample = """
        Package: openjdk-21
        Version: 21.0.13-2
        Architecture: aarch64
        Maintainer: @termux
        Installed-Size: 333925
        Depends: ca-certificates-java, libandroid-shmem,
         libandroid-spawn, libiconv, libjpeg-turbo, zlib, littlecms, alsa-plugins
        Filename: pool/main/o/openjdk-21/openjdk-21_21.0.13-2_aarch64.deb
        Size: 106011440
        SHA256: AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899
        Description: OpenJDK 21
         A multi-line description continuation.

        Package: libandroid-shmem
        Version: 0.7
        Filename: pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb
        Size: 7216
        SHA256: 1111111111111111111111111111111111111111111111111111111111111111

        Package: broken-stanza-without-filename
        Version: 1.0
    """.trimIndent()

    @Test
    fun parsesAllStanzasAndFields() {
        val packages = TermuxPackagesParser.parse(sample)
        assertEquals(2, packages.size)

        val openjdk = packages[0]
        assertEquals("openjdk-21", openjdk.name)
        assertEquals("21.0.13-2", openjdk.version)
        assertEquals(
            "pool/main/o/openjdk-21/openjdk-21_21.0.13-2_aarch64.deb",
            openjdk.filename
        )
        assertEquals(106011440L, openjdk.size)
        assertEquals(
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            openjdk.sha256
        )
        assertEquals("aarch64", openjdk.architecture)
        assertEquals(
            listOf(
                listOf("ca-certificates-java"),
                listOf("libandroid-shmem"),
                listOf("libandroid-spawn"),
                listOf("libiconv"),
                listOf("libjpeg-turbo"),
                listOf("zlib"),
                listOf("littlecms"),
                listOf("alsa-plugins")
            ),
            openjdk.depends.map { group -> group.map { it.name } }
        )
        assertTrue(openjdk.requiresCaCertificatesJava)

        val shmem = packages[1]
        assertEquals("libandroid-shmem", shmem.name)
        assertTrue(shmem.depends.isEmpty())
        assertEquals(7216L, shmem.size)
    }

    @Test
    fun skipsStanzasWithoutRequiredFields() {
        val text = sample + "\n\nPackage: no-version\nFilename: pool/x.deb\n"
        val packages = TermuxPackagesParser.parse(text)
        assertTrue(packages.none { it.name == "broken-stanza-without-filename" })
        assertTrue(packages.none { it.name == "no-version" })
    }

    @Test
    fun toleratesCrlfAndBlankLines() {
        val crlf = sample.replace("\n", "\r\n")
        val packages = TermuxPackagesParser.parse(crlf)
        assertEquals(2, packages.size)
        assertEquals("openjdk-21", packages[0].name)
        assertEquals(8, packages[0].depends.size)
    }

    @Test
    fun parsesEmptyOrGarbageInput() {
        assertTrue(TermuxPackagesParser.parse("").isEmpty())
        assertTrue(TermuxPackagesParser.parse("\n\n\n").isEmpty())
        assertTrue(TermuxPackagesParser.parse("not a package stanza").isEmpty())
    }

    @Test
    fun parsesDependsAlternativesConstraintsAndArchQualifiers() {
        val groups = TermuxPackagesParser.parseDepends(
            "foo (>= 1.2), bar | baz:any, qux [arm64], libx (>= 2.0, < 3.0)"
        )
        assertEquals(4, groups.size)
        assertEquals(DependencyAlternative("foo", ">= 1.2"), groups[0][0])
        assertEquals(listOf("bar", "baz"), groups[1].map { it.name })
        assertEquals(listOf("qux"), groups[2].map { it.name })
        assertEquals(DependencyAlternative("libx", ">= 2.0, < 3.0"), groups[3][0])
    }

    @Test
    fun parsesProvides() {
        val text = """
            Package: provider
            Version: 1.0
            Filename: pool/provider.deb
            Provides: virtual-thing (= 2.0), other-virtual
        """.trimIndent()
        val pkg = TermuxPackagesParser.parse(text).single()
        assertEquals(listOf("virtual-thing", "other-virtual"), pkg.provides)
    }

    @Test
    fun parsesGzipCompressedIndex() {
        val bytes = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(sample.toByteArray()) }
        }.toByteArray()
        val packages = TermuxPackagesParser.parseGzip(bytes)
        assertEquals(2, packages.size)
        assertEquals("openjdk-21", packages[0].name)
    }

    @Test
    fun missingOptionalFieldsBecomeDefaults() {
        val text = """
            Package: minimal
            Version: 1.0
            Filename: pool/minimal.deb
        """.trimIndent()
        val pkg = TermuxPackagesParser.parse(text).single()
        assertEquals("", pkg.sha256)
        assertEquals(0L, pkg.size)
        assertTrue(pkg.depends.isEmpty())
        assertTrue(pkg.provides.isEmpty())
        assertNull(pkg.architecture)
    }

    @Test
    fun dependsRawIsReadable() {
        val pkg = TermuxPackagesParser.parse(sample).first()
        assertNotNull(pkg)
        assertTrue(pkg.dependsRaw.contains("ca-certificates-java"))
        assertTrue(pkg.dependsRaw.contains("libiconv"))
    }
}
