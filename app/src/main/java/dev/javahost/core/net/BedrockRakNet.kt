package dev.javahost.core.net

/**
 * RakNet constants, ping/pong codecs and the MCPE server-id descriptor used by
 * the Bedrock Edition LAN bridge.
 *
 * A Bedrock client discovers LAN servers by broadcasting an *Unconnected Ping*
 * (id [UNCONNECTED_PING_ID] or [UNCONNECTED_PING_OPEN_CONNECTIONS_ID]) on UDP
 * 19132. Any host that answers with an *Unconnected Pong* (id
 * [UNCONNECTED_PONG_ID]) shows up in the client's Friends/LAN list, and the
 * client connects to the **source address of the pong** using the advertised
 * IPv4 port from the server-id descriptor. That is why the LAN bridge has to
 * own UDP 19132 and rewrite both the pong header (GUID) and the descriptor
 * ports before forwarding Geyser's answer.
 *
 * Everything here is pure JVM: byte arrays in, byte arrays out, no sockets, no
 * Android types, and the parsing functions never throw on malformed input.
 * Big-endian ("network order") is used for every integer field.
 *
 * @see <a href="https://wiki.vg/Raknet_Protocol">wiki.vg RakNet protocol</a>
 */
object BedrockRakNet {

    /** RakNet `ID_UNCONNECTED_PING`. */
    const val UNCONNECTED_PING_ID = 0x01

    /** RakNet `ID_UNCONNECTED_PING_OPEN_CONNECTIONS` (same shape as a normal ping). */
    const val UNCONNECTED_PING_OPEN_CONNECTIONS_ID = 0x02

    /** RakNet `ID_UNCONNECTED_PONG` answer to both ping variants. */
    const val UNCONNECTED_PONG_ID = 0x1c

    /** UDP port Bedrock clients broadcast their LAN discovery pings on. */
    const val BEDROCK_LAN_PORT = 19132

    /** UDP port Geyser is moved to while the bridge owns [BEDROCK_LAN_PORT]. */
    const val BEDROCK_GEYSER_PORT = 19133

    /**
     * The 16-byte RakNet "offline message magic": `00 ff ff 00 fe fe fe fe fd
     * fd fd fd 12 34 56 78`.
     *
     * Do not modify the returned array; treat it as immutable.
     */
    val MAGIC: ByteArray = byteArrayOf(
        0x00,
        0xff.toByte(),
        0xff.toByte(),
        0x00,
        0xfe.toByte(),
        0xfe.toByte(),
        0xfe.toByte(),
        0xfe.toByte(),
        0xfd.toByte(),
        0xfd.toByte(),
        0xfd.toByte(),
        0xfd.toByte(),
        0x12,
        0x34,
        0x56,
        0x78
    )

    /** 16-byte MAGIC length. */
    private const val MAGIC_SIZE = 16

    /** Byte offset of the MAGIC inside an Unconnected Ping. */
    private const val PING_MAGIC_OFFSET = 1 + 8

    /** Byte offset of the MAGIC inside an Unconnected Pong. */
    private const val PONG_MAGIC_OFFSET = 1 + 8 + 8

    /** id(1) + time(8) + MAGIC(16) + clientGuid(8). */
    private const val PING_SIZE = 1 + 8 + MAGIC_SIZE + 8

    /** id(1) + pingTime(8) + serverGuid(8) + MAGIC(16) + length(2). */
    private const val PONG_HEADER_SIZE = 1 + 8 + 8 + MAGIC_SIZE + 2

    /** Index of the server GUID field inside the MCPE server-id descriptor. */
    private const val GUID_FIELD_INDEX = 6

    /** Index of the advertised IPv4 port inside the MCPE server-id descriptor. */
    private const val PORT4_FIELD_INDEX = 10

    /** Index of the advertised IPv6 port inside the MCPE server-id descriptor. */
    private const val PORT6_FIELD_INDEX = 11

