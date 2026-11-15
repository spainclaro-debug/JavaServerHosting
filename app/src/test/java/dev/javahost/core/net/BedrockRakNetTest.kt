package dev.javahost.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic tests for [BedrockRakNet]: packet layout, lenient parsing, descriptor
 * round-trips and the bridge's pong rewriting. No sockets and no Android types.
 */
class BedrockRakNetTest {

    // ------------------------------------------------------------------
    // Constants and MAGIC
    // ------------------------------------------------------------------

    @Test
    fun `raknet ids and bridge ports are the frozen values`() {
        assertEquals(0x01, BedrockRakNet.UNCONNECTED_PING_ID)
        assertEquals(0x02, BedrockRakNet.UNCONNECTED_PING_OPEN_CONNECTIONS_ID)
        assertEquals(0x1c, BedrockRakNet.UNCONNECTED_PONG_ID)
        assertEquals(19132, BedrockRakNet.BEDROCK_LAN_PORT)
        assertEquals(19133, BedrockRakNet.BEDROCK_GEYSER_PORT)
    }

    @Test
    fun `magic is the 16 byte offline message magic`() {
        val expected = byteArrayOf(
            0x00, 0xff.toByte(), 0xff.toByte(), 0x00,
            0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(),
            0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(),
            0x12, 0x34, 0x56, 0x78
        )
        assertEquals(16, BedrockRakNet.MAGIC.size)
        assertArrayEquals(expected, BedrockRakNet.MAGIC)
    }

    // ------------------------------------------------------------------
    // Ping
    // ------------------------------------------------------------------

    @Test
    fun `buildPing lays out id time magic and client guid big endian`() {
        val packet = BedrockRakNet.buildPing(0x0102030405060708L, 0x1112131415161718L)
        assertEquals(33, packet.size)
        assertEquals(0x01, packet[0].toInt() and 0xFF)
        assertEquals(0x0102030405060708L, readLongBE(packet, 1))
        assertArrayEquals(BedrockRakNet.MAGIC, packet.copyOfRange(9, 25))
        assertEquals(0x1112131415161718L, readLongBE(packet, 25))
    }

    @Test
    fun `isUnconnectedPing accepts both ping variants at the full size`() {
        assertTrue(BedrockRakNet.isUnconnectedPing(BedrockRakNet.buildPing(1L, 2L)))

        val openConnections = BedrockRakNet.buildPing(1L, 2L)
        openConnections[0] = 0x02
        assertTrue(BedrockRakNet.isUnconnectedPing(openConnections))
    }

    @Test
    fun `isUnconnectedPing rejects short wrong or empty datagrams`() {
        assertFalse(BedrockRakNet.isUnconnectedPing(ByteArray(0)))
        assertFalse(BedrockRakNet.isUnconnectedPing(ByteArray(32)))

        val pong = BedrockRakNet.buildPong(1L, 2L, DESCRIPTOR)
        assertFalse("a pong is not a ping", BedrockRakNet.isUnconnectedPing(pong))

        val wrongId = BedrockRakNet.buildPing(1L, 2L)
        wrongId[0] = 0x84.toByte()
        assertFalse(BedrockRakNet.isUnconnectedPing(wrongId))
    }

    // ------------------------------------------------------------------
    // Pong codec
    // ------------------------------------------------------------------

    @Test
    fun `buildPong posts the header fields and length`() {
        val bytes = BedrockRakNet.buildPong(0x0102030405060708L, 0x1112131415161718L, DESCRIPTOR)
        assertEquals(0x1c, bytes[0].toInt() and 0xFF)
        assertEquals(0x0102030405060708L, readLongBE(bytes, 1))
        assertEquals(0x1112131415161718L, readLongBE(bytes, 9))
        assertArrayEquals(BedrockRakNet.MAGIC, bytes.copyOfRange(17, 33))
        val declaredLength = readUnsignedShortBE(bytes, 33)
        assertEquals(DESCRIPTOR.toByteArray(Charsets.UTF_8).size, declaredLength)
        assertEquals(35 + declaredLength, bytes.size)
        assertEquals(DESCRIPTOR, String(bytes, 35, declaredLength, Charsets.UTF_8))
    }

    @Test
    fun `parsePong round trips every field`() {
        val pong = BedrockRakNet.Pong(0x1000000000000001L, -2L, DESCRIPTOR)
        val parsed = BedrockRakNet.parsePong(pong.toBytes())
        assertNotNull(parsed)
        assertEquals(pong, parsed)
        assertArrayEquals(pong.toBytes(), parsed!!.toBytes())
    }

