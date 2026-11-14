package dev.javahost.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogTailStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var instanceDir: File

    @Before
    fun setUp() {
        instanceDir = temp.newFolder("instance")
    }

    private fun createStore(rotationBytes: Long = LogTailStore.DEFAULT_ROTATION_BYTES): LogTailStore =
        LogTailStore(
            transcriptFile = File(instanceDir, "logs/console.log"),
            previousTranscriptFile = File(instanceDir, "logs/console.previous.log"),
            crashDir = File(instanceDir, "crashes"),
            lastCrashFile = File(instanceDir, "crashes/last-crash.txt"),
            rotationBytes = rotationBytes
        )

    @Test
    fun `append and tail round trip`() {
        val store = createStore()
        store.startSession()
        for (i in 1..10) store.append("line $i")
        assertEquals(listOf("line 8", "line 9", "line 10"), store.readTail(3))
        assertEquals(10, store.readTail(100).size)
        assertEquals(emptyList<String>(), store.readTail(0))
        assertTrue(File(instanceDir, "logs/console.log").isFile)
        store.close()
    }

    @Test
    fun `readTail returns empty for missing log`() {
        val store = createStore()
        assertEquals(emptyList<String>(), store.readTail(50))
        store.close()
    }

    @Test
    fun `startSession moves previous run to previous log`() {
        val first = createStore()
        first.startSession()
        first.append("old line")
        first.close()

        val second = createStore()
        second.startSession()
        second.append("new line")
        assertEquals(listOf("new line"), second.readTail(10))
        assertEquals(
            listOf("old line"),
            LogTailStore.tailOf(File(instanceDir, "logs/console.previous.log"), 10)
        )
        second.close()
    }

    @Test
    fun `rotation at threshold keeps both files and preserves order`() {
        // Each appended line is 17 chars + newline = 18 bytes; 7 lines = 126 bytes.
        val store = createStore(rotationBytes = 120)
        store.startSession()
        for (i in 0 until 7) store.append("line-$i-abcdefghij")
        val latest = File(instanceDir, "logs/console.log")
        val previous = File(instanceDir, "logs/console.previous.log")
        assertTrue(previous.isFile)
        assertTrue(latest.isFile)
        val previousLines = LogTailStore.tailOf(previous, 20)
        assertEquals(7, previousLines.size)
        assertEquals("line-0-abcdefghij", previousLines.first())

        for (i in 7 until 12) store.append("line-$i-abcdefghij")
        assertEquals(
            listOf(
                "line-8-abcdefghij",
                "line-9-abcdefghij",
                "line-10-abcdefghij",
                "line-11-abcdefghij"
            ),
            store.readTail(4)
        )
        assertEquals(5, store.readTail(100).size)
        store.close()
    }

    @Test
    fun `crash report is written and mirrored`() {
        val store = createStore()
        store.startSession()
        store.append("something bad")
        val crashDir = File(instanceDir, "crashes")
        val report = store.writeCrashReport(
            timestamp = 123456789L,
            header = listOf("JavaHost crash report", "Exit code: 137"),
            tail = listOf("boom", "tick")
        )
        assertNotNull(report)
        report!!
        assertEquals("crash-123456789.txt", report.name)
        assertTrue(report.isFile)
        val text = report.readText()
        assertTrue(text.contains("JavaHost crash report"))
        assertTrue(text.contains("Exit code: 137"))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("tick"))
        val lastCrash = File(crashDir, "last-crash.txt")
        assertTrue(lastCrash.isFile)
        assertEquals(text, lastCrash.readText())
        store.close()
    }

    @Test
    fun `tailOf ignores partial first line when reading a chunk`() {
        val file = temp.newFile("big.log")
        val lines = (0 until 50).map { "line-$it" }
        file.writeText(lines.joinToString("\n") + "\n")
        val tail = LogTailStore.tailOf(file, 5, maxBytes = 40)
        assertEquals(listOf("line-45", "line-46", "line-47", "line-48", "line-49"), tail)
    }

    @Test
    fun `transcript never touches the server own latest log`() {
        val serverLog = File(instanceDir, "logs/latest.log")
        serverLog.parentFile.mkdirs()
        serverLog.writeText("minecraft own log line\n")
        val store = createStore()
        store.startSession()
        store.append("app transcript line")
        assertEquals("minecraft own log line\n", serverLog.readText(Charsets.UTF_8))
        assertFalse(File(instanceDir, "logs/console.previous.log").isFile)
        assertEquals(listOf("app transcript line"), store.readTail(10))
        store.close()
    }

    @Test
    fun `empty lines are not appended`() {
        val store = createStore()
        store.startSession()
        store.append("")
        store.append("real")
        assertEquals(listOf("real"), store.readTail(10))
        assertFalse(store.readTail(10).isEmpty())
        store.close()
    }
}
