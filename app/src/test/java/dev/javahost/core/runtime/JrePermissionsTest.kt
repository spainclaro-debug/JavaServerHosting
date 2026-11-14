package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JrePermissionsTest {

    @get:Rule
    var tmp = TemporaryFolder()

    @Test
    fun chmodTreeMarksBinariesAndDirectoriesExecutable() {
        val home = tmp.newFolder("jre21")
        val bin = File(home, "bin").apply { mkdirs() }
        val java = File(bin, "java").apply { writeText("java") }
        File(bin, "keytool").writeText("keytool")
        val lib = File(home, "lib").apply { mkdirs() }
        val libjvm = File(File(lib, "server").apply { mkdirs() }, "libjvm.so").apply { writeText("jvm") }
        File(home, "release").writeText("JAVA_VERSION=\"21\"")
        File(home, "jexec").let { File(lib, "jexec").writeText("jexec") }
        File(lib, "jspawnhelper").writeText("spawn")

        val fake = FakeFileSystemOps()
        JrePermissions.chmodTree(fake, home)

        assertEquals(AndroidFileSystemOps.MODE_DIR, fake.modeOf(home))
        assertEquals(AndroidFileSystemOps.MODE_DIR, fake.modeOf(bin))
        assertEquals(AndroidFileSystemOps.MODE_EXEC, fake.modeOf(java))
        assertEquals(AndroidFileSystemOps.MODE_EXEC, fake.modeOf(File(bin, "keytool")))
        assertEquals(AndroidFileSystemOps.MODE_EXEC, fake.modeOf(libjvm))
        assertEquals(AndroidFileSystemOps.MODE_EXEC, fake.modeOf(File(lib, "jexec")))
        assertEquals(AndroidFileSystemOps.MODE_EXEC, fake.modeOf(File(lib, "jspawnhelper")))
        assertNull("release must keep its mode", fake.modeOf(File(home, "release")))
    }

    @Test
    fun needsExecuteBitRules() {
        val home = tmp.newFolder("home")
        fun rel(path: String): Boolean = JrePermissions.needsExecuteBit(home, File(home, path))
        assertTrue(rel("bin/java"))
        assertTrue(rel("lib/jexec"))
        assertTrue(rel("lib/jspawnhelper"))
        assertTrue(rel("lib/server/libjvm.so"))
        assertTrue(rel("lib/libz.so.1.4"))
        assertFalse(rel("release"))
        assertFalse(rel("conf/security.java"))
        assertFalse(rel("lib/security/cacerts"))
        assertFalse(rel("legal/LICENSE"))
    }

    @Test
    fun installedBytesSumsRegularFiles() {
        val home = tmp.newFolder("jre21")
        File(home, "a").writeBytes(ByteArray(10))
        File(home, "sub").mkdirs()
        File(home, "sub/b").writeBytes(ByteArray(25))
        assertEquals(35L, JrePermissions.installedBytes(home))
    }

    @Test
    fun installedBytesOfMissingDirectoryIsZero() {
        assertEquals(0L, JrePermissions.installedBytes(File(tmp.root, "nope")))
    }

    @Test
    fun archivePathsRejectEscapingPaths() {
        assertTrue(ArchivePaths.isSafeRelative("lib/server/libjvm.so"))
        assertFalse(ArchivePaths.isSafeRelative("../etc/passwd"))
        assertFalse(ArchivePaths.isSafeRelative("lib/../../etc/passwd"))
        assertFalse(ArchivePaths.isSafeRelative(""))
        assertFalse(ArchivePaths.isSafeRelative("/etc/passwd"))
        assertEquals("lib/x", ArchivePaths.normalizeEntryName("./lib/x"))
        assertTrue(ArchivePaths.isInsideOrEqual("lib/jvm/java-21-openjdk", "lib/jvm/java-21-openjdk"))
        assertTrue(ArchivePaths.isInsideOrEqual("lib/jvm/java-21-openjdk/bin", "lib/jvm/java-21-openjdk"))
        assertFalse(ArchivePaths.isInsideOrEqual("lib/jvm/java-21-openjdk-x", "lib/jvm/java-21-openjdk"))
        assertNull(ArchivePaths.safeLinkTarget(tmp.newFolder("base"), "lib/a", "/etc/passwd"))
    }
}