    @Test
    fun `parsePong handles UTF-8 descriptors and zero length pongs`() {
        val utf8 = "MCPE;日本語 ✓ €;622;1.21.60;0;10;42;Sub;Survival;0;19132;19133;"
        val parsedUtf8 = BedrockRakNet.parsePong(
            BedrockRakNet.buildPong(7L, 9L, utf8)
        )
        assertNotNull(parsedUtf8)
        assertEquals(utf8, parsedUtf8!!.serverId)

        val empty = BedrockRakNet.parsePong(BedrockRakNet.buildPong(7L, 9L, ""))
        assertNotNull(empty)
        assertEquals("", empty!!.serverId)
        assertEquals(35, BedrockRakNet.buildPong(7L, 9L, "").size)
    }

    @Test
    fun `parsePong rejects malformed packets`() {
        assertNull(BedrockRakNet.parsePong(ByteArray(0)))
        assertNull("header only but truncated", BedrockRakNet.parsePong(ByteArray(34)))
        assertNull("a ping is not a pong", BedrockRakNet.parsePong(BedrockRakNet.buildPing(1L, 2L)))

        val valid = BedrockRakNet.buildPong(1L, 2L, DESCRIPTOR)

        val wrongId = valid.copyOf()
        wrongId[0] = 0x01
        assertNull(BedrockRakNet.parsePong(wrongId))

        val badMagic = valid.copyOf()
        badMagic[17] = 0x7f
        assertNull(BedrockRakNet.parsePong(badMagic))

        val tooShortForLength = valid.copyOf(34)
        assertNull(BedrockRakNet.parsePong(tooShortForLength))

        val shorterThanDeclared = valid.copyOf(valid.size - 1)
        assertNull("declared length exceeds the payload", BedrockRakNet.parsePong(shorterThanDeclared))

        val longerThanDeclared = valid + byteArrayOf(0x41)
        assertNull("trailing bytes after the payload", BedrockRakNet.parsePong(longerThanDeclared))
    }

    @Test
    fun `buildPong truncates descriptors beyond the 16 bit length limit`() {
        val huge = "a".repeat(70_000)
        val bytes = BedrockRakNet.buildPong(1L, 2L, huge)
        assertEquals(35 + 0xFFFF, bytes.size)
        assertEquals(0xFFFF, readUnsignedShortBE(bytes, 33))
        val parsed = BedrockRakNet.parsePong(bytes)
        assertNotNull(parsed)
        assertEquals(0xFFFF, parsed!!.serverId.length)
    }

    // ------------------------------------------------------------------
    // Server id descriptor
    // ------------------------------------------------------------------

    @Test
    fun `parseServerId reads every standard field`() {
        val info = BedrockRakNet.parseServerId(DESCRIPTOR)
        assertNotNull(info)
        info!!
        assertEquals("MCPE", info.edition)
        assertEquals("My Bedrock Server", info.motd)
        assertEquals(622, info.protocol)
        assertEquals("1.21.60", info.version)
        assertEquals(3, info.players)
        assertEquals(10, info.maxPlayers)
        assertEquals(123456789012345L, info.serverGuid)
        assertEquals("Second line", info.subMotd)
        assertEquals("Survival", info.gamemode)
        assertFalse(info.nintendoLimited)
        assertEquals(19132, info.port4)
        assertEquals(19133, info.port6)
    }

    @Test
    fun `parseServerId accepts extra trailing fields and missing ports`() {
        val withExtras = BedrockRakNet.parseServerId(
            "MCPE;Motd;622;1.21.60;1;5;7;Sub;Creative;1;19132;19133;newField;anotherField;"
        )
        assertNotNull(withExtras)
        assertEquals(19132, withExtras!!.port4)
        assertEquals(19133, withExtras.port6)
        assertTrue(withExtras.nintendoLimited)

        val noPorts = BedrockRakNet.parseServerId("MCPE;Motd;622;1.21.60;1;5;7;Sub;Creative;0")
        assertNotNull(noPorts)
        assertNull(noPorts!!.port4)
        assertNull(noPorts.port6)

        val missingEverything = BedrockRakNet.parseServerId("MCPE;Motd;")
        assertNotNull(missingEverything)
        assertEquals(0, missingEverything!!.protocol)
        assertEquals("", missingEverything.version)
        assertEquals(0L, missingEverything.serverGuid)
        assertNull(missingEverything.port4)
        assertNull(missingEverything.port6)
    }

