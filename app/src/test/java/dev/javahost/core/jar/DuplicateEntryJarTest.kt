package dev.javahost.core.jar

import dev.javahost.core.model.InspectionResult
import dev.javahost.core.model.ServerFlavor
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fat JARs (Spring Boot / Suwayomi) commonly contain duplicate `LICENSE`
 * entries. Android's platform ZipFile can reject those, so JarInspector has a
 * Commons Compress fallback and must classify such JARs as generic servers.
 */
class DuplicateEntryJarTest {

    @get:Rule
    var tmp = TemporaryFolder()

    @Test
    fun `real Suwayomi fat jar is accepted when fixture is provided`() {
        val target = System.getenv("JAVAHOST_TESTTARGETS")
            ?.let { File(it, "suwayomi.jar") }
        org.junit.Assume.assumeTrue("Set JAVAHOST_TESTTARGETS with suwayomi.jar", target?.isFile == true)
        val inspection = JarInspector.inspect(target!!)
        assertTrue(inspection is InspectionResult.Success)
        val result = (inspection as InspectionResult.Success).inspection
        assertEquals(ServerFlavor.GENERIC, result.flavor)
        assertEquals("suwayomi.tachidesk.MainKt", result.mainClass)
    }

    @Test
    fun `jar with duplicate LICENSE entries is accepted via tolerant reader`() {
        val jar = File(tmp.root, "suwayomi-like.jar")
        ZipArchiveOutputStream(jar).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                val entry = ZipArchiveEntry(name)
                zip.putArchiveEntry(entry)
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
            add(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\r\nMain-Class: suwayomi.tachidesk.MainKt\r\n\r\n".toByteArray()
            )
            add("LICENSE", "license text".toByteArray())
            add("LICENSE", "license text".toByteArray())
            add("com/example/Main.class", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61))
        }

        val inspection = JarInspector.inspect(jar)
        assertTrue(inspection is InspectionResult.Success)
        val result = (inspection as InspectionResult.Success).inspection
        assertEquals(ServerFlavor.GENERIC, result.flavor)
        assertEquals("suwayomi.tachidesk.MainKt", result.mainClass)

        val facts = JarInspector.readFactsWithCommonsZip(jar)
        assertNotNull(facts)
        val tolerant = JarInspector.classify(facts!!)
        assertTrue(tolerant is InspectionResult.Success)
        assertEquals(ServerFlavor.GENERIC, (tolerant as InspectionResult.Success).inspection.flavor)
    }
}