    /** Highest value representable by the descriptor's unsigned 16-bit length field. */
    private const val MAX_SERVER_ID_BYTES = 0xFFFF

    /**
     * True when [data] looks like an Unconnected Ping: id 0x01 or 0x02 and at
     * least the full 33-byte ping layout.
     */
    fun isUnconnectedPing(data: ByteArray): Boolean {
        if (data.size < PING_SIZE) return false
        return when (data[0].toInt() and 0xFF) {
            UNCONNECTED_PING_ID, UNCONNECTED_PING_OPEN_CONNECTIONS_ID -> true
            else -> false
        }
    }

    /**
     * Builds the canonical Unconnected Ping datagram:
     * `0x01 | time(8 BE) | MAGIC(16) | clientGuid(8)`.
     *
     * @param timeMillis discovery timestamp echoed back in the pong's pingTime.
     * @param clientGuid client identity; a Bedrock client usually derives it
     *   from the device, any value works for discovery.
     */
    fun buildPing(timeMillis: Long, clientGuid: Long): ByteArray {
        val packet = ByteArray(PING_SIZE)
        packet[0] = UNCONNECTED_PING_ID.toByte()
        writeLongBE(packet, 1, timeMillis)
        MAGIC.copyInto(packet, PING_MAGIC_OFFSET)
        writeLongBE(packet, PING_MAGIC_OFFSET + MAGIC_SIZE, clientGuid)
        return packet
    }

    /**
     * A decoded Unconnected Pong.
     *
     * @property pingTime timestamp copied from the ping this pong answers.
     * @property serverGuid 64-bit server identity advertised in the header.
     * @property serverId MCPE descriptor (see [parseServerId]).
     */
    data class Pong(
        val pingTime: Long,
        val serverGuid: Long,
        val serverId: String
    ) {
        /** Encodes this pong, keeping every field as-is. */
        fun toBytes(): ByteArray = buildPong(pingTime, serverGuid, serverId)
    }

    /**
     * Parses an Unconnected Pong, or returns null when [data] is not a valid
     * pong (wrong id, shorter than the 35-byte header, missing MAGIC, or a
     * declared descriptor length that does not match the payload size).
     */
    fun parsePong(data: ByteArray): Pong? {
        if (data.size < PONG_HEADER_SIZE) return null
        if ((data[0].toInt() and 0xFF) != UNCONNECTED_PONG_ID) return null
        if (!hasMagicAt(data, PONG_MAGIC_OFFSET)) return null
        val declaredLength = readUnsignedShortBE(data, PONG_MAGIC_OFFSET + MAGIC_SIZE)
        if (declaredLength != data.size - PONG_HEADER_SIZE) return null
        val serverId = String(data, PONG_HEADER_SIZE, declaredLength, Charsets.UTF_8)
        return Pong(
            pingTime = readLongBE(data, 1),
            serverGuid = readLongBE(data, 1 + 8),
            serverId = serverId
        )
    }

    /**
     * Decoded MCPE server-id descriptor.
     *
     * @property edition first field, normally `MCPE`.
     * @property motd user-visible server name (field 1).
     * @property protocol Bedrock protocol number; 0 when absent/invalid.
     * @property version human-readable Bedrock version, e.g. `1.21.60`.
     * @property players current player count; 0 when absent/invalid.
     * @property maxPlayers player cap; 0 when absent/invalid.
     * @property serverGuid 64-bit GUID field (field 6), 0 when absent/invalid.
     * @property subMotd secondary MOTD line (field 7).
     * @property gamemode gamemode name, e.g. `Survival` (field 8).
     * @property nintendoLimited whether field 9 is `1` (Nintendo limited mode).
     * @property port4 advertised IPv4 port (field 10), null when absent/invalid.
     * @property port6 advertised IPv6 port (field 11), null when absent/invalid.
     */
    data class ServerInfo(
        val edition: String,
        val motd: String,
        val protocol: Int,
        val version: String,
        val players: Int,
        val maxPlayers: Int,
        val serverGuid: Long,
        val subMotd: String,
        val gamemode: String,
        val nintendoLimited: Boolean,
        val port4: Int?,
        val port6: Int?
    )

