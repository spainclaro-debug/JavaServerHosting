package dev.javahost.core.server

import dev.javahost.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleParserTest {

    @Test
    fun `stripAnsi removes csi and osc sequences`() {
        assertEquals("ERROR boom", ConsoleParser.stripAnsi("\u001B[31mERROR\u001B[0m boom"))
        assertEquals("hello", ConsoleParser.stripAnsi("\u001B]0;window title\u0007hello"))
        assertEquals("plain line", ConsoleParser.stripAnsi("plain line"))
    }

    @Test
    fun `classify reads bracketed levels`() {
        assertEquals(
            LogLevel.INFO,
            ConsoleParser.classify("[12:00:00] [Server thread/INFO]: Starting minecraft server version 1.20.4")
        )
        assertEquals(
            LogLevel.WARN,
            ConsoleParser.classify("[12:00:00] [Server thread/WARN]: Can't keep up! Is the server overloaded?")
        )
        assertEquals(
            LogLevel.ERROR,
            ConsoleParser.classify("[12:00:00] [Server thread/ERROR]: Encountered an unexpected exception")
        )
        assertEquals(
            LogLevel.DEBUG,
            ConsoleParser.classify("[main/DEBUG]: Debugging something")
        )
        assertEquals(
            LogLevel.TRACE,
            ConsoleParser.classify("[main/TRACE]: trace me")
        )
    }

    @Test
    fun `classify handles plain prefixes and exceptions`() {
        assertEquals(LogLevel.WARN, ConsoleParser.classify("WARNING: deprecated option"))
        assertEquals(LogLevel.ERROR, ConsoleParser.classify("ERROR: could not load config"))
        assertEquals(LogLevel.ERROR, ConsoleParser.classify("java.lang.OutOfMemoryError: Java heap space"))
        assertEquals(LogLevel.ERROR, ConsoleParser.classify("Exception in thread \"main\" java.io.IOException"))
        assertEquals(LogLevel.INFO, ConsoleParser.classify("ordinary output line"))
        assertEquals(LogLevel.UNKNOWN, ConsoleParser.classify("   "))
    }

    @Test
    fun `ready detection`() {
        assertTrue(
            ConsoleParser.isReady(
                "[12:00:00] [Server thread/INFO]: Done (36.123s)! For help, type \"help\""
            )
        )
        assertTrue(ConsoleParser.isReady("Listening on port 8080"))
        assertTrue(ConsoleParser.isReady("Server started on port 25565"))
        assertFalse(
            ConsoleParser.isReady(
                "[12:00:00] [Server thread/INFO]: Starting minecraft server version 1.20.4"
            )
        )
        assertFalse(ConsoleParser.isReady(""))
    }

    @Test
    fun `eula prompt detection`() {
        assertTrue(
            ConsoleParser.isEulaPrompt(
                "[Server thread/INFO]: You need to agree to the EULA in order to run the server. " +
                    "Go to eula.txt for more info."
            )
        )
        assertTrue(ConsoleParser.isEulaPrompt("Failed to load eula.txt"))
        assertFalse(ConsoleParser.isEulaPrompt("Loaded properties from server.properties"))
    }

    @Test
    fun `player count parsing`() {
        assertEquals(PlayerCount(0, 20), ConsoleParser.parsePlayers("There are 0 of a max of 20 players online:"))
        assertEquals(PlayerCount(3, 10), ConsoleParser.parsePlayers("There are 3 of a maximum of 10 players online"))
        assertEquals(
            PlayerCount(1, 20),
            ConsoleParser.parsePlayers(
                "\u001B[32m[12:00:00] [Server thread/INFO]: There are 1 of a max of 20 players online\u001B[0m"
            )
        )
        assertNull(ConsoleParser.parsePlayers("Nobody is online"))
    }

    @Test
    fun `player event parsing`() {
        assertEquals(
            PlayerEvent("Steve", PlayerEventType.JOIN),
            ConsoleParser.parsePlayerEvent("[12:00:00] [Server thread/INFO]: Steve joined the game")
        )
        assertEquals(
            PlayerEvent("Alex", PlayerEventType.LEAVE),
            ConsoleParser.parsePlayerEvent("[12:00:00] [Server thread/INFO]: Alex left the game")
        )
        assertEquals(
            PlayerEvent("Herobrine", PlayerEventType.LEAVE),
            ConsoleParser.parsePlayerEvent("[12:00:00] [Server thread/INFO]: Herobrine lost connection: Disconnected")
        )
        assertNull(ConsoleParser.parsePlayerEvent("[12:00:00] [Server thread/INFO]: Preparing spawn area"))
    }

    @Test
    fun `port parsing`() {
        assertEquals(25565, ConsoleParser.parsePort("Starting Minecraft server on *:25565"))
        assertEquals(8080, ConsoleParser.parsePort("Listening on port 8080"))
        assertEquals(8081, ConsoleParser.parsePort("Listening on /127.0.0.1:8081"))
        assertEquals(9000, ConsoleParser.parsePort("Started on port 9000"))
        assertNull(ConsoleParser.parsePort("Preparing level \"world\""))
    }

    @Test
    fun `crash signature detection`() {
        assertEquals(
            CrashSignal.OUT_OF_MEMORY,
            ConsoleParser.crashSignal("[Server thread/ERROR]: java.lang.OutOfMemoryError: Java heap space")
        )
        assertEquals(
            CrashSignal.PORT_IN_USE,
            ConsoleParser.crashSignal("java.net.BindException: Address already in use")
        )
        assertEquals(
            CrashSignal.PORT_IN_USE,
            ConsoleParser.crashSignal("[Server thread/WARN]: **** FAILED TO BIND TO PORT!")
        )
        assertEquals(
            CrashSignal.UNSUPPORTED_JAVA,
            ConsoleParser.crashSignal("java.lang.UnsupportedClassVersionError: net/minecraft/server/Main")
        )
        assertEquals(
            CrashSignal.MISSING_LIBRARY,
            ConsoleParser.crashSignal("java.lang.NoClassDefFoundError: com/sun/jna/Native")
        )
        assertEquals(
            CrashSignal.EULA,
            ConsoleParser.crashSignal("You need to agree to the EULA in order to run the server.")
        )
        assertNull(ConsoleParser.crashSignal("Done (1.2s)! For help, type \"help\""))
    }
}
