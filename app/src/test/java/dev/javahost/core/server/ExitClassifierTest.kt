package dev.javahost.core.server

import dev.javahost.core.model.ExitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitClassifierTest {

    @Test
    fun `signal is derived from 128 plus signal exit codes`() {
        assertEquals(9, ExitClassifier.signalOf(137))
        assertEquals(15, ExitClassifier.signalOf(143))
        assertNull(ExitClassifier.signalOf(0))
        assertNull(ExitClassifier.signalOf(1))
        assertNull(ExitClassifier.signalOf(null))
    }

    @Test
    fun `user requested stop is expected`() {
        val result = ExitClassifier.classify(
            exitCode = 143,
            stopRequested = true,
            force = false,
            tail = emptyList()
        )
        assertEquals(ExitReason.STOPPED_BY_USER, result.reason)
        assertTrue(result.expected)
        assertEquals("Stopped by user", result.summary)
        assertEquals(15, result.signal)
    }

    @Test
    fun `forced stop summary mentions force`() {
        val result = ExitClassifier.classify(137, stopRequested = true, force = true, tail = emptyList())
        assertEquals(ExitReason.STOPPED_BY_USER, result.reason)
        assertTrue(result.expected)
        assertEquals("Stopped by user (forced)", result.summary)
    }

    @Test
    fun `out of memory in tail`() {
        val result = ExitClassifier.classify(
            exitCode = 1,
            stopRequested = false,
            force = false,
            tail = listOf("[Server thread/INFO]: hello", "java.lang.OutOfMemoryError: Java heap space")
        )
        assertEquals(ExitReason.CRASH_OUT_OF_MEMORY, result.reason)
        assertFalse(result.expected)
    }

    @Test
    fun `port in use in tail`() {
        val result = ExitClassifier.classify(
            exitCode = 1,
            stopRequested = false,
            force = false,
            tail = listOf("java.net.BindException: Address already in use")
        )
        assertEquals(ExitReason.CRASH_PORT_IN_USE, result.reason)
    }

    @Test
    fun `unsupported java in tail`() {
        val result = ExitClassifier.classify(
            exitCode = 1,
            stopRequested = false,
            force = false,
            tail = listOf("java.lang.UnsupportedClassVersionError: class file major version 65")
        )
        assertEquals(ExitReason.CRASH_UNSUPPORTED_JAVA, result.reason)
    }

    @Test
    fun `sigkill means killed by system`() {
        val result = ExitClassifier.classify(137, stopRequested = false, force = false, tail = emptyList())
        assertEquals(ExitReason.KILLED_BY_SYSTEM, result.reason)
        assertEquals(9, result.signal)
        assertFalse(result.expected)
    }

    @Test
    fun `exit code zero is a normal stop`() {
        val result = ExitClassifier.classify(
            exitCode = 0,
            stopRequested = false,
            force = false,
            tail = listOf("[Server thread/INFO]: Stopping server")
        )
        assertEquals(ExitReason.STOPPED_BY_USER, result.reason)
        assertTrue(result.expected)
    }

    @Test
    fun `exit code zero with a stale missing-library warning is still a normal stop`() {
        val result = ExitClassifier.classify(
            exitCode = 0,
            stopRequested = true,
            force = false,
            tail = listOf(
                "[Server thread/INFO]: Stopping server",
                "[Server thread/WARN]: java.lang.NoClassDefFoundError: com/example/Plugin",
                "[Server thread/INFO]: Closing Server"
            )
        )
        assertEquals(ExitReason.STOPPED_BY_USER, result.reason)
        assertTrue(result.expected)
        assertEquals("Stopped by user", result.summary)
    }

    @Test
    fun `exit code zero without stop flag is normal even with stale warnings`() {
        val result = ExitClassifier.classify(
            exitCode = 0,
            stopRequested = false,
            force = false,
            tail = listOf("java.lang.NoClassDefFoundError: SomethingOld")
        )
        assertEquals(ExitReason.STOPPED_BY_USER, result.reason)
        assertTrue(result.expected)
    }

    @Test
    fun `unknown crash keeps exit code in message`() {
        val result = ExitClassifier.classify(134, stopRequested = false, force = false, tail = emptyList())
        assertEquals(ExitReason.CRASH_UNKNOWN, result.reason)
        assertEquals(6, result.signal)
        assertTrue(result.summary.contains("134"))
    }

    @Test
    fun `observed signal is used when the tail is empty`() {
        val result = ExitClassifier.classify(
            exitCode = 1,
            stopRequested = false,
            force = false,
            tail = emptyList(),
            observedSignal = CrashSignal.MISSING_LIBRARY
        )
        assertEquals(ExitReason.CRASH_MISSING_LIBRARY, result.reason)
    }

    @Test
    fun `tail wins over observed signal`() {
        val result = ExitClassifier.classify(
            exitCode = 1,
            stopRequested = false,
            force = false,
            tail = listOf("java.lang.OutOfMemoryError: Java heap space"),
            observedSignal = CrashSignal.MISSING_LIBRARY
        )
        assertEquals(ExitReason.CRASH_OUT_OF_MEMORY, result.reason)
    }
}
