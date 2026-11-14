package dev.javahost.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JnaCompatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun libraryJar(relativePath: String): File {
        val file = File(temp.root, "libraries/$relativePath")
        file.parentFile.mkdirs()
        file.writeText("jar")
        return file
    }

    @Test
    fun `detects Paper maven layout`() {
        libraryJar("net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar")
        assertEquals(5 to 13, JnaCompat.detectJnaVersion(File(temp.root, "libraries")))
    }

    @Test
    fun `detects plain layout`() {
        libraryJar("jna-5.14.0.jar")
        assertEquals(5 to 14, JnaCompat.detectJnaVersion(File(temp.root, "libraries")))
    }

    @Test
    fun `picks the highest version when several exist`() {
        libraryJar("net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar")
        libraryJar("net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar")
        assertEquals(5 to 14, JnaCompat.detectJnaVersion(File(temp.root, "libraries")))
    }

    @Test
    fun `ignores unrelated jars and missing directory`() {
        libraryJar("net/minecraft/server/1.20.4/server-1.20.4.jar")
        assertEquals(null, JnaCompat.detectJnaVersion(File(temp.root, "libraries")))
        assertNull(JnaCompat.detectJnaVersion(File(temp.root, "nope")))
    }

    @Test
    fun `maps versions to packaged dispatchers`() {
        assertEquals("jna/5.13/libjnidispatch.so", JnaCompat.assetFor(5 to 13))
        assertEquals("jna/5.13/libjnidispatch.so", JnaCompat.assetFor(5 to 12))
        assertEquals("jna/5.19/libjnidispatch.so", JnaCompat.assetFor(5 to 14))
        assertEquals("jna/5.19/libjnidispatch.so", JnaCompat.assetFor(5 to 19))
        assertNull(JnaCompat.assetFor(6 to 0))
        assertNull(JnaCompat.assetFor(4 to 13))
    }
}
