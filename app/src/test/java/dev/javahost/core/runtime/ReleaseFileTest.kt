package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReleaseFileTest {

    @get:Rule
    var tmp = TemporaryFolder()

    @Test
    fun parsesQuotedAndUnquotedValues() {
        val map = ReleaseFile.parse(
            """
            # comment
            JAVA_VERSION="21.0.13"
            IMPLEMENTOR='Termux'
            OS_NAME=Linux

            JAVA_RUNTIME_VERSION=21.0.13+11
            """.trimIndent()
        )
        assertEquals("21.0.13", map["JAVA_VERSION"])
        assertEquals("Termux", map["IMPLEMENTOR"])
        assertEquals("Linux", map["OS_NAME"])
        assertEquals("21.0.13+11", map["JAVA_RUNTIME_VERSION"])
    }

    @Test
    fun readsVersionLabelFromHome() {
        val home = tmp.newFolder("jre21")
        File(home, "release").writeText("JAVA_VERSION=\"21.0.13\"\n")
        assertEquals("21.0.13", ReleaseFile.versionLabel(home))
        assertNull(ReleaseFile.versionLabel(tmp.newFolder("empty")))
    }

    @Test
    fun extractsMajorVersionFromLabels() {
        assertEquals(21, ReleaseFile.majorVersion("21.0.13"))
        assertEquals(25, ReleaseFile.majorVersion("25-ea"))
        assertEquals(17, ReleaseFile.majorVersion("17"))
        assertEquals(8, ReleaseFile.majorVersion("1.8.0_402"))
        assertEquals(21, ReleaseFile.majorVersion("jdk-21.0.5+11"))
        assertNull(ReleaseFile.majorVersion(null))
        assertNull(ReleaseFile.majorVersion(""))
        assertNull(ReleaseFile.majorVersion("no digits here"))
    }

    @Test
    fun extractsMajorVersionFromArchiveNames() {
        assertEquals(21, ReleaseFile.majorVersionFromName("jdk-21.0.5.tar.gz"))
        assertEquals(17, ReleaseFile.majorVersionFromName("OpenJDK17U-jre_aarch64_linux_hotspot.tar.gz"))
        assertEquals(25, ReleaseFile.majorVersionFromName("jre_25_linux_aarch64.tar.gz"))
        assertEquals(21, ReleaseFile.majorVersionFromName("Temurin java 21 runtime"))
        assertNull(ReleaseFile.majorVersionFromName("runtime.tar.gz"))
    }
}
