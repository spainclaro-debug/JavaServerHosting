package dev.javahost.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeapSamplerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `parse G1 heap info`() {
        val output = """
            1234:
             garbage-first heap   total 524288K, used 123456K [0x0000000700000000, 0x0000000800000000)
              region size 1024K, 1 young (1024K), 0 survivors (0K)
             Metaspace       used 9876K, committed 10240K, reserved 1056768K
              class space    used 1234K, committed 1400K, reserved 1048576K
        """.trimIndent()
        val sample = HeapSampler.parseHeapInfo(output)
        assertNotNull(sample)
        sample!!
        assertEquals(120, sample.usedMb)
        assertEquals(512, sample.totalMb)
        assertEquals(HeapSource.JCMD, sample.source)
    }

    @Test
    fun `parse multi generation heap info sums generations`() {
        val output = """
            PS Young Generation
             total 30720K, used 2048K
            PS Old Generation
             total 67584K, used 1024K
        """.trimIndent()
        val sample = HeapSampler.parseHeapInfo(output)
        assertNotNull(sample)
        sample!!
        assertEquals(3, sample.usedMb)
        assertEquals(96, sample.totalMb)
    }

    @Test
    fun `parse used only heap info`() {
        val sample = HeapSampler.parseHeapInfo(" unused heap space: used 2048K")
        assertNotNull(sample)
        assertEquals(2, sample!!.usedMb)
        assertNull(sample.totalMb)
    }

    @Test
    fun `parse heap info rejects garbage`() {
        assertNull(HeapSampler.parseHeapInfo(null))
        assertNull(HeapSampler.parseHeapInfo(""))
        assertNull(HeapSampler.parseHeapInfo("no heap numbers here"))
    }

    @Test
    fun `parse vm rss from proc status`() {
        val status = """
            Name:	java
            State:	S (sleeping)
            VmRSS:	  123456 kB
        """.trimIndent()
        assertEquals(123456L, HeapSampler.parseVmRssKb(status))
        assertNull(HeapSampler.parseVmRssKb(null))
        assertNull(HeapSampler.parseVmRssKb("VmRSS: nope"))
    }

    @Test
    fun `sample uses jcmd when the executable exists`() {
        val javaHome = temp.newFolder("jre21")
        val jcmd = File(javaHome, "bin/jcmd").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        var capturedCommand: List<String>? = null
        var capturedEnv: Map<String, String>? = null
        val output = " garbage-first heap   total 1048576K, used 262144K\n"
        val sampler = HeapSampler(
            javaHome = javaHome,
            commandRunner = { command, env, _ ->
                capturedCommand = command
                capturedEnv = env
                output
            }
        )
        val sample = sampler.sample(4321L)
        assertNotNull(sample)
        assertEquals(256, sample!!.usedMb)
        assertEquals(1024, sample.totalMb)
        assertEquals(jcmd.absolutePath, capturedCommand!![0])
        assertEquals("4321", capturedCommand!![1])
        assertEquals("GC.heap_info", capturedCommand!![2])
        assertEquals(javaHome.absolutePath, capturedEnv!!["JAVA_HOME"])
        assertTrue(capturedEnv!!.getValue("LD_LIBRARY_PATH").contains("/lib/server"))
    }

    @Test
    fun `sample falls back to proc rss and returns null when unavailable`() {
        val javaHome = temp.newFolder("jre21")
        File(javaHome, "bin").mkdirs()
        File(javaHome, "bin/jcmd").writeText("not executable")
        val sampler = HeapSampler(
            javaHome = javaHome,
            commandRunner = { _, _, _ -> null }
        )
        // Fake pid that certainly has no /proc entry in this process namespace.
        assertNull(sampler.sample(999_999_999L))
    }

    @Test
    fun `sample rejects invalid pids`() {
        val sampler = HeapSampler(javaHome = null)
        assertNull(sampler.sample(0L))
        assertNull(sampler.sample(-1L))
    }
}
