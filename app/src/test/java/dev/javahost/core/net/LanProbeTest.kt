package dev.javahost.core.net

import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [SocketTcpProbe]. Every socket used here is a throwaway
 * one bound to the loopback interface on an ephemeral port, so the tests need
 * no network access and finish in milliseconds.
 */
class LanProbeTest {

    private val probe: TcpProbe = SocketTcpProbe()

    // ------------------------------------------------------------------
    // Outcome model
    // ------------------------------------------------------------------

    @Test
    fun `only reachable outcomes are ok`() {
        assertTrue(LanProbeOutcome(LanProbeResult.REACHABLE).ok)
        assertFalse(LanProbeOutcome(LanProbeResult.REFUSED).ok)
        assertFalse(LanProbeOutcome(LanProbeResult.TIMEOUT).ok)
        assertFalse(LanProbeOutcome(LanProbeResult.ERROR).ok)
    }

    @Test
    fun `outcome defaults are null`() {
        val outcome = LanProbeOutcome(LanProbeResult.REACHABLE)
        assertNull(outcome.latencyMs)
        assertNull(outcome.detail)
    }

    // ------------------------------------------------------------------
    // REACHABLE
    // ------------------------------------------------------------------

    @Test
    fun `listening loopback server is reachable with a latency`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val outcome = probe.probe("127.0.0.1", server.localPort, timeoutMs = 1000)
            assertEquals(LanProbeResult.REACHABLE, outcome.result)
            assertTrue(outcome.ok)
            assertNotNull("reachable probes must measure latency", outcome.latencyMs)
            assertTrue("latency must not be negative", (outcome.latencyMs ?: -1L) >= 0L)
        }
    }

    @Test
    fun `reachable probe uses the default timeout`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            // The default argument (1500 ms) must be usable from Kotlin and succeed locally.
            val outcome = probe.probe("127.0.0.1", server.localPort)
            assertEquals(LanProbeResult.REACHABLE, outcome.result)
        }
    }

    @Test
    fun `host with surrounding whitespace is trimmed and still probed`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val outcome = probe.probe("  127.0.0.1  ", server.localPort, timeoutMs = 1000)
            assertEquals(LanProbeResult.REACHABLE, outcome.result)
        }
    }

    // ------------------------------------------------------------------
    // REFUSED
    // ------------------------------------------------------------------

    @Test
    fun `closed loopback port is refused`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val closedPort = server.localPort
        server.close()
        val outcome = probe.probe("127.0.0.1", closedPort, timeoutMs = 1000)
        assertEquals(LanProbeResult.REFUSED, outcome.result)
        assertFalse(outcome.ok)
        assertNull(outcome.latencyMs)
    }

    // ------------------------------------------------------------------
    // ERROR: invalid input never throws
    // ------------------------------------------------------------------

    @Test
    fun `blank host is an error without touching the network`() {
        val blank = probe.probe("", 25565, timeoutMs = 200)
        assertEquals(LanProbeResult.ERROR, blank.result)
        assertFalse(blank.ok)
        assertNotNull(blank.detail)

        val whitespace = probe.probe("   ", 25565, timeoutMs = 200)
        assertEquals(LanProbeResult.ERROR, whitespace.result)
        assertNotNull(whitespace.detail)
    }

    @Test
    fun `out of range ports are errors without touching the network`() {
        for (port in listOf(0, -1, 65536, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val outcome = probe.probe("127.0.0.1", port, timeoutMs = 200)
            assertEquals("port $port must be rejected", LanProbeResult.ERROR, outcome.result)
            assertFalse(outcome.ok)
            assertNotNull(outcome.detail)
        }
    }

    @Test
    fun `invalid hostname is an error instead of an exception`() {
        val outcome = probe.probe("no such host exists", 25565, timeoutMs = 500)
        assertEquals(LanProbeResult.ERROR, outcome.result)
        assertFalse(outcome.ok)
        assertNotNull(outcome.detail)
    }

    @Test
    fun `zero or negative timeouts are clamped instead of blocking forever`() {
        // Socket.connect(0) means "wait forever"; the probe must clamp it so the
        // call returns promptly. A just-closed loopback port can legitimately
        // answer REFUSED or TIMEOUT at a 1 ms clamped budget, so assert the
        // property under test (no infinite block, never REACHABLE), not the race.
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val closedPort = server.localPort
        server.close()
        val startedNs = System.nanoTime()
        val outcome = probe.probe("127.0.0.1", closedPort, timeoutMs = 0)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        assertNotEquals(LanProbeResult.REACHABLE, outcome.result)
        assertTrue(
            "probe must not block with a clamped timeout (took ${elapsedMs}ms)",
            elapsedMs < 5_000L
        )
    }
}
