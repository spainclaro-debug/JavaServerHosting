package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JreRuntimeValidationTest {

    @get:Rule
    var tmp = TemporaryFolder()

    private fun jreHome(name: String, bionic: Boolean = true): File {
        val home = File(tmp.root, name).apply { mkdirs() }
        File(home, "bin").mkdirs()
        File(home, "lib/server").mkdirs()
        File(home, "bin/java").writeBytes(
            (
                if (bionic) {
                    "ELF\u0000/system/bin/linker64\u0000libjli.so"
                } else {
                    "ELF\u0000/lib/ld-linux-aarch64.so.1\u0000libjli.so"
                }
            ).toByteArray(Charsets.ISO_8859_1)
        )
        File(home, "lib/server/libjvm.so").writeText("jvm")
        File(home, "release").writeText(
            "JAVA_VERSION=\"21.0.5\"\nOS_ARCH=\"aarch64\"\n"
        )
        return home
    }

    @Test
    fun locatesJavaHomeAtRoot() {
        val home = jreHome("jdk-21")
        assertEquals(home, JreRuntimeValidation.locateJavaHome(home))
    }

    @Test
    fun locatesNestedJavaHome() {
        val parent = File(tmp.root, "downloads").apply { mkdirs() }
        val home = File(parent, "jdk-21.0.5").apply { mkdirs() }
        File(home, "bin").mkdirs()
        File(home, "bin/java").writeText("ELF\u0000/system/bin/linker64")
        assertEquals(home, JreRuntimeValidation.locateJavaHome(parent))
    }

    @Test
    fun rejectsFolderWithoutJava() {
        val empty = File(tmp.root, "empty").apply { mkdirs() }
        assertNull(JreRuntimeValidation.locateJavaHome(empty))
    }

    @Test
    fun detectsBionicInterpreter() {
        assertTrue(JreRuntimeValidation.looksLikeAndroidBionic(File(jreHome("bionic"), "bin/java")))
        assertFalse(JreRuntimeValidation.looksLikeAndroidBionic(File(jreHome("glibc", bionic = false), "bin/java")))
    }

    @Test
    fun validatesArm64Architecture() {
        val home = jreHome("arch")
        assertEquals("aarch64", JreRuntimeValidation.architecture(home))
        assertTrue(JreRuntimeValidation.isArm64("aarch64"))
        assertTrue(JreRuntimeValidation.isArm64(null))
        assertFalse(JreRuntimeValidation.isArm64("x86_64"))
    }
}
