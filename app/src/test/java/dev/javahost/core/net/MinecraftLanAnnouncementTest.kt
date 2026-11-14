package dev.javahost.core.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Minecraft LAN discovery payload. The protocol string
 * is frozen: Minecraft clients parse exactly
 * `[MOTD]<motd>[/MOTD][AD]<port>[/AD]`, and a user-controlled MOTD must never
 * be able to inject extra tags or line breaks into the packet.
 */
class MinecraftLanAnnouncementTest {

    // ------------------------------------------------------------------
    // Constants and protocol framing
    // ------------------------------------------------------------------

    @Test
    fun `protocol constants match the minecraft lan discovery contract`() {
        assertEquals("224.0.2.60", MinecraftLanAnnouncement.MULTICAST_GROUP)
        assertEquals(4445, MinecraftLanAnnouncement.DEFAULT_PORT)
        assertEquals(1500L, MinecraftLanAnnouncement.INTERVAL_MS)
    }

    @Test
    fun `message uses the exact minecraft protocol framing`() {
        assertEquals(
            "[MOTD]Refuge[/MOTD][AD]25565[/AD]",
            MinecraftLanAnnouncement.message("Refuge", 25565)
        )
    }

    @Test
    fun `message includes the requested port and accepts boundary ports`() {
        assertEquals(
            "[MOTD]Server[/MOTD][AD]1[/AD]",
            MinecraftLanAnnouncement.message("Server", 1)
        )
        assertEquals(
            "[MOTD]Server[/MOTD][AD]65535[/AD]",
            MinecraftLanAnnouncement.message("Server", 65535)
        )
        assertEquals(
            "[MOTD]Server[/MOTD][AD]12345[/AD]",
            MinecraftLanAnnouncement.message("Server", 12345)
        )
    }

    @Test
    fun `message rejects out of range ports`() {
        assertThrows(IllegalArgumentException::class.java) {
            MinecraftLanAnnouncement.message("Server", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinecraftLanAnnouncement.message("Server", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinecraftLanAnnouncement.message("Server", 65536)
        }
    }

    // ------------------------------------------------------------------
    // Payload
    // ------------------------------------------------------------------

    @Test
    fun `payload is the utf8 encoding of the message`() {
        val expected = "[MOTD]Refuge[/MOTD][AD]25565[/AD]"
        val payload = MinecraftLanAnnouncement.payload("Refuge", 25565)
        assertEquals(expected, String(payload, Charsets.UTF_8))
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), payload)
    }

    @Test
    fun `payload encodes non ascii motd as multi byte utf8`() {
        val payload = MinecraftLanAnnouncement.payload("Café ☕", 25565)
        val decoded = String(payload, Charsets.UTF_8)
        assertEquals(MinecraftLanAnnouncement.message("Café ☕", 25565), decoded)
        assertTrue("UTF-8 bytes must be longer than the character count", payload.size > decoded.length)
    }

    @Test
    fun `payload sanitises the motd`() {
        val payload = MinecraftLanAnnouncement.payload("Evil[/MOTD][AD]1[/AD]", 25565)
        val decoded = String(payload, Charsets.UTF_8)
        assertEquals(1, Regex("\\[/MOTD\\]").findAll(decoded).count())
        assertEquals(1, Regex("\\[AD\\]").findAll(decoded).count())
        assertTrue(decoded.startsWith("[MOTD]Evil(/MOTD)(AD)1(/AD)[/MOTD]"))
    }

    @Test
    fun `payload matches message applied to the sanitised motd`() {
        val raw = "bad\nmotd"
        val sanitised = MinecraftLanAnnouncement.sanitizeMotd(raw, "Minecraft server")
        assertArrayEquals(
            MinecraftLanAnnouncement.payload(sanitised, 4445),
            MinecraftLanAnnouncement.payload(raw, 4445)
        )
    }

