package dev.javahost.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM tests for [SafePaths]: traversal rejection, symlink containment
 * checks and SAF file-name sanitization. No Android framework types are used.
 */
class FileBrowserSafetyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /* ------------------------------------------------------------------ */
    /* normalizeRelative                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    fun `normalize accepts plain relative paths`() {
        assertEquals("world/region", SafePaths.normalizeRelative("world/region"))
        assertEquals("server.properties", SafePaths.normalizeRelative("server.properties"))
        assertEquals("plugins/Some Plugin.jar", SafePaths.normalizeRelative("plugins/Some Plugin.jar"))
    }

    @Test
    fun `normalize collapses dot and empty segments`() {
        assertEquals("world/region", SafePaths.normalizeRelative("./world//region"))
        assertEquals("world/region", SafePaths.normalizeRelative("world///./region"))
        assertEquals("", SafePaths.normalizeRelative(""))
        assertEquals("", SafePaths.normalizeRelative("."))
        assertEquals("", SafePaths.normalizeRelative("./"))
    }

    @Test
    fun `normalize converts backslashes from Windows-ish input`() {
        assertEquals("plugins/EssentialsX.jar", SafePaths.normalizeRelative("plugins\\EssentialsX.jar"))
    }

    @Test
    fun `normalize rejects parent traversal in every position`() {
        assertNull(SafePaths.normalizeRelative("../etc/passwd"))
        assertNull(SafePaths.normalizeRelative(".."))
        assertNull(SafePaths.normalizeRelative("world/../../escape"))
        assertNull(SafePaths.normalizeRelative("world/.."))
        assertNull(SafePaths.normalizeRelative("./../x"))
        assertNull(SafePaths.normalizeRelative("a/b/../../../c"))
    }

    @Test
    fun `normalize rejects absolute POSIX paths`() {
        assertNull(SafePaths.normalizeRelative("/etc/passwd"))
        assertNull(SafePaths.normalizeRelative("/"))
        assertNull(SafePaths.normalizeRelative("\\etc\\passwd"))
    }

    @Test
    fun `normalize rejects Windows drive paths`() {
        assertNull(SafePaths.normalizeRelative("C:\\Windows\\system32"))
        assertNull(SafePaths.normalizeRelative("c:/temp"))
    }

    @Test
    fun `normalize rejects NUL bytes`() {
        assertNull(SafePaths.normalizeRelative("world/\u0000evil"))
        assertNull(SafePaths.normalizeRelative("\u0000"))
    }

    /* ------------------------------------------------------------------ */
    /* isWithin                                                           */
    /* ------------------------------------------------------------------ */

    @Test
    fun `isWithin accepts the base and its children`() {
        val base = tempFolder.newFolder("instance")
        val child = File(base, "world/region/r.0.0.mca")
        child.parentFile.mkdirs()
        child.writeText("data")
        assertTrue(SafePaths.isWithin(base, base))
        assertTrue(SafePaths.isWithin(base, child))
    }

    @Test
    fun `isWithin rejects siblings and absolute escapes`() {
        val base = tempFolder.newFolder("instance")
        val sibling = tempFolder.newFolder("other")
        assertFalse(SafePaths.isWithin(base, sibling))
        assertFalse(SafePaths.isWithin(base, File(base, "../other")))
        assertFalse(SafePaths.isWithin(base, File("/data/data/other-app/files")))
    }

    @Test
    fun `isWithin rejects symlinks that escape the instance directory`() {
        val base = tempFolder.newFolder("instance")
        val outside = tempFolder.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("top secret") }
        val link = File(base, "escape")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (t: Throwable) {
            Assume.assumeNoException("Filesystem does not support symlinks", t)
        }
        assertFalse(SafePaths.isWithin(base, link))
        assertFalse(SafePaths.isWithin(base, File(link, "secret.txt")))
        assertTrue(secret.exists())
    }

    @Test
    fun `isWithin accepts symlinks that stay inside the instance directory`() {
        val base = tempFolder.newFolder("instance")
        val target = File(base, "real").apply { mkdirs() }
        val link = File(base, "alias")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (t: Throwable) {
            Assume.assumeNoException("Filesystem does not support symlinks", t)
        }
        assertTrue(SafePaths.isWithin(base, link))
        assertTrue(SafePaths.isWithin(base, File(link, "file.txt")))
    }

    /* ------------------------------------------------------------------ */
    /* sanitizeFileName                                                   */
    /* ------------------------------------------------------------------ */

    @Test
    fun `sanitize keeps normal names untouched`() {
        assertEquals("server.properties", SafePaths.sanitizeFileName("server.properties"))
        assertEquals("EssentialsX-2.20.1.jar", SafePaths.sanitizeFileName("EssentialsX-2.20.1.jar"))
        assertEquals("世界.jar", SafePaths.sanitizeFileName("世界.jar"))
    }

    @Test
    fun `sanitize strips directory components from display names`() {
        assertEquals("passwd", SafePaths.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.txt", SafePaths.sanitizeFileName("..\\evil.txt"))
        assertEquals("plugin.jar", SafePaths.sanitizeFileName("/sdcard/Download/plugin.jar"))
    }

    @Test
    fun `sanitize replaces hostile characters`() {
        assertEquals("a_b_.jar", SafePaths.sanitizeFileName("a:b?.jar"))
        assertEquals("bad_name", SafePaths.sanitizeFileName("bad\u0001name"))
        assertEquals("pipe_name", SafePaths.sanitizeFileName("pipe|name"))
    }

    @Test
    fun `sanitize falls back for empty or dot names`() {
        assertEquals("imported-file", SafePaths.sanitizeFileName(null))
        assertEquals("imported-file", SafePaths.sanitizeFileName("   "))
        assertEquals("imported-file", SafePaths.sanitizeFileName("."))
        assertEquals("imported-file", SafePaths.sanitizeFileName(".."))
        assertEquals("fallback.bin", SafePaths.sanitizeFileName("", fallback = "fallback.bin"))
        assertEquals("", SafePaths.sanitizeFileName("", fallback = ""))
    }

    @Test
    fun `sanitize truncates very long names but keeps the extension`() {
        val longName = "a".repeat(400) + ".jar"
        val sanitized = SafePaths.sanitizeFileName(longName)
        assertTrue("length ${sanitized.length}", sanitized.length <= SafePaths.MAX_FILE_NAME_LENGTH)
        assertTrue(sanitized.endsWith(".jar"))
    }

    /* ------------------------------------------------------------------ */
    /* uniqueFile                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    fun `uniqueFile returns the requested name when free`() {
        val directory = tempFolder.newFolder("files")
        assertEquals(File(directory, "a.jar"), SafePaths.uniqueFile(directory, "a.jar"))
    }

    @Test
    fun `uniqueFile appends a counter without overwriting`() {
        val directory = tempFolder.newFolder("files")
        File(directory, "a.jar").writeText("first")
        val first = SafePaths.uniqueFile(directory, "a.jar")
        assertEquals("a (1).jar", first.name)
        first.writeText("second")
        val second = SafePaths.uniqueFile(directory, "a.jar")
        assertEquals("a (2).jar", second.name)
    }

    @Test
    fun `uniqueFile handles names without extension`() {
        val directory = tempFolder.newFolder("files")
        File(directory, "README").writeText("first")
        assertEquals("README (1)", SafePaths.uniqueFile(directory, "README").name)
    }
}
