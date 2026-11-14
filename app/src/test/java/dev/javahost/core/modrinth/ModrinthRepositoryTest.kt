package dev.javahost.core.modrinth

import dev.javahost.core.api.DownloadException
import dev.javahost.core.api.DownloadResult
import dev.javahost.core.api.Downloader
import dev.javahost.core.model.ModTarget
import dev.javahost.core.model.ModrinthFile
import dev.javahost.core.model.ModrinthVersion
import dev.javahost.core.model.ServerInstance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Hermetic tests for the Modrinth download/install pipeline. A fake [Downloader]
 * stands in for the network; no Android Context is involved because storage is
 * injected through resolver lambdas.
 */
class ModrinthRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fetchBase = "https://api.modrinth.test/v2"

    /* ------------------------------------------------------------------ */
    /* Search / version listing                                            */
    /* ------------------------------------------------------------------ */

    @Test
    fun `search builds facets and parses projects`() = runBlocking {
        val downloader = FakeDownloader().apply {
            textResponder = { url ->
                assertTrue(url, url.startsWith("$fetchBase/search?"))
                assertTrue(url, decodeParam(url, "facets").contains("project_type:plugin"))
                assertTrue(url, decodeParam(url, "facets").contains("versions:1.20.4"))
                assertTrue(url, decodeParam(url, "facets").contains("categories:paper"))
                assertTrue(url, url.contains("offset=20"))
                """{"hits":[{"project_id":"abc","slug":"my-plugin","title":"My Plugin",
                    "description":"Does things","icon_url":null,"downloads":42,
                    "project_type":"plugin","categories":["paper","spigot"],
                    "versions":["1.20.4"],"author":"someone"}]}"""
            }
        }
        val repository = repository(downloader)
        val result = repository.search(ModTarget.PLUGINS, "my plugin", "1.20.4", "paper", 20)
        assertTrue(result.isSuccess)
        val projects = result.getOrThrow()
        assertEquals(1, projects.size)
        assertEquals("abc", projects[0].id)
        assertEquals("my-plugin", projects[0].slug)
        assertEquals(listOf("paper", "spigot"), projects[0].loaders)
        assertNotNull(downloader.lastFetchUrl)
    }

    @Test
    fun `search surfaces downloadable-looking rate limit error as friendly message`() = runBlocking {
        val downloader = FakeDownloader().apply {
            textResponder = { throw DownloadException("HTTP 429 for https://api.modrinth.com/v2/search") }
        }
        val result = repository(downloader).search(ModTarget.MODS, "sodium", null, null)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("rate-limiting"))
        assertFalse("no stack trace leakage", message.contains("DownloadException"))
    }

    @Test
    fun `search rejects html payload with readable message`() = runBlocking {
        val downloader = FakeDownloader().apply {
            textResponder = { "<html>blocked by cloudflare</html>" }
        }
        val result = repository(downloader).search(ModTarget.MODS, "sodium", null, null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("unreadable"))
    }

    @Test
    fun `listVersions passes filters and parses versions`() = runBlocking {
        val downloader = FakeDownloader().apply {
            textResponder = { url ->
                assertTrue(url, url.startsWith("$fetchBase/project/sodium/version?"))
                assertTrue(url, url.contains("game_versions="))
                assertTrue(url, url.contains("loaders="))
                """[{"id":"v1","project_id":"sodium","name":"Sodium 0.5.8",
                    "version_number":"0.5.8","game_versions":["1.20.4"],
                    "loaders":["fabric"],"date_published":"2024-02-01T00:00:00Z",
                    "files":[{"filename":"sodium.jar","url":"https://cdn.example/sodium.jar",
                    "size":123,"hashes":{"sha1":"deadbeef"},"primary":true}]}]"""
            }
        }
        val result = repository(downloader).listVersions("sodium", "1.20.4", "fabric")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("sodium.jar", result.getOrThrow()[0].files[0].filename)
    }

    @Test
    fun `listVersions with blank project fails readably`() = runBlocking {
        val result = repository(FakeDownloader()).listVersions("   ", null, null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No Modrinth project"))
    }

    /* ------------------------------------------------------------------ */
    /* Install                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    fun `install downloads verifies sha1 and atomically installs plugin`() = runBlocking {
        val downloader = FakeDownloader()
        val repository = repository(downloader)
        val version = version(
            files = listOf(
                ModrinthFile(
                    filename = "MyPlugin-1.0.jar",
                    url = "https://cdn.modrinth.com/data/proj/versions/ver/MyPlugin-1.0.jar",
                    size = FakeDownloader.DEFAULT_BYTES.size.toLong(),
                    sha1 = "0123456789abcdef0123456789abcdef01234567",
                    primary = true
                )
            )
        )
        val result = repository.install("inst-1", version, ModTarget.PLUGINS)
        assertTrue(result.isSuccess)
        val installed = result.getOrThrow()
        assertEquals("MyPlugin-1.0.jar", installed.name)
        assertTrue(installed.isFile)
        assertArrayEqualsFile(FakeDownloader.DEFAULT_BYTES, installed)
        assertEquals(version.files[0].url, downloader.lastDownloadUrl)
        assertEquals(version.files[0].sha1, downloader.lastExpectedSha1)
        assertTrue(installed.absolutePath.contains("/instances/inst-1/plugins/"))
        assertNoStagingFiles(installed.parentFile!!)
    }

    @Test
    fun `install into mods uses mods directory`() = runBlocking {
        val downloader = FakeDownloader()
        val result = repository(downloader)
            .install("inst-1", version(listOf(jarFile("cool-mod.jar", size = 0))), ModTarget.MODS)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().absolutePath.contains("/instances/inst-1/mods/"))
    }

    @Test
    fun `install uses primary file when several are present`() = runBlocking {
        val downloader = FakeDownloader()
        val version = version(
            files = listOf(
                jarFile("sources.jar", url = "https://cdn.example/sources.jar", size = 0, primary = false),
                ModrinthFile(
                    "plugin.jar",
                    "https://cdn.example/primary.jar",
                    FakeDownloader.DEFAULT_BYTES.size.toLong(),
                    "abc",
                    primary = true
                )
            )
        )
        val result = repository(downloader).install("inst-1", version, ModTarget.PLUGINS)
        assertTrue(result.isSuccess)
        assertEquals("https://cdn.example/primary.jar", downloader.lastDownloadUrl)
        assertEquals("plugin.jar", result.getOrThrow().name)
    }

    @Test
    fun `install sanitizes traversal filename`() = runBlocking {
        val downloader = FakeDownloader()
        val version = version(listOf(jarFile("../../evil.jar", url = "https://cdn.example/x", size = 0)))
        val result = repository(downloader).install("inst-1", version, ModTarget.PLUGINS)
        assertTrue(result.isSuccess)
        val installed = result.getOrThrow()
        assertEquals("evil.jar", installed.name)
        val pluginsDir = File(temp.root, "instances/inst-1/plugins").canonicalFile
        assertTrue(installed.canonicalPath.startsWith(pluginsDir.path + File.separator))
    }

    @Test
    fun `install unknown instance fails readably`() = runBlocking {
        val result = repository(FakeDownloader(), instance = null)
            .install("missing", version(listOf(jarFile("x.jar"))), ModTarget.PLUGINS)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no longer exists"))
    }

    @Test
    fun `install version without files fails readably`() = runBlocking {
        val result = repository(FakeDownloader())
            .install("inst-1", version(emptyList()), ModTarget.PLUGINS)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no downloadable file"))
    }

    @Test
    fun `install size mismatch fails and leaves no partial file`() = runBlocking {
        val downloader = FakeDownloader()
        val version = version(
            listOf(
                ModrinthFile(
                    filename = "size-mismatch.jar",
                    url = "https://cdn.example/size.jar",
                    size = 9_999L,
                    sha1 = "abc",
                    primary = true
                )
            )
        )
        val result = repository(downloader).install("inst-1", version, ModTarget.PLUGINS)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("bytes"))
        val pluginsDir = File(temp.root, "instances/inst-1/plugins")
        assertFalse(File(pluginsDir, "size-mismatch.jar").exists())
        assertNoStagingFiles(pluginsDir)
    }

    @Test
    fun `install checksum failure is readable and cleans staging file`() = runBlocking {
        val downloader = FakeDownloader().apply {
            downloadResponder = { _, _, _ ->
                throw DownloadException(
                    "SHA-1 mismatch for plugin.jar: expected abc, got def. " +
                        "The download may be corrupted; retry."
                )
            }
        }
        val version = version(
            listOf(
                ModrinthFile("plugin.jar", "https://cdn.example/plugin.jar", 0, "abc", primary = true)
            )
        )
        val result = repository(downloader).install("inst-1", version, ModTarget.PLUGINS)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("SHA-1 mismatch"))
        assertFalse(File(temp.root, "instances/inst-1/plugins/plugin.jar").exists())
        assertNoStagingFiles(File(temp.root, "instances/inst-1/plugins"))
    }

    @Test
    fun `install network failure during download is readable`() = runBlocking {
        val downloader = FakeDownloader().apply {
            downloadResponder = { _, _, _ ->
                throw DownloadException("Failed to connect to cdn.modrinth.com")
            }
        }
        val result = repository(downloader).install(
            "inst-1",
            version(listOf(jarFile("x.jar", url = "https://cdn.modrinth.com/x.jar", size = 0))),
            ModTarget.PLUGINS
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Couldn't"))
    }

    // ---------------------------------------------------------------------

    private fun repository(
        downloader: Downloader,
        instance: ServerInstance? = ServerInstance("inst-1", "My Server", "server.jar")
    ): ModrinthRepository = ModrinthRepository(
        downloader = downloader,
        instanceResolver = { id -> instance?.takeIf { it.id == id } },
        installDirResolver = { id, target ->
            val folder = if (target == ModTarget.PLUGINS) "plugins" else "mods"
            File(temp.root, "instances/$id/$folder")
        },
        apiBaseUrl = fetchBase,
        searchLimit = 20
    )

    private fun version(files: List<ModrinthFile>, name: String = "Test Plugin 1.0"): ModrinthVersion =
        ModrinthVersion(
            id = "ver-1",
            projectId = "proj",
            name = name,
            versionNumber = "1.0",
            gameVersions = listOf("1.20.4"),
            loaders = listOf("paper"),
            files = files,
            datePublished = "2024-01-01T00:00:00Z"
        )

    private fun jarFile(
        filename: String,
        url: String = "https://cdn.example/$filename",
        size: Long = FakeDownloader.DEFAULT_BYTES.size.toLong(),
        primary: Boolean = true
    ): ModrinthFile = ModrinthFile(filename, url, size, sha1 = "abc", primary = primary)

    private fun assertArrayEqualsFile(expected: ByteArray, file: File) {
        assertEquals(expected.size, file.length().toInt())
        assertTrue(expected.contentEquals(file.readBytes()))
    }

    private fun assertNoStagingFiles(directory: File) {
        val leftovers = directory.listFiles()?.filter { it.name.endsWith(".part") || it.name.startsWith(".") }
            ?: emptyList()
        assertTrue("staging files left behind: $leftovers", leftovers.isEmpty())
    }

    private fun decodeParam(url: String, name: String): String =
        java.net.URLDecoder.decode(
            url.substringAfter("$name=").substringBefore('&').replace("+", "%2B"),
            "UTF-8"
        )

    private class FakeDownloader : Downloader {
        var textResponder: (String) -> String = { error("unexpected fetchText: $1") }
        var downloadResponder: (String, File, String?) -> DownloadResult = { _, destination, _ ->
            destination.parentFile?.mkdirs()
            destination.writeBytes(DEFAULT_BYTES)
            DownloadResult(destination, DEFAULT_BYTES.size.toLong())
        }
        var lastFetchUrl: String? = null
        var lastDownloadUrl: String? = null
        var lastExpectedSha1: String? = null
        var lastDestination: File? = null

        override suspend fun download(
            url: String,
            destination: File,
            expectedSha256: String?,
            expectedSha1: String?,
            onProgress: (Long, Long) -> Unit
        ): DownloadResult {
            lastDownloadUrl = url
            lastDestination = destination
            lastExpectedSha1 = expectedSha1
            val result = downloadResponder(url, destination, expectedSha1)
            onProgress(result.bytes, result.bytes)
            return result
        }

        override suspend fun fetchBytes(url: String, maxBytes: Long): ByteArray =
            fetchText(url, maxBytes).toByteArray()

        override suspend fun fetchText(url: String, maxBytes: Long): String {
            lastFetchUrl = url
            return textResponder(url)
        }

        companion object {
            val DEFAULT_BYTES: ByteArray = "FAKE-JAR-CONTENT".toByteArray(Charsets.UTF_8)
        }
    }
}