    @Test
    fun `payload rejects out of range ports`() {
        assertThrows(IllegalArgumentException::class.java) {
            MinecraftLanAnnouncement.payload("Server", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MinecraftLanAnnouncement.payload("Server", 70000)
        }
    }

    // ------------------------------------------------------------------
    // MOTD sanitisation
    // ------------------------------------------------------------------

    @Test
    fun `plain motd is returned unchanged`() {
        assertEquals("Refuge", MinecraftLanAnnouncement.sanitizeMotd("Refuge", "Fallback"))
        assertEquals("My Server", MinecraftLanAnnouncement.sanitizeMotd("My Server", "Fallback"))
    }

    @Test
    fun `blank motd falls back and blank fallback becomes the default`() {
        assertEquals("Fallback", MinecraftLanAnnouncement.sanitizeMotd(null, "Fallback"))
        assertEquals("Fallback", MinecraftLanAnnouncement.sanitizeMotd("", "Fallback"))
        assertEquals("Fallback", MinecraftLanAnnouncement.sanitizeMotd("   ", "Fallback"))
        assertEquals("Fallback", MinecraftLanAnnouncement.sanitizeMotd("\r\n\t", "Fallback"))
        assertEquals("Minecraft server", MinecraftLanAnnouncement.sanitizeMotd(null, ""))
        assertEquals("Minecraft server", MinecraftLanAnnouncement.sanitizeMotd("   ", "   "))
    }

    @Test
    fun `protocol brackets in a motd are neutralised`() {
        assertEquals(
            "Hello(/MOTD)(AD)1(/AD)",
            MinecraftLanAnnouncement.sanitizeMotd("Hello[/MOTD][AD]1[/AD]", "Fallback")
        )
        assertEquals("(just brackets)", MinecraftLanAnnouncement.sanitizeMotd("[just brackets]", "Fallback"))
        assertEquals("()", MinecraftLanAnnouncement.sanitizeMotd("[]", "Fallback"))
    }

    @Test
    fun `injected protocol tags never produce a second closing tag in the message`() {
        val message = MinecraftLanAnnouncement.message("Evil[/MOTD][AD]25565[/AD]", 25565)
        assertEquals(1, Regex("\\[/MOTD\\]").findAll(message).count())
        assertEquals(1, Regex("\\[AD\\]").findAll(message).count())
        assertEquals("[MOTD]Evil(/MOTD)(AD)25565(/AD)[/MOTD][AD]25565[/AD]", message)
    }

    @Test
    fun `line breaks tabs and control characters become single spaces`() {
        assertEquals(
            "line1 line2 line3",
            MinecraftLanAnnouncement.sanitizeMotd("line1\r\nline2\tline3", "Fallback")
        )
        assertEquals("a b c", MinecraftLanAnnouncement.sanitizeMotd("a\u0000b\u0007c", "Fallback"))
        assertEquals("a b", MinecraftLanAnnouncement.sanitizeMotd("a\u000Bb", "Fallback"))
    }

    @Test
    fun `whitespace is collapsed and trimmed`() {
        assertEquals("hello world", MinecraftLanAnnouncement.sanitizeMotd("  hello   world  ", "Fallback"))
        assertEquals("hello world", MinecraftLanAnnouncement.sanitizeMotd("\n\nhello\t\tworld\n", "Fallback"))
        assertEquals("hello", MinecraftLanAnnouncement.sanitizeMotd("hello", "Fallback"))
    }

    @Test
    fun `motd is capped at one hundred characters`() {
        val long = "a".repeat(150)
        val sanitised = MinecraftLanAnnouncement.sanitizeMotd(long, "Fallback")
        assertEquals(100, sanitised.length)
        assertEquals("a".repeat(100), sanitised)
        // A 100-character MOTD is not truncated.
        val exact = "b".repeat(100)
        assertEquals(exact, MinecraftLanAnnouncement.sanitizeMotd(exact, "Fallback"))
    }

    @Test
    fun `truncation never leaves a trailing space`() {
        // Collapses to 101 characters; the 100-character cut lands on the single
        // space, which must then be trimmed away.
        val raw = "a".repeat(99) + " " + "b"
        val sanitised = MinecraftLanAnnouncement.sanitizeMotd(raw, "Fallback")
        assertEquals(99, sanitised.length)
        assertEquals("a".repeat(99), sanitised)
    }

    @Test
    fun `fallback is sanitised too`() {
        assertEquals(
            "bad)fallback",
            MinecraftLanAnnouncement.sanitizeMotd(null, "bad]fallback")
        )
        assertEquals(
            "a b",
            MinecraftLanAnnouncement.sanitizeMotd("   ", "a\nb")
        )
        assertEquals(
            100,
            MinecraftLanAnnouncement.sanitizeMotd(null, "f".repeat(150)).length
        )
    }

    @Test
    fun `message sanitises a raw motd before framing`() {
        assertEquals(
            "[MOTD]hello world[/MOTD][AD]25565[/AD]",
            MinecraftLanAnnouncement.message("hello\nworld", 25565)
        )
        assertEquals(
            "[MOTD]Minecraft server[/MOTD][AD]25565[/AD]",
            MinecraftLanAnnouncement.message("   ", 25565)
        )
    }

    @Test
    fun `message keeps a long motd bounded so the packet stays small`() {
        val message = MinecraftLanAnnouncement.message("x".repeat(10_000), 25565)
        assertEquals("[MOTD]${"x".repeat(100)}[/MOTD][AD]25565[/AD]", message)
    }

    @Test
    fun `sanitised motd is always non blank for hostile input`() {
        val hostile = listOf(null, "", " ", "\r\n", "[", "]", "[]", "\u0000", "\t\t", "[/MOTD]")
        for (raw in hostile) {
            val sanitised = MinecraftLanAnnouncement.sanitizeMotd(raw, "")
            assertTrue("sanitised MOTD for $raw must not be blank", sanitised.isNotBlank())
        }
    }

    @Test
    fun `sanitizeMotd is idempotent`() {
        val inputs = listOf("Refuge", "  a   b  ", "Evil[/MOTD]", "x".repeat(150), "Café ☕")
        for (input in inputs) {
            val once = MinecraftLanAnnouncement.sanitizeMotd(input, "Fallback")
            val twice = MinecraftLanAnnouncement.sanitizeMotd(once, "Fallback")
            assertEquals("sanitising $input twice must be a no-op", once, twice)
        }
    }
}