    /**
     * Leniently parses an MCPE server-id descriptor:
     * `MCPE;<motd>;<protocol>;<version>;<players>;<max>;<guid>;<subMotd>;<gamemode>;<nintendoLimited>;<port4>;<port6>;[extra...];`
     *
     * Missing trailing fields (older/newer servers, absent ports) are tolerated
     * and defaulted, extra trailing fields are ignored here but preserved by
     * [rewritePong]. Returns null only when the string is unusable — fewer than
     * two fields or a blank edition.
     */
    fun parseServerId(serverId: String): ServerInfo? {
        val fields = serverId.split(';')
        if (fields.size < 2 || fields[0].isBlank()) return null
        return ServerInfo(
            edition = fields[0].trim(),
            motd = fields[1],
            protocol = fields.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
            version = fields.getOrNull(3)?.trim().orEmpty(),
            players = fields.getOrNull(4)?.trim()?.toIntOrNull() ?: 0,
            maxPlayers = fields.getOrNull(5)?.trim()?.toIntOrNull() ?: 0,
            serverGuid = parseGuidField(fields.getOrNull(6)),
            subMotd = fields.getOrNull(7).orEmpty(),
            gamemode = fields.getOrNull(8).orEmpty(),
            nintendoLimited = parseNintendoLimited(fields.getOrNull(9)),
            port4 = parsePortField(fields.getOrNull(10)),
            port6 = parsePortField(fields.getOrNull(11))
        )
    }

    /**
     * Builds an MCPE server-id descriptor from [info], always ending with `;`.
     * Ports are omitted (keeping the descriptor parseable) when null.
     */
    fun buildServerId(info: ServerInfo): String {
        val builder = StringBuilder()
        builder.append(info.edition).append(';')
        builder.append(info.motd).append(';')
        builder.append(info.protocol).append(';')
        builder.append(info.version).append(';')
        builder.append(info.players).append(';')
        builder.append(info.maxPlayers).append(';')
        builder.append(formatGuid(info.serverGuid)).append(';')
        builder.append(info.subMotd).append(';')
        builder.append(info.gamemode).append(';')
        builder.append(if (info.nintendoLimited) '1' else '0').append(';')
        if (info.port4 != null) {
            builder.append(info.port4).append(';')
        }
        if (info.port6 != null) {
            // Keep the positional field for port4 even when it is not advertised.
            if (info.port4 == null) builder.append(';')
            builder.append(info.port6).append(';')
        }
        return builder.toString()
    }

    /**
     * Rebuilds a pong for the bridge: same pingTime, [serverGuid] in the header
     * and in the descriptor's GUID field, and the descriptor's port4/port6
     * forced to [advertisedPort]. Every other descriptor field (including
     * unknown trailing fields added by newer Bedrock versions) and the trailing
     * semicolons are preserved byte-for-byte.
     *
     * Never throws: when the descriptor is unusable this returns the header-only
     * pong (valid packet with an empty server id).
     */
    fun rewritePong(pong: Pong, advertisedPort: Int, serverGuid: Long): ByteArray {
        return try {
            val serverId = rewriteServerId(pong.serverId, advertisedPort, serverGuid) ?: ""
            buildPong(pong.pingTime, serverGuid, serverId)
        } catch (t: Throwable) {
            try {
                buildPong(pong.pingTime, serverGuid, "")
            } catch (t2: Throwable) {
                ByteArray(0)
            }
        }
    }

