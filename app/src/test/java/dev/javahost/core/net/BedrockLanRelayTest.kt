package dev.javahost.core.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic integration tests for [BedrockLanRelay].
 *
 * Everything runs on UDP loopback: the relay binds a wildcard ephemeral port
 * (`listenPort = 0`) and a [MockUpstream] on `127.0.0.1` plays Geyser (answers
 * RakNet pings with a crafted pong and echoes sentinel datagrams). No external
 * network access, no Android types.
 */
class BedrockLanRelayTest {

    private val relays = mutableListOf<BedrockLanRelay>()
    private val resources = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() {
        relays.forEach { runCatching { it.stop() } }
        relays.clear()
        resources.forEach { runCatching { it.close() } }
        resources.clear()
    }

    // ------------------------------------------------------------------
    // Ping -> rewritten pong
    // ------------------------------------------------------------------

    @Test
    fun `ping is answered from the relay socket with the rewritten port and guid`() {
        val upstream = newMock()
        val relay = newRelay(upstreamPort = upstream.port)
        assertTrue(relay.start())
        val client = newClient()
        try {
            val pingTime = System.currentTimeMillis()
            val pingBytes = BedrockRakNet.buildPing(pingTime, 0x0102030405060708L)
            send(client, pingBytes, relay.boundPort)
            val response = receivePacket(client) ?: testFailure("the relay did not answer the ping")
            assertEquals(
                "the pong must come from the relay's listening port",
                relay.boundPort,
                response.port
            )
            val pong = BedrockRakNet.parsePong(bytesOf(response))
            assertNotNull("the answer is not an Unconnected Pong", pong)
            pong!!
            assertEquals("pingTime must be echoed", pingTime, pong.pingTime)
            assertNotEquals("Geyser's GUID must not leak", UPSTREAM_GUID, pong.serverGuid)

            val info = BedrockRakNet.parseServerId(pong.serverId)
            assertNotNull("the rewritten pong has no usable descriptor", info)
            info!!
            assertEquals("advertised IPv4 port must be the relay port", relay.boundPort, info.port4)
            assertEquals("advertised IPv6 port must be the relay port", relay.boundPort, info.port6)
            assertEquals("My Bedrock Server", info.motd)
            assertEquals(622, info.protocol)
            assertEquals("1.21.60", info.version)
            assertEquals(3, info.players)
            assertEquals(10, info.maxPlayers)
            assertEquals("Second line", info.subMotd)
            assertEquals("Survival", info.gamemode)
            assertTrue("extra descriptor fields must be preserved", pong.serverId.contains("extraFieldOne"))
            assertTrue("trailing semicolon must be preserved", pong.serverId.endsWith(";"))
            assertTrue("the ping must be relayed to Geyser", upstream.pingsSeen.get() >= 1)
            assertArrayEquals(
                "client ping bytes must reach Geyser unchanged",
                pingBytes,
                upstream.lastPingBytes
            )
            assertEquals(1L, relay.stats().pingsAnswered)

            // The relay GUID is stable for the lifetime of the relay instance.
            send(client, BedrockRakNet.buildPing(pingTime + 1, 0x0102030405060708L), relay.boundPort)
            val second = BedrockRakNet.parsePong(bytesOf(receivePacket(client) ?: testFailure("no second pong")))
            assertNotNull(second)
            assertEquals(pong.serverGuid, second!!.serverGuid)
        } finally {
            relay.stop()
        }
    }

    // ------------------------------------------------------------------
    // Gameplay datagrams
    // ------------------------------------------------------------------

