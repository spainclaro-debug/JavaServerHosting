package dev.javahost.core.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EulaManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `accept writes a readable eula file`() {
        val file = File(temp.newFolder("instance"), "eula.txt")
        assertTrue(EulaManager.accept(file, timestamp = 0L))
        assertTrue(file.isFile)
        val text = file.readText()
        assertTrue(text.contains("eula=true"))
        assertTrue(text.contains(EulaManager.EULA_URL))
        assertTrue(EulaManager.readAccepted(file))
    }

    @Test
    fun `rejecting writes eula false`() {
        val file = File(temp.root, "eula.txt")
        assertTrue(EulaManager.write(file, accepted = false))
        assertFalse(EulaManager.readAccepted(file))
        assertTrue(file.readText().contains("eula=false"))
    }

    @Test
    fun `read accepted handles missing, comments and case`() {
        assertFalse(EulaManager.readAccepted(File(temp.root, "does-not-exist.txt")))
        val file = File(temp.root, "eula-commented.txt")
        file.writeText("# eula=true\n# just a comment\nEULA=TRUE\n")
        assertTrue(EulaManager.readAccepted(file))
        val falseFile = File(temp.root, "eula-false.txt")
        falseFile.writeText("# eula=true\neula=false\n")
        assertFalse(EulaManager.readAccepted(falseFile))
    }
}