    @Test
    fun `parseServerId is null only for unusable descriptors`() {
        assertNull(BedrockRakNet.parseServerId(""))
        assertNull(BedrockRakNet.parseServerId("MCPE"))
        assertNull(BedrockRakNet.parseServerId(";Motd;622"))
        assertNull(BedrockRakNet.parseServerId("   ;Motd;"))
        assertNotNull(BedrockRakNet.parseServerId("MCPE;Motd"))
    }

    @Test
    fun `parseServerId parses unsigned guid fields and invalid values leniently`() {
        val unsigned = BedrockRakNet.parseServerId(
            "MCPE;Motd;622;1.21.60;0;10;18446744073709551615;Sub;Survival;0;19132;19133;"
        )
        assertNotNull(unsigned)
        assertEquals(-1L, unsigned!!.serverGuid)

        val garbage = BedrockRakNet.parseServerId(
            "MCPE;Motd;notANumber;1.21.60;x;y;notAGuid;Sub;Survival;nope;0;70000;"
        )
        assertNotNull(garbage)
        assertEquals(0, garbage!!.protocol)
        assertEquals(0, garbage.players)
        assertEquals(0, garbage.maxPlayers)
        assertEquals(0L, garbage.serverGuid)
        assertFalse(garbage.nintendoLimited)
        assertNull(garbage.port4)
        assertNull(garbage.port6)
    }

    @Test
    fun `buildServerId always terminates and round trips`() {
        val info = BedrockRakNet.ServerInfo(
            edition = "MCPE",
            motd = "My Bedrock Server",
            protocol = 622,
            version = "1.21.60",
            players = 3,
            maxPlayers = 10,
            serverGuid = 123456789012345L,
            subMotd = "Second line",
            gamemode = "Survival",
            nintendoLimited = false,
            port4 = 19132,
            port6 = 19133
        )
        val descriptor = BedrockRakNet.buildServerId(info)
        assertTrue(descriptor.endsWith(";"))
        assertEquals(info, BedrockRakNet.parseServerId(descriptor))
        assertEquals(12, descriptor.count { it == ';' })
    }

    @Test
    fun `buildServerId omits absent ports and keeps positions`() {
        val base = BedrockRakNet.ServerInfo(
            edition = "MCPE",
            motd = "Motd",
            protocol = 1,
            version = "1.0",
            players = 0,
            maxPlayers = 8,
            serverGuid = 5L,
            subMotd = "Sub",
            gamemode = "Survival",
            nintendoLimited = true,
            port4 = null,
            port6 = null
        )

        val noPorts = BedrockRakNet.buildServerId(base)
        assertTrue(noPorts.endsWith(";"))
        assertNull(BedrockRakNet.parseServerId(noPorts)!!.port4)
        assertNull(BedrockRakNet.parseServerId(noPorts)!!.port6)

        val ipv4Only = BedrockRakNet.buildServerId(base.copy(port4 = 19132))
        val ipv4Info = BedrockRakNet.parseServerId(ipv4Only)
        assertEquals(19132, ipv4Info!!.port4)
        assertNull(ipv4Info.port6)

        val ipv6Only = BedrockRakNet.buildServerId(base.copy(port6 = 19133))
        val ipv6Info = BedrockRakNet.parseServerId(ipv6Only)
        assertNull(ipv6Info!!.port4)
        assertEquals(19133, ipv6Info.port6)
    }

    @Test
    fun `buildServerId keeps a negative guid stable as its unsigned value`() {
        val info = BedrockRakNet.parseServerId(DESCRIPTOR)!!.copy(serverGuid = -1L)
        val descriptor = BedrockRakNet.buildServerId(info)
        assertTrue(descriptor.contains(";18446744073709551615;"))
        assertEquals(-1L, BedrockRakNet.parseServerId(descriptor)!!.serverGuid)
    }

    // ------------------------------------------------------------------
    // rewritePong
    // ------------------------------------------------------------------

