package dev.javahost.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Hermetic tests for the `server.properties` codec: comment/order preservation,
 * typed parsing, port validation and stable round-trips.
 */
class ServerPropertiesCodecTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parse reads key value pairs`() {
        val raw = """
            # Minecraft server properties
            motd=A Java server
            server-port=25565
            online-mode=true
        """.trimIndent() + "\n"
        val entries = ServerPropertiesCodec.parse(raw)
        assertEquals("A Java server", entries["motd"])
        assertEquals("25565", entries["server-port"])
        assertEquals("true", entries["online-mode"])
    }

    @Test
    fun `parse tolerates colon separators and comments`() {
        val entries = ServerPropertiesCodec.parse("#c\nenable-command-block:false\n")
        assertEquals("false", entries["enable-command-block"])
    }

    @Test
    fun `last duplicate key wins like java properties`() {
        val entries = ServerPropertiesCodec.parse("motd=first\nmotd=second\n")
        assertEquals("second", entries["motd"])
    }

    @Test
    fun `round trip preserves comments order and unknown lines`() {
        val raw = """
            # Header comment
            # another comment
            motd=Hello
            server-port=25565

            unknown-future-key=42
            online-mode=true
        """.trimIndent() + "\n"
        val formatted = ServerPropertiesCodec.format(ServerPropertiesCodec.parse(raw), raw)
        assertEquals(raw, formatted)
    }

    @Test
    fun `round trip is byte stable for unusual spacing`() {
        val raw = "  motd = Hello World  \n# keep me\n"
        assertEquals(raw, ServerPropertiesCodec.format(ServerPropertiesCodec.parse(raw), raw))
    }

    @Test
    fun `format updates a value in place and keeps surrounding comments`() {
        val raw = "# header\nmotd=Old\n# trailer\nonline-mode=true\n"
        val entries = ServerPropertiesCodec.parse(raw).toMutableMap()
        entries["motd"] = "New"
        entries["max-players"] = "20"
        val formatted = ServerPropertiesCodec.format(entries, raw)
        assertEquals(
            "# header\nmotd=New\n# trailer\nonline-mode=true\nmax-players=20\n",
            formatted
        )
    }

    @Test
    fun `format appends new keys in map order`() {
        val raw = "b=2\n"
        val entries = linkedMapOf("b" to "2", "a" to "1")
        assertEquals("b=2\na=1\n", ServerPropertiesCodec.format(entries, raw))
    }

    @Test
    fun `format drops keys removed from the map but keeps comments`() {
        val raw = "a=1\n# keep\nb=2\n"
        assertEquals("# keep\nb=3\n", ServerPropertiesCodec.format(mapOf("b" to "3"), raw))
    }

    @Test
    fun `format without previous text writes a canonical file`() {
        val entries = linkedMapOf("motd" to "Hi", "server-port" to "25565")
        assertEquals("motd=Hi\nserver-port=25565\n", ServerPropertiesCodec.format(entries))
    }

    @Test
    fun `empty and missing trailing newline round trip`() {
        assertEquals("", ServerPropertiesCodec.format(emptyMap(), ""))
        assertEquals("a=1", ServerPropertiesCodec.format(ServerPropertiesCodec.parse("a=1"), "a=1"))
        assertEquals("a=1\n", ServerPropertiesCodec.format(ServerPropertiesCodec.parse("a=1\n"), "a=1\n"))
    }

    @Test
    fun `BOM is stripped before parsing`() {
        val entries = ServerPropertiesCodec.parse("\uFEFFmotd=Hi\n")
        assertEquals("Hi", entries["motd"])
    }

    @Test
    fun `port validation accepts only 1 to 65535`() {
        assertEquals(25565, ServerPropertiesCodec.validatePort("25565"))
        assertEquals(1, ServerPropertiesCodec.validatePort("1"))
        assertEquals(65535, ServerPropertiesCodec.validatePort("65535"))
        assertEquals(25565, ServerPropertiesCodec.validatePort(" 25565 "))
        assertNull(ServerPropertiesCodec.validatePort("0"))
        assertNull(ServerPropertiesCodec.validatePort("65536"))
        assertNull(ServerPropertiesCodec.validatePort("-1"))
        assertNull(ServerPropertiesCodec.validatePort("abc"))
        assertNull(ServerPropertiesCodec.validatePort(""))
    }

    @Test
    fun `setPort validates and writes the value`() {
        val entries = linkedMapOf<String, String>()
        ServerPropertiesCodec.setPort(entries, 25566)
        assertEquals("25566", entries[ServerPropertiesCodec.SERVER_PORT])
        assertEquals(25566, ServerPropertiesCodec.port(entries))

        assertThrows(IllegalArgumentException::class.java) {
            ServerPropertiesCodec.setPort(entries, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerPropertiesCodec.setPort(entries, 65536)
        }
    }

    @Test
    fun `port getter rejects invalid stored values`() {
        assertNull(ServerPropertiesCodec.port(mapOf(ServerPropertiesCodec.SERVER_PORT to "70000")))
        assertNull(ServerPropertiesCodec.port(emptyMap()))
    }

    @Test
    fun `boolean parsing is case insensitive and strict`() {
        assertEquals(true, ServerPropertiesCodec.parseBoolean("true"))
        assertEquals(true, ServerPropertiesCodec.parseBoolean(" TRUE "))
        assertEquals(false, ServerPropertiesCodec.parseBoolean("False"))
        assertNull(ServerPropertiesCodec.parseBoolean("yes"))
        assertNull(ServerPropertiesCodec.parseBoolean(null))
        assertTrue(ServerPropertiesCodec.getBoolean(mapOf("pvp" to "false"), "pvp", default = true).not())
    }

    @Test
    fun `integer helpers`() {
        assertEquals(20, ServerPropertiesCodec.getInt(mapOf("max-players" to "20"), "max-players"))
        assertNull(ServerPropertiesCodec.getInt(mapOf("max-players" to "many"), "max-players"))
    }

    @Test
    fun `motd setter cannot inject line breaks`() {
        val entries = linkedMapOf<String, String>()
        ServerPropertiesCodec.setMotd(entries, "line one\nline two\r\nline three")
        assertEquals("line one line two  line three", entries[ServerPropertiesCodec.MOTD])
        // ... and formatting the map can never produce extra lines.
        val formatted = ServerPropertiesCodec.format(entries, "")
        assertEquals(1, formatted.trimEnd('\n').lines().size)
    }

    @Test
    fun `variant setters validate known enums`() {
        val entries = linkedMapOf<String, String>()
        ServerPropertiesCodec.setDifficulty(entries, "HARD")
        assertEquals("hard", entries[ServerPropertiesCodec.DIFFICULTY])
        ServerPropertiesCodec.setGamemode(entries, "Creative")
        assertEquals("creative", entries[ServerPropertiesCodec.GAMEMODE])
        assertEquals("hard", ServerPropertiesCodec.difficulty(entries))
        assertEquals("creative", ServerPropertiesCodec.gamemode(entries))
        assertThrows(IllegalArgumentException::class.java) {
            ServerPropertiesCodec.setDifficulty(entries, "impossible")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerPropertiesCodec.setGamemode(entries, "flying")
        }
    }

    @Test
    fun `level name cannot introduce path separators`() {
        val entries = linkedMapOf<String, String>()
        ServerPropertiesCodec.setLevelName(entries, "world/../escape")
        assertEquals("world_.._escape", entries[ServerPropertiesCodec.LEVEL_NAME])
        assertThrows(IllegalArgumentException::class.java) {
            ServerPropertiesCodec.setLevelName(entries, "   ")
        }
    }

    @Test
    fun `read write preserves comments on disk`() {
        val file: File = tempFolder.newFile("server.properties")
        val original = "# managed by hand\nmotd=Old\nserver-port=25565\n# tail\n"
        file.writeText(original)
        val entries = ServerPropertiesCodec.read(file).toMutableMap()
        ServerPropertiesCodec.setMotd(entries, "New")
        ServerPropertiesCodec.write(file, entries)
        assertEquals(
            "# managed by hand\nmotd=New\nserver-port=25565\n# tail\n",
            file.readText()
        )
    }

    @Test
    fun `read missing file returns empty map`() {
        val file = File(tempFolder.root, "does-not-exist.properties")
        assertTrue(ServerPropertiesCodec.read(file).isEmpty())
    }

    @Test
    fun `read rejects oversized files`() {
        val file: File = tempFolder.newFile("big.properties")
        file.writeText("motd=" + "x".repeat(2048) + "\n")
        assertThrows(java.io.IOException::class.java) {
            ServerPropertiesCodec.read(file, maxBytes = 128)
        }
    }
}