    /**
     * Builds a complete pong packet: `0x1c | pingTime(8 BE) | serverGuid(8 BE) |
     * MAGIC(16) | len(2 BE) | serverId UTF-8`. The descriptor is encoded
     * verbatim (the caller pins the ports/GUID it needs); oversized descriptors
     * are truncated to the 65535-byte length-field limit instead of throwing.
     */
    fun buildPong(pingTime: Long, serverGuid: Long, serverId: String): ByteArray {
        var payload = serverId.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_SERVER_ID_BYTES) {
            payload = payload.copyOf(MAX_SERVER_ID_BYTES)
        }
        val packet = ByteArray(PONG_HEADER_SIZE + payload.size)
        packet[0] = UNCONNECTED_PONG_ID.toByte()
        writeLongBE(packet, 1, pingTime)
        writeLongBE(packet, 1 + 8, serverGuid)
        MAGIC.copyInto(packet, PONG_MAGIC_OFFSET)
        writeUnsignedShortBE(packet, PONG_MAGIC_OFFSET + MAGIC_SIZE, payload.size)
        payload.copyInto(packet, PONG_HEADER_SIZE)
        return packet
    }

    /* ------------------------------------------------------------------ */
    /* Internals                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Rewrites GUID/port fields of [serverId] by splicing the split descriptor,
     * which keeps every unknown field untouched. Null when the descriptor is
     * unusable (see [parseServerId]).
     */
    private fun rewriteServerId(serverId: String, advertisedPort: Int, serverGuid: Long): String? {
        val fields = serverId.split(';').toMutableList()
        if (fields.size < 2 || fields[0].isBlank()) return null
        while (fields.size <= PORT6_FIELD_INDEX) fields.add("")
        fields[GUID_FIELD_INDEX] = formatGuid(serverGuid)
        fields[PORT4_FIELD_INDEX] = advertisedPort.toString()
        fields[PORT6_FIELD_INDEX] = advertisedPort.toString()
        val rebuilt = fields.joinToString(";")
        // Padding above can consume the original trailing empty field; descriptors
        // terminate every field with ';', so make sure the result does too.
        return if (rebuilt.endsWith(';')) rebuilt else "$rebuilt;"
    }

    /** Reads field 9 as the Nintendo limited flag (`1` or `true`). */
    private fun parseNintendoLimited(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        return text == "1" || text.equals("true", ignoreCase = true)
    }

    /** Parses a port field, returning null for absent or out-of-range values. */
    private fun parsePortField(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return text.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /**
     * Parses a 64-bit descriptor GUID. MCPE writes unsuffixed uint64 values, so
     * fall back to unsigned parsing before giving up with 0.
     */
    private fun parseGuidField(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        return try {
            java.lang.Long.parseLong(text)
        } catch (e: NumberFormatException) {
            try {
                java.lang.Long.parseUnsignedLong(text)
            } catch (e2: NumberFormatException) {
                0L
            }
        }
    }

    /** Formats a GUID the way Bedrock's uint64 descriptor field expects it. */
    private fun formatGuid(serverGuid: Long): String = java.lang.Long.toUnsignedString(serverGuid)

    /** True when MAGIC starts at [offset]; false when out of bounds or different. */
    private fun hasMagicAt(source: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + MAGIC_SIZE > source.size) return false
        for (index in 0 until MAGIC_SIZE) {
            if (source[offset + index] != MAGIC[index]) return false
        }
        return true
    }

    /** Writes [value] big-endian at [offset]. */
    private fun writeLongBE(target: ByteArray, offset: Int, value: Long) {
        var remaining = value
        for (index in 7 downTo 0) {
            target[offset + index] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
    }

    /** Reads a big-endian long at [offset]; caller guarantees 8 bytes exist. */
    private fun readLongBE(source: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (source[offset + index].toLong() and 0xFF)
        }
        return value
    }

    /** Writes the low 16 bits of [value] big-endian at [offset]. */
    private fun writeUnsignedShortBE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    /** Reads an unsigned big-endian 16-bit value at [offset]; caller guarantees 2 bytes. */
    private fun readUnsignedShortBE(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)
}
