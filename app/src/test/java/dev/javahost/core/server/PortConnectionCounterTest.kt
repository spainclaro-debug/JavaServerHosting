package dev.javahost.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

class PortConnectionCounterTest {

    private val header =
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    @Test
    fun `counts only established connections on the requested port`() {
        val lines = listOf(
            header,
            // local port 8080 = 1F90, state 01 = ESTABLISHED
            "   0: 0100007F:1F90 0100007F:C350 01 00000000:00000000 00:00000000 00000000  1000        0 1 1 0000000000000000 20 4 30 10 -1",
            "   1: 0100007F:1F90 0100007F:C351 01 00000000:00000000 00:00000000 00000000  1000        0 1 1 0000000000000000 20 4 30 10 -1",
            // TIME_WAIT must not count
            "   2: 0100007F:1F90 0100007F:C352 06 00000000:00000000 00:00000000 00000000  1000        0 1 1 0000000000000000 20 4 30 10 -1",
            // different local port (25565 = 63DD)
            "   3: 0100007F:63DD 0100007F:C353 01 00000000:00000000 00:00000000 00000000  1000        0 1 1 0000000000000000 20 4 30 10 -1"
        )
        assertEquals(2, PortConnectionCounter.countLines(lines, 8080))
        assertEquals(1, PortConnectionCounter.countLines(lines, 25565))
        assertEquals(0, PortConnectionCounter.countLines(lines, 8081))
    }

    @Test
    fun `handles ipv6 hex local addresses`() {
        val lines = listOf(
            header,
            "   0: 00000000000000000000000001000000:1F90 00000000000000000000000001000000:C350 01 00000000:00000000 00:00000000 00000000 1000 0 1 1"
        )
        assertEquals(1, PortConnectionCounter.countLines(lines, 8080))
    }

    @Test
    fun `invalid ports are ignored`() {
        assertEquals(0, PortConnectionCounter.countLines(listOf(header), 0))
        assertEquals(0, PortConnectionCounter.countLines(listOf(header), 70000))
    }
}
