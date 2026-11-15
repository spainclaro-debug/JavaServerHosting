package dev.javahost.core.net

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Hermetic tests for [BedrockGeyserConfig]: locating a Geyser plugin `config.yml`
 * under `plugins`, reading the modern `bedrock.port` / legacy `bedrock-port`
 * values and patching the port in place without touching any other byte.
 *
 * Every file lives in a JUnit [TemporaryFolder], so no test depends on the host
 * filesystem layout or on an actual Geyser installation.
 */
class BedrockGeyserConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /* ------------------------------------------------------------------ */
    /* Fixtures                                                           */
    /* ------------------------------------------------------------------ */

    private val modernConfig = """
        # Geyser configuration file written by Geyser-Spigot.
        bedrock:
          # The IP address to listen on.
          address: 0.0.0.0
          # The port to listen on (19132 is the vanilla Bedrock default).
          port: 19132
          # Also announce on the LAN with this port.
          broadcast-port: 19132
          motd1: JavaHost
          motd2: Bedrock
        remote:
          address: 127.0.0.1
          port: 25565
          auth-type: floodgate
        # Trailing comment kept verbatim.
    """.trimIndent() + "\n"

    private val legacyConfig = """
        # Legacy Geyser configuration.
        bedrock-port: 19132
        remote:
          address: 127.0.0.1
          port: 25565
    """.trimIndent() + "\n"

    private fun pluginsDir(): File = File(tempFolder.root, "plugins").apply { mkdirs() }

    private fun createConfig(
        dirName: String,
        content: String,
        plugins: File = pluginsDir()
    ): File {
        val dir = File(plugins, dirName).apply { mkdirs() }
        return File(dir, "config.yml").apply { writeText(content) }
    }

    private fun modernConfigFile(content: String = modernConfig): File =
        createConfig("Geyser-Spigot", content)

    /** Replaces the first occurrence of [oldValue] in [text], failing the test when absent. */
    private fun replaceOnce(text: String, oldValue: String, newValue: String): String {
        val index = text.indexOf(oldValue)
        assertTrue("fixture must contain \"$oldValue\"", index >= 0)
        return text.substring(0, index) + newValue + text.substring(index + oldValue.length)
    }

    /** Asserts [after] differs from [before] only inside one contiguous [oldValue] -> [newValue] run. */
    private fun assertOnlyPortDigitsChanged(
        before: String,
        after: String,
        oldValue: String,
        newValue: String
    ) {
        assertEquals("the digit count must not change", before.length, after.length)
        val diffs = before.indices.filter { before[it] != after[it] }
        assertFalse("the port must actually change", diffs.isEmpty())
        for (index in diffs) {
            assertTrue("byte $index must stay a digit in the old text", before[index].isDigit())
            assertTrue("byte $index must stay a digit in the new text", after[index].isDigit())
        }
        val firstDiff = diffs.first()
        val lastDiff = diffs.last()
        assertTrue(
            "the change must fit inside the port value",
            lastDiff - firstDiff + 1 <= oldValue.length
        )
        assertEquals(oldValue, before.substring(lastDiff + 1 - oldValue.length, lastDiff + 1))
        assertEquals(newValue, after.substring(lastDiff + 1 - newValue.length, lastDiff + 1))
        assertEquals(before.substring(0, firstDiff), after.substring(0, firstDiff))
        assertEquals(before.substring(lastDiff + 1), after.substring(lastDiff + 1))
    }

    /* ------------------------------------------------------------------ */
    /* findConfigFile                                                     */
    /* ------------------------------------------------------------------ */

    @Test
    fun `findConfigFile finds the geyser plugin config`() {
        val expected = modernConfigFile()
        assertEquals(expected, BedrockGeyserConfig.findConfigFile(pluginsDir()))
    }

    @Test
    fun `findConfigFile matches the directory name case-insensitively`() {
        val expected = createConfig("GEYSER-Spigot", modernConfig)
        assertEquals(expected, BedrockGeyserConfig.findConfigFile(pluginsDir()))
    }

    @Test
    fun `findConfigFile ignores non-geyser plugin directories`() {
        createConfig("EssentialsX", "motd: hi\n")
        createConfig("WorldEdit", "port: 1\n")
        assertNull(BedrockGeyserConfig.findConfigFile(pluginsDir()))
    }

    @Test
    fun `findConfigFile skips a geyser directory without a config file`() {
        pluginsDir().resolve("Geyser-Spigot").mkdirs()
        val expected = createConfig("Geyser-BungeeCord", legacyConfig)
        assertEquals(expected, BedrockGeyserConfig.findConfigFile(pluginsDir()))
    }

    @Test
    fun `findConfigFile picks the first geyser directory in name order`() {
        createConfig("Geyser-Standalone", legacyConfig)
        val spigot = createConfig("Geyser-Spigot", modernConfig)
        assertEquals(spigot, BedrockGeyserConfig.findConfigFile(pluginsDir()))
    }

    @Test
    fun `findConfigFile ignores plain files named like geyser plugins`() {
        File(pluginsDir(), "Geyser-Spigot").writeText("not a directory")
        assertNull(BedrockGeyserConfig.findConfigFile(pluginsDir()))
    }

    @Test
    fun `findConfigFile returns null for a missing plugins directory`() {
        assertNull(BedrockGeyserConfig.findConfigFile(File(tempFolder.root, "does-not-exist")))
    }

    @Test
    fun `findConfigFile returns null when config yml is a directory`() {
        val geyserDir = File(pluginsDir(), "Geyser-Spigot").apply { mkdirs() }
        File(geyserDir, "config.yml").mkdirs()
        assertNull(BedrockGeyserConfig.findConfigFile(pluginsDir()))
    }

    /* ------------------------------------------------------------------ */
    /* readBedrockPort                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    fun `readBedrockPort reads the modern bedrock block`() {
        assertEquals(19132, BedrockGeyserConfig.readBedrockPort(modernConfigFile()))
    }

    @Test
    fun `readBedrockPort does not read a later remote block port`() {
        val file = modernConfigFile()
        // The fixture's remote.port is 25565; only bedrock.port may be returned.
        assertEquals(19132, BedrockGeyserConfig.readBedrockPort(file))
    }

    @Test
    fun `readBedrockPort reads the legacy top level key`() {
        assertEquals(19132, BedrockGeyserConfig.readBedrockPort(createConfig("Geyser-Spigot", legacyConfig)))
    }

    @Test
    fun `readBedrockPort handles CRLF files`() {
        val crlf = modernConfig.replace("\n", "\r\n")
        assertEquals(19132, BedrockGeyserConfig.readBedrockPort(modernConfigFile(crlf)))
    }

    @Test
    fun `readBedrockPort handles deeper indentation`() {
        val content = "bedrock:\n    address: 0.0.0.0\n    port: 20000\n"
        assertEquals(20000, BedrockGeyserConfig.readBedrockPort(modernConfigFile(content)))
    }

    @Test
    fun `readBedrockPort falls back to the legacy key when the modern block has no port`() {
        val content = "bedrock:\n  address: 0.0.0.0\nbedrock-port: 19132\n"
        assertEquals(19132, BedrockGeyserConfig.readBedrockPort(modernConfigFile(content)))
    }

    @Test
    fun `readBedrockPort prefers the modern block over a legacy key`() {
        val content = "bedrock-port: 19000\nbedrock:\n  address: 0.0.0.0\n  port: 19132\n"
        assertEquals(19132, BedrockGeyserConfig.readBedrockPort(modernConfigFile(content)))
    }

    @Test
    fun `readBedrockPort returns null without a bedrock section`() {
        val file = modernConfigFile("motd: hi\nremote:\n  port: 25565\n")
        assertNull(BedrockGeyserConfig.readBedrockPort(file))
    }

    @Test
    fun `readBedrockPort returns null for a missing file`() {
        val missing = File(tempFolder.root, "plugins/Geyser-Spigot/config.yml")
        assertNull(BedrockGeyserConfig.readBedrockPort(missing))
    }

    @Test
    fun `readBedrockPort returns null for a non-numeric port`() {
        assertNull(BedrockGeyserConfig.readBedrockPort(modernConfigFile("bedrock:\n  port: soon\n")))
    }

    /* ------------------------------------------------------------------ */
    /* patchToPort: modern config                                         */
    /* ------------------------------------------------------------------ */

    @Test
    fun `patchToPort changes only the port digits in a modern config`() {
        val file = modernConfigFile()
        val before = file.readText()
        val outcome = BedrockGeyserConfig.patchToPort(file, 19133)

        assertTrue(outcome.changed)
        assertEquals(19132, outcome.originalPort)
        assertTrue(outcome.message.contains("19133"))

        val after = file.readText()
        assertOnlyPortDigitsChanged(before, after, "19132", "19133")
        assertEquals(replaceOnce(modernConfig, "  port: 19132\n", "  port: 19133\n"), after)
        // Comments, the broadcast-port sibling key and the remote block are untouched.
        assertTrue(after.contains("# The port to listen on (19132 is the vanilla Bedrock default)."))
        assertTrue(after.contains("broadcast-port: 19132"))
        assertTrue(after.contains("auth-type: floodgate"))
        assertEquals(19133, BedrockGeyserConfig.readBedrockPort(file))
    }

    @Test
    fun `patchToPort preserves a trailing inline comment`() {
        val file = modernConfigFile("bedrock:\n  port: 19132 # the Bedrock LAN port\n")
        val outcome = BedrockGeyserConfig.patchToPort(file, 19133)
        assertTrue(outcome.changed)
        assertEquals(19132, outcome.originalPort)
        assertEquals("bedrock:\n  port: 19133 # the Bedrock LAN port\n", file.readText())
    }

    @Test
    fun `patchToPort is a no-op when the port is already patched`() {
        val file = modernConfigFile(replaceOnce(modernConfig, "  port: 19132\n", "  port: 19133\n"))
        val before = file.readBytes()
        val outcome = BedrockGeyserConfig.patchToPort(file, 19133)

        assertFalse(outcome.changed)
        assertEquals(19133, outcome.originalPort)
        assertTrue(outcome.message.contains("already"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `patchToPort leaves a config without a bedrock section alone`() {
        val content = "motd: hi\nremote:\n  port: 25565\n"
        val file = modernConfigFile(content)
        val outcome = BedrockGeyserConfig.patchToPort(file, 19133)

        assertFalse(outcome.changed)
        assertNull(outcome.originalPort)
        assertTrue(outcome.message.contains("No Bedrock port"))
        assertEquals(content, file.readText())
    }

    @Test
    fun `patchToPort returns changed false for a missing file`() {
        val missing = File(tempFolder.root, "plugins/Geyser-Spigot/config.yml")
        val outcome = BedrockGeyserConfig.patchToPort(missing, 19133)

        assertFalse(outcome.changed)
        assertNull(outcome.originalPort)
        assertTrue(outcome.message.contains("not found"))
        assertFalse("patch must not create files", missing.exists())
    }

    @Test
    fun `patchToPort rejects an out of range port without touching the file`() {
        val file = modernConfigFile()
        val before = file.readBytes()

        assertFalse(BedrockGeyserConfig.patchToPort(file, 0).changed)
        assertFalse(BedrockGeyserConfig.patchToPort(file, 65536).changed)
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `patchToPort keeps a file without a trailing newline byte identical otherwise`() {
        val content = "bedrock:\n  port: 19132"
        val file = modernConfigFile(content)
        assertTrue(BedrockGeyserConfig.patchToPort(file, 19133).changed)
        assertEquals("bedrock:\n  port: 19133", file.readText())
    }

    /* ------------------------------------------------------------------ */
    /* patchToPort: legacy config and CRLF                                */
    /* ------------------------------------------------------------------ */

    @Test
    fun `patchToPort handles the legacy top level key`() {
        val file = createConfig("Geyser-Spigot", legacyConfig)
        val outcome = BedrockGeyserConfig.patchToPort(file, 19133)

        assertTrue(outcome.changed)
        assertEquals(19132, outcome.originalPort)
        assertEquals(replaceOnce(legacyConfig, "bedrock-port: 19132", "bedrock-port: 19133"), file.readText())
        assertEquals(19133, BedrockGeyserConfig.readBedrockPort(file))
    }

    @Test
    fun `patchToPort preserves CRLF line endings`() {
        val crlf = modernConfig.replace("\n", "\r\n")
        val file = modernConfigFile(crlf)
        val outcome = BedrockGeyserConfig.patchToPort(file, 19133)

        assertTrue(outcome.changed)
        val after = file.readText()
        assertEquals(replaceOnce(crlf, "  port: 19132\r\n", "  port: 19133\r\n"), after)
        assertTrue("CRLF endings must be preserved", after.contains("\r\n"))
        assertFalse("no lone LF may appear", after.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `patchToPort only touches the modern block when a legacy key also exists`() {
        val content = "bedrock-port: 19000\nbedrock:\n  port: 19132\n"
        val file = modernConfigFile(content)
        assertTrue(BedrockGeyserConfig.patchToPort(file, 19133).changed)
        assertEquals("bedrock-port: 19000\nbedrock:\n  port: 19133\n", file.readText())
    }


    /* ------------------------------------------------------------------ */
    /* prepareForBridge: Geyser 2.12+ transport / webrtc-port             */
    /* ------------------------------------------------------------------ */

    private val bridgeConfig = """
        # Geyser configuration file written by Geyser-Spigot 2.12.
        bedrock:
          # The IP address to listen on.
          address: 0.0.0.0
          # RakNet listen port used by the Bedrock LAN bridge.
          port: 19132
          # Transport: both keeps RakNet (19133) and NetherNet (19134) open.
          transport: nethernet
          # NetherNet/WebRTC signalling port.
          webrtc-port: 0 # 0 means "pick an ephemeral port".
          motd1: JavaHost
        remote:
          address: 127.0.0.1
          port: 25565
    """.trimIndent() + "\n"

    private fun bridgeConfigFile(content: String = bridgeConfig): File =
        createConfig("Geyser-Spigot", content)

    @Test
    fun `prepareForBridge patches port transport and webrtc port in one write`() {
        val file = bridgeConfigFile()
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertEquals(19132, outcome.originalPort)
        assertTrue(outcome.transportPatched)
        assertTrue(outcome.webrtcPortPatched)
        assertTrue(outcome.message.contains("19133"))

        val expected = replaceOnce(
            replaceOnce(
                replaceOnce(bridgeConfig, "  port: 19132\n", "  port: 19133\n"),
                "  transport: nethernet\n",
                "  transport: both\n"
            ),
            "  webrtc-port: 0 # 0 means \"pick an ephemeral port\".\n",
            "  webrtc-port: 19134 # 0 means \"pick an ephemeral port\".\n"
        )
        assertEquals(expected, file.readText())
        // Only three scalar lines changed; comments, ordering and line count survive.
        assertEquals(bridgeConfig.lines().size, file.readText().lines().size)
        assertTrue(file.readText().contains("# RakNet listen port used by the Bedrock LAN bridge.\n"))
        assertTrue(file.readText().contains("port: 25565"))
        assertEquals(19133, BedrockGeyserConfig.readBedrockPort(file))
    }

    @Test
    fun `prepareForBridge does not create a transport key when it is absent`() {
        val content = "bedrock:\n  port: 19132\n  webrtc-port: 0\n"
        val file = bridgeConfigFile(content)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertEquals(19132, outcome.originalPort)
        assertFalse(outcome.transportPatched)
        assertTrue(outcome.webrtcPortPatched)
        assertEquals("bedrock:\n  port: 19133\n  webrtc-port: 19134\n", file.readText())
    }

    @Test
    fun `prepareForBridge does not create a webrtc port key when it is absent`() {
        val content = "bedrock:\n  port: 19132\n  transport: nethernet\n"
        val file = bridgeConfigFile(content)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertTrue(outcome.transportPatched)
        assertFalse(outcome.webrtcPortPatched)
        assertEquals("bedrock:\n  port: 19133\n  transport: both\n", file.readText())
    }

    @Test
    fun `prepareForBridge changes nethernet transport even when the port is already right`() {
        val content = "bedrock:\n  port: 19133\n  transport: nethernet\n  webrtc-port: 19134\n"
        val file = bridgeConfigFile(content)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertNull("the port did not change, so no original port is recorded", outcome.originalPort)
        assertTrue(outcome.transportPatched)
        assertFalse(outcome.webrtcPortPatched)
        assertEquals("bedrock:\n  port: 19133\n  transport: both\n  webrtc-port: 19134\n", file.readText())
    }

    @Test
    fun `prepareForBridge moves webrtc port 0 to the bridge port keeping its comment`() {
        val content = "bedrock:\n  port: 19132\n  webrtc-port: 0 # ephemeral by default\n"
        val file = bridgeConfigFile(content)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertTrue(outcome.webrtcPortPatched)
        assertEquals("bedrock:\n  port: 19133\n  webrtc-port: 19134 # ephemeral by default\n", file.readText())
    }

    @Test
    fun `prepareForBridge preserves inline comments on every patched scalar`() {
        val content = "bedrock:\n  # Transport comment stays.\n  port: 19132 # raknet\n" +
            "  transport: nethernet # NetherNet is the 2.12 default.\n" +
            "  webrtc-port: 0 # ephemeral\n"
        val file = bridgeConfigFile(content)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertEquals(
            "bedrock:\n  # Transport comment stays.\n  port: 19133 # raknet\n" +
                "  transport: both # NetherNet is the 2.12 default.\n" +
                "  webrtc-port: 19134 # ephemeral\n",
            file.readText()
        )
    }

    @Test
    fun `prepareForBridge leaves transport alone when it already is both`() {
        val content = "bedrock:\n  port: 19132\n  transport: both\n  webrtc-port: 19134\n"
        val file = bridgeConfigFile(content)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertEquals(19132, outcome.originalPort)
        assertFalse(outcome.transportPatched)
        assertFalse(outcome.webrtcPortPatched)
        assertEquals("bedrock:\n  port: 19133\n  transport: both\n  webrtc-port: 19134\n", file.readText())
    }

    @Test
    fun `prepareForBridge is idempotent`() {
        val file = bridgeConfigFile()
        assertTrue(BedrockGeyserConfig.prepareForBridge(file, 19133, 19134).changed)
        val afterFirst = file.readBytes()

        val second = BedrockGeyserConfig.prepareForBridge(file, 19133, 19134)
        assertFalse(second.changed)
        assertNull(second.originalPort)
        assertFalse(second.transportPatched)
        assertFalse(second.webrtcPortPatched)
        assertTrue(second.message.contains("already"))
        assertArrayEquals(afterFirst, file.readBytes())
    }

    @Test
    fun `prepareForBridge preserves CRLF line endings`() {
        val crlf = bridgeConfig.replace("\n", "\r\n")
        val file = bridgeConfigFile(crlf)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        val after = file.readText()
        assertEquals(
            replaceOnce(
                replaceOnce(
                    replaceOnce(crlf, "  port: 19132\r\n", "  port: 19133\r\n"),
                    "  transport: nethernet\r\n",
                    "  transport: both\r\n"
                ),
                "  webrtc-port: 0 # 0 means \"pick an ephemeral port\".\r\n",
                "  webrtc-port: 19134 # 0 means \"pick an ephemeral port\".\r\n"
            ),
            after
        )
        assertFalse("no lone LF may appear", after.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `prepareForBridge still handles a legacy config`() {
        val file = createConfig("Geyser-Spigot", legacyConfig)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertTrue(outcome.changed)
        assertEquals(19132, outcome.originalPort)
        assertFalse(outcome.transportPatched)
        assertFalse(outcome.webrtcPortPatched)
        assertEquals(replaceOnce(legacyConfig, "bedrock-port: 19132", "bedrock-port: 19133"), file.readText())
    }

    @Test
    fun `prepareForBridge is a no-op without a bedrock port`() {
        val content = "bedrock:\n  transport: nethernet\n  webrtc-port: 0\n"
        val file = bridgeConfigFile(content)
        val outcome = BedrockGeyserConfig.prepareForBridge(file, bedrockPort = 19133, webrtcPort = 19134)

        assertFalse(outcome.changed)
        assertNull(outcome.originalPort)
        assertFalse(outcome.transportPatched)
        assertFalse(outcome.webrtcPortPatched)
        assertTrue(outcome.message.contains("No Bedrock port"))
        assertEquals(content, file.readText())
    }

    @Test
    fun `prepareForBridge returns changed false for a missing file`() {
        val missing = File(tempFolder.root, "plugins/Geyser-Spigot/config.yml")
        val outcome = BedrockGeyserConfig.prepareForBridge(missing, 19133, 19134)

        assertFalse(outcome.changed)
        assertNull(outcome.originalPort)
        assertFalse(missing.exists())
        assertTrue(outcome.message.contains("not found"))
    }

    @Test
    fun `prepareForBridge rejects invalid bridge ports without touching the file`() {
        val file = bridgeConfigFile()
        val before = file.readBytes()

        assertFalse(BedrockGeyserConfig.prepareForBridge(file, 19133, 0).changed)
        assertFalse(BedrockGeyserConfig.prepareForBridge(file, 0, 19134).changed)
        assertArrayEquals(before, file.readBytes())
    }

    /* ------------------------------------------------------------------ */
    /* Atomic write                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    fun `patchToPort writes atomically and leaves siblings intact`() {
        val plugins = pluginsDir()
        val file = createConfig("Geyser-Spigot", modernConfig)
        val sibling = File(plugins, "keep-me.txt").apply { writeText("sibling bytes\n") }
        val siblingDir = File(plugins, "Geyser-Spigot/other.txt").apply { writeText("other bytes\n") }

        assertTrue(BedrockGeyserConfig.patchToPort(file, 19133).changed)

        assertEquals("sibling bytes\n", sibling.readText())
        assertEquals("other bytes\n", siblingDir.readText())
        assertEquals(19133, BedrockGeyserConfig.readBedrockPort(file))
        // The temporary file used for the atomic rename must be gone.
        assertEquals(
            listOf("config.yml", "other.txt"),
            File(plugins, "Geyser-Spigot").listFiles()!!.map { it.name }.sorted()
        )
    }

    /* ------------------------------------------------------------------ */
    /* restorePort                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    fun `restorePort puts the original port back`() {
        val file = modernConfigFile()
        assertTrue(BedrockGeyserConfig.patchToPort(file, 19133).changed)
        assertEquals(19133, BedrockGeyserConfig.readBedrockPort(file))

        assertTrue(BedrockGeyserConfig.restorePort(file, expectedCurrentPort = 19133, originalPort = 19132))
        assertEquals(19132, BedrockGeyserConfig.readBedrockPort(file))
        assertEquals(modernConfig, file.readText())
    }

    @Test
    fun `restorePort is a no-op when the current port does not match`() {
        val file = modernConfigFile()
        val before = file.readBytes()

        assertFalse(BedrockGeyserConfig.restorePort(file, expectedCurrentPort = 19133, originalPort = 19132))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `restorePort returns true when the file already holds the original port`() {
        val file = modernConfigFile()
        val before = file.readBytes()

        assertTrue(BedrockGeyserConfig.restorePort(file, expectedCurrentPort = 19132, originalPort = 19132))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun `restorePort is false for a missing config`() {
        val missing = File(tempFolder.root, "plugins/Geyser-Spigot/config.yml")
        assertFalse(BedrockGeyserConfig.restorePort(missing, expectedCurrentPort = 19133, originalPort = 19132))
    }

    /* ------------------------------------------------------------------ */
    /* describe                                                           */
    /* ------------------------------------------------------------------ */

    @Test
    fun `describe names the file and the current bedrock port`() {
        val file = modernConfigFile()
        assertEquals(
            "plugins/Geyser-Spigot/config.yml (bedrock.port=19132)",
            BedrockGeyserConfig.describe(file)
        )
        assertTrue(BedrockGeyserConfig.patchToPort(file, 19133).changed)
        assertEquals(
            "plugins/Geyser-Spigot/config.yml (bedrock.port=19133)",
            BedrockGeyserConfig.describe(file)
        )
    }

    @Test
    fun `describe reports a config without a bedrock port`() {
        val file = modernConfigFile("motd: hi\n")
        assertEquals(
            "plugins/Geyser-Spigot/config.yml (no bedrock.port)",
            BedrockGeyserConfig.describe(file)
        )
    }

    @Test
    fun `describe handles null and missing files`() {
        assertEquals("no Geyser config found", BedrockGeyserConfig.describe(null))
        assertEquals(
            "no Geyser config found",
            BedrockGeyserConfig.describe(File(tempFolder.root, "plugins/Geyser-Spigot/config.yml"))
        )
    }
}