    @Test
    fun `rewritePong advertises the relay port and guid and keeps every other field`() {
        val upstreamGuid = 0x1111222233334444L
        val relayGuid = 0x5555666677778888L
        val pong = BedrockRakNet.Pong(0x0a0b0c0d0e0f1011L, upstreamGuid, DESCRIPTOR)
        val rewrittenBytes = BedrockRakNet.rewritePong(pong, 25000, relayGuid)
        val rewritten = BedrockRakNet.parsePong(rewrittenBytes)
        assertNotNull(rewritten)
        rewritten!!
        assertEquals("pingTime is echoed", pong.pingTime, rewritten.pingTime)
        assertEquals("header guid is the relay guid", relayGuid, rewritten.serverGuid)

        val originalFields = DESCRIPTOR.split(';')
        val rewrittenFields = rewritten.serverId.split(';')
        assertEquals("field count must be preserved", originalFields.size, rewrittenFields.size)
        assertEquals("relay guid in field 6", relayGuid.toString(), rewrittenFields[6])
        assertEquals("port4", "25000", rewrittenFields[10])
        assertEquals("port6", "25000", rewrittenFields[11])
        for (index in originalFields.indices) {
            if (index == 6 || index == 10 || index == 11) continue
            assertEquals("field $index must be preserved", originalFields[index], rewrittenFields[index])
        }
        assertEquals("trailing semicolon", DESCRIPTOR.count { it == ';' }, rewritten.serverId.count { it == ';' })
        assertTrue(rewritten.serverId.contains("extraFieldOne"))
        assertTrue(rewritten.serverId.contains("extraFieldTwo"))
    }

    @Test
    fun `rewritePong keeps UTF-8 motd and extra fields intact`() {
        val descriptor = "MCPE;MötD 日本語;622;1.21.60;0;10;42;Sub;Survival;0;19132;19133;future1;future2;"
        val rewritten = BedrockRakNet.parsePong(
            BedrockRakNet.rewritePong(BedrockRakNet.Pong(1L, 2L, descriptor), 19132, 3L)
        )
        assertNotNull(rewritten)
        assertEquals("MötD 日本語", BedrockRakNet.parseServerId(rewritten!!.serverId)!!.motd)
        assertEquals(19132, BedrockRakNet.parseServerId(rewritten.serverId)!!.port4)
        assertTrue(rewritten.serverId.endsWith(";future1;future2;"))
    }

    @Test
    fun `rewritePong pads short descriptors with usable ports`() {
        val rewritten = BedrockRakNet.parsePong(
            BedrockRakNet.rewritePong(BedrockRakNet.Pong(5L, 6L, "MCPE;Tiny"), 19132, 7L)
        )
        assertNotNull(rewritten)
        val info = BedrockRakNet.parseServerId(rewritten!!.serverId)
        assertNotNull(info)
        assertEquals("Tiny", info!!.motd)
        assertEquals(7L, BedrockRakNet.parseServerId(rewritten.serverId)!!.serverGuid)
        assertEquals(19132, info.port4)
        assertEquals(19132, info.port6)
        assertTrue(rewritten.serverId.endsWith(";"))
    }

    @Test
    fun `rewritePong never throws and falls back to a header only pong`() {
        val relayGuid = 0x0102030405060708L
        for (badDescriptor in listOf("", "garbage", ";;;", "   ")) {
            val bytes = BedrockRakNet.rewritePong(
                BedrockRakNet.Pong(42L, 43L, badDescriptor),
                19132,
                relayGuid
            )
            val parsed = BedrockRakNet.parsePong(bytes)
            assertNotNull("descriptor '$badDescriptor' must still produce a packet", parsed)
            assertEquals(42L, parsed!!.pingTime)
            assertEquals(relayGuid, parsed.serverGuid)
            assertEquals("", parsed.serverId)
            assertEquals(35, bytes.size)
        }
    }

    @Test
    fun `rewritePong tolerates out of range advertised ports`() {
        val bytes = BedrockRakNet.rewritePong(
            BedrockRakNet.Pong(1L, 2L, DESCRIPTOR),
            advertisedPort = 0,
            serverGuid = 3L
        )
        val parsed = BedrockRakNet.parsePong(bytes)
        assertNotNull(parsed)
        val info = BedrockRakNet.parseServerId(parsed!!.serverId)!!
        assertNull("port 0 is not a usable advertised port", info.port4)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun readLongBE(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (source[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun readUnsignedShortBE(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

    private companion object {
        private const val DESCRIPTOR =
            "MCPE;My Bedrock Server;622;1.21.60;3;10;123456789012345;" +
                "Second line;Survival;0;19132;19133;extraFieldOne;extraFieldTwo;"
    }
}