    @Test
    fun `non ping datagrams are relayed both ways and counted in stats`() {
        val upstream = newMock()
        val relay = newRelay(upstreamPort = upstream.port)
        assertTrue(relay.start())
        val client = newClient()
        try {
            send(client, CLIENT_PREFIX.toByteArray(), relay.boundPort)
            val reply = receivePacket(client) ?: testFailure("the echoed datagram never came back")
            assertEquals("SERVER:CLIENT:", String(bytesOf(reply), Charsets.UTF_8))

            val stats = relay.stats()
            assertTrue(stats.running)
            assertTrue("client packets must be counted", stats.packetsFromClients >= 1L)
            assertTrue("server packets must be counted", stats.packetsFromServer >= 1L)
            assertEquals("no pong travelled over the relay", 0L, stats.pingsAnswered)
            assertNull("no pong was seen", stats.lastServerInfo)
            assertEquals(1, stats.clients)
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `two clients each get their own session and response`() {
        val upstream = newMock()
        val relay = newRelay(upstreamPort = upstream.port)
        assertTrue(relay.start())
        val first = newClient()
        val second = newClient()
        try {
            send(first, (CLIENT_PREFIX + "one").toByteArray(), relay.boundPort)
            send(second, (CLIENT_PREFIX + "two").toByteArray(), relay.boundPort)

            val firstReply = receivePacket(first) ?: testFailure("first client got no response")
            val secondReply = receivePacket(second) ?: testFailure("second client got no response")
            assertEquals("SERVER:CLIENT:one", String(bytesOf(firstReply), Charsets.UTF_8))
            assertEquals("SERVER:CLIENT:two", String(bytesOf(secondReply), Charsets.UTF_8))

            send(first, BedrockRakNet.buildPing(1L, 11L), relay.boundPort)
            send(second, BedrockRakNet.buildPing(2L, 22L), relay.boundPort)
            val firstPong = BedrockRakNet.parsePong(bytesOf(receivePacket(first) ?: testFailure("no pong for 1")))
            val secondPong = BedrockRakNet.parsePong(bytesOf(receivePacket(second) ?: testFailure("no pong for 2")))
            assertNotNull(firstPong)
            assertNotNull(secondPong)
            assertEquals(2, relay.stats().clients)
        } finally {
            relay.stop()
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `busy port makes start return false and report an error`() {
        val blocker = DatagramSocket(0)
        resources.add(blocker)
        val errors = mutableListOf<String>()
        val relay = newRelay(
            listenPort = blocker.localPort,
            onError = { message -> synchronized(errors) { errors.add(message) } }
        )

        assertFalse("binding a busy port must fail", relay.start())
        assertFalse(relay.isRunning())
        assertFalse(relay.stats().running)
        synchronized(errors) {
            assertTrue("the bind failure must be reported through onError", errors.isNotEmpty())
            assertTrue(errors.first().contains("19132") || errors.first().contains("UDP"))
        }
        relay.stop()
    }

    @Test
    fun `stop closes the listening socket and the relay stops answering`() {
        val upstream = newMock()
        val relay = newRelay(upstreamPort = upstream.port)
        assertTrue(relay.start())
        val relayPort = relay.boundPort
        val client = newClient()
        try {
            send(client, CLIENT_PREFIX.toByteArray(), relayPort)
            assertNotNull("relay is not relaying before stop", receivePacket(client))

            relay.stop()
            assertFalse(relay.isRunning())
            assertFalse(relay.stats().running)
            assertEquals(0, relay.stats().clients)

            send(client, BedrockRakNet.buildPing(3L, 4L), relayPort)
            assertNull("a stopped relay must not answer", receivePacket(client, timeoutMs = 700))

            relay.stop() // idempotent
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `start and stop are idempotent and the relay guid survives a restart`() {
        val upstream = newMock()
        val relay = newRelay(upstreamPort = upstream.port)
        val client = newClient()
        try {
            assertTrue(relay.start())
            assertTrue("starting twice is a no-op success", relay.start())
            val firstGuid = pingAndReadGuid(relay, client)

            relay.stop()
            assertFalse(relay.isRunning())
            relay.stop()

            assertTrue("a stopped relay can be restarted", relay.start())
            val secondGuid = pingAndReadGuid(relay, client)
            assertEquals("the relay guid is stable per instance", firstGuid, secondGuid)
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `listenPort zero picks a free port and an explicit port is honoured`() {
        val upstream = newMock()
        val ephemeral = newRelay(listenPort = 0, upstreamPort = upstream.port)
        val client = newClient()
        try {
            assertTrue(ephemeral.start())
            val chosen = ephemeral.boundPort
            assertTrue("the OS must assign a real port", chosen in 1..65535)
            pingAndReadGuid(ephemeral, client)
            ephemeral.stop()
        } finally {
            ephemeral.stop()
        }

        // Use an independently reserved port for the explicit case: rebinding the
        // port of a just-stopped relay is racy on Linux (a receive blocked in the
        // kernel can keep the closed socket bound for a few more milliseconds).
        val explicitPort = DatagramSocket(0).use { it.localPort }
        val fixed = newRelay(listenPort = explicitPort, upstreamPort = upstream.port)
        try {
            assertTrue("binding an explicit free port must work", fixed.start())
            assertEquals(explicitPort, fixed.boundPort)
            pingAndReadGuid(fixed, client)
        } finally {
            fixed.stop()
        }
    }

    @Test
    fun `idle sessions are closed`() {
        val upstream = newMock()
        val relay = newRelay(
            upstreamPort = upstream.port,
            idleTimeoutMs = 200L,
            beaconIntervalMs = 60_000L
        )
        assertTrue(relay.start())
        val client = newClient()
        try {
            pingAndReadGuid(relay, client)
            assertEquals(1, relay.stats().clients)
            awaitTrue(5_000L) { relay.stats().clients == 0 }
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `maxClients closes the oldest session`() {
        val upstream = newMock()
        val relay = newRelay(upstreamPort = upstream.port, maxClients = 1)
        val first = newClient()
        val second = newClient()
        try {
            assertTrue(relay.start())
            pingAndReadGuid(relay, first)
            assertEquals(1, relay.stats().clients)

            val pong = pingAndReadGuid(relay, second)
            assertEquals("the limit must hold", 1, relay.stats().clients)
            assertNotEquals(UPSTREAM_GUID, pong)
        } finally {
            relay.stop()
        }
    }

    // ------------------------------------------------------------------
    // Beacon
    // ------------------------------------------------------------------

    @Test
    fun `a zero beacon interval disables unsolicited pongs`() {
        val upstream = newMock()
        val providerCalls = AtomicInteger()
        val relay = newRelay(
            upstreamPort = upstream.port,
            broadcastProvider = {
                providerCalls.incrementAndGet()
                listOf("255.255.255.255")
            },
            beaconIntervalMs = 0L
        )
        assertTrue(relay.start())
        val client = newClient()
        try {
            pingAndReadGuid(relay, client)
            Thread.sleep(400L)
            assertEquals("a <= 0 interval means reply-only mode", 0, providerCalls.get())
            assertTrue(relay.isRunning())
            // The relay keeps servicing pings without the beacon loop.
            pingAndReadGuid(relay, client)
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `beacon arms after the first pong`() {
        val upstream = newMock()
        val providerCalls = AtomicInteger()
        val relay = newRelay(
            upstreamPort = upstream.port,
            broadcastProvider = {
                providerCalls.incrementAndGet()
                emptyList()
            },
            beaconIntervalMs = 100L
        )
        assertTrue(relay.start())
        val client = newClient()
        try {
            Thread.sleep(300L)
            assertEquals("no pong is cached yet", 0, providerCalls.get())

            pingAndReadGuid(relay, client)
            awaitTrue(3_000L) { providerCalls.get() > 0 }
            assertTrue(relay.isRunning())
        } finally {
            relay.stop()
        }
    }

    @Test
    fun `a throwing broadcast provider does not stop the relay`() {
        val upstream = newMock()
        val relay = newRelay(
            upstreamPort = upstream.port,
            broadcastProvider = { throw IllegalStateException("broadcast lookup broke") },
            beaconIntervalMs = 100L
        )
        assertTrue(relay.start())
        val client = newClient()
        try {
            pingAndReadGuid(relay, client)
            Thread.sleep(400L)
            assertTrue("beacon failures must be contained", relay.isRunning())
            pingAndReadGuid(relay, client)
        } finally {
            relay.stop()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newRelay(
        listenPort: Int = 0,
        upstreamPort: Int = 19133,
        broadcastProvider: () -> List<String> = { emptyList() },
        beaconIntervalMs: Long = 60_000L,
        idleTimeoutMs: Long = 60_000L,
        maxClients: Int = 32,
        onError: ((String) -> Unit)? = null
    ): BedrockLanRelay {
        val relay = BedrockLanRelay(
            listenPort = listenPort,
            upstreamHost = "127.0.0.1",
            upstreamPort = upstreamPort,
            broadcastProvider = broadcastProvider,
            beaconIntervalMs = beaconIntervalMs,
            idleTimeoutMs = idleTimeoutMs,
            maxClients = maxClients,
            onError = onError
        )
        relays.add(relay)
        return relay
    }

    private fun newMock(): MockUpstream {
        val mock = MockUpstream()
        resources.add(mock)
        return mock
    }

    private fun newClient(): DatagramSocket {
        val client = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        resources.add(client)
        return client
    }

    private fun send(client: DatagramSocket, payload: ByteArray, port: Int) {
        client.send(DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), port))
    }

    private fun receivePacket(socket: DatagramSocket, timeoutMs: Int = 3_000): DatagramPacket? {
        socket.soTimeout = timeoutMs
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    private fun bytesOf(packet: DatagramPacket): ByteArray =
        packet.data.copyOfRange(packet.offset, packet.offset + packet.length)

    private fun pingAndReadGuid(relay: BedrockLanRelay, client: DatagramSocket): Long {
        val pingTime = System.currentTimeMillis()
        send(client, BedrockRakNet.buildPing(pingTime, 0x0a0b0c0d0e0f1011L), relay.boundPort)
        val packet = receivePacket(client) ?: testFailure("the relay did not answer the ping")
        assertEquals(relay.boundPort, packet.port)
        val pong = BedrockRakNet.parsePong(bytesOf(packet))
        assertNotNull("the answer is not a valid pong", pong)
        val info = BedrockRakNet.parseServerId(pong!!.serverId)
        assertNotNull(info)
        assertEquals(relay.boundPort, info!!.port4)
        return pong.serverGuid
    }

    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25L)
        }
        assertTrue("condition not met within $timeoutMs ms", condition())
    }

    /**
     * Loopback stand-in for Geyser: answers Unconnected Pings with a crafted
     * pong whose descriptor advertises 19132/19133 and extra trailing fields,
     * and echoes `CLIENT:*` sentinels back to their sender.
     */
    private class MockUpstream : AutoCloseable {

        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        private val receiveThread = Thread({ receiveLoop() }, "mock-geyser").apply {
            isDaemon = true
            start()
        }

        val port: Int get() = socket.localPort
        val pingsSeen = AtomicInteger()

        @Volatile
        var lastPingBytes: ByteArray? = null

        private fun receiveLoop() {
            val buffer = ByteArray(MAX_DATAGRAM_BYTES)
            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = packet.data.copyOfRange(
                        packet.offset,
                        packet.offset + packet.length
                    )
                    val reply = responseFor(data) ?: continue
                    socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                } catch (e: SocketException) {
                    return // closed by close()
                } catch (e: SocketTimeoutException) {
                    // No timeout is configured; nothing to do.
                } catch (t: Throwable) {
                    if (!running.get()) return
                }
            }
        }

        private fun responseFor(data: ByteArray): ByteArray? {
            if (BedrockRakNet.isUnconnectedPing(data)) {
                pingsSeen.incrementAndGet()
                lastPingBytes = data
                return BedrockRakNet.buildPong(
                    pingTime = readLongBE(data, 1),
                    serverGuid = UPSTREAM_GUID,
                    serverId = UPSTREAM_DESCRIPTOR
                )
            }
            val text = String(data, Charsets.UTF_8)
            if (!text.startsWith(CLIENT_PREFIX)) return null
            return (SERVER_PREFIX + text).toByteArray()
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            runCatching { receiveThread.join(2_000L) }
        }
    }
}

private const val MAX_DATAGRAM_BYTES = 64 * 1024
private const val CLIENT_PREFIX = "CLIENT:"
private const val SERVER_PREFIX = "SERVER:"
private const val UPSTREAM_GUID = 0x1111222233334444L
private const val UPSTREAM_DESCRIPTOR =
    "MCPE;My Bedrock Server;622;1.21.60;3;10;123456789012345;" +
        "Second line;Survival;0;19132;19133;extraFieldOne;extraFieldTwo;"

private fun testFailure(message: String): Nothing = throw AssertionError(message)

private fun readLongBE(source: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (source[offset + index].toLong() and 0xFF)
    }
    return value
}
