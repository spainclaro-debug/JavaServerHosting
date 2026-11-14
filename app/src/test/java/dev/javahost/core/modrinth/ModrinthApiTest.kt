package dev.javahost.core.modrinth

import dev.javahost.core.model.ModTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder

/**
 * Hermetic tests for the pure Modrinth helpers: URL/facet construction, JSON
 * parsing and untrusted-filename sanitising. No network, no Android.
 */
class ModrinthApiTest {

    /* ------------------------------------------------------------------ */
    /* URL / facets                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    fun `facets for mods contain only project type when unfiltered`() {
        assertEquals(
            """[["project_type:mod"]]""",
            ModrinthApi.buildFacets(ModTarget.MODS, null, null)
        )
    }

    @Test
    fun `facets for plugins add versions and loader categories`() {
        assertEquals(
            """[["project_type:plugin"],["versions:1.20.4"],["categories:paper"]]""",
            ModrinthApi.buildFacets(ModTarget.PLUGINS, " 1.20.4 ", " PAPER ")
        )
    }

    @Test
    fun `facets ignore blank filters`() {
        assertEquals(
            """[["project_type:mod"]]""",
            ModrinthApi.buildFacets(ModTarget.MODS, "  ", "")
        )
    }

    @Test
    fun `search url encodes query facets and offset`() {
        val url = ModrinthApi.buildSearchUrl(
            apiBase = "https://api.modrinth.com/v2/",
            target = ModTarget.PLUGINS,
            query = "world edit",
            gameVersion = "1.20.4",
            loader = "paper",
            offset = 40,
            limit = 20
        )
        assertTrue(url, url.startsWith("https://api.modrinth.com/v2/search?"))
        val query = URLDecoder.decode(url.substringAfter("query=").substringBefore('&'), "UTF-8")
        assertEquals("world edit", query)
        val facets = URLDecoder.decode(url.substringAfter("facets=").substringBefore('&'), "UTF-8")
        assertEquals(
            """[["project_type:plugin"],["versions:1.20.4"],["categories:paper"]]""",
            facets
        )
        assertTrue(url, url.contains("limit=20"))
        assertTrue(url, url.contains("offset=40"))
        assertEquals(ModrinthApi.SEARCH_LIMIT, 20)
    }

    @Test
    fun `search url clamps limit and offset`() {
        val url = ModrinthApi.buildSearchUrl(
            apiBase = "https://api.modrinth.com/v2",
            target = ModTarget.MODS,
            query = "",
            gameVersion = null,
            loader = null,
            offset = -5,
            limit = 500
        )
        assertTrue(url, url.contains("limit=100"))
        assertTrue(url, url.contains("offset=0"))
    }

    @Test
    fun `versions url encodes json array filters`() {
        val url = ModrinthApi.buildVersionsUrl(
            apiBase = "https://api.modrinth.com/v2",
            projectId = "paper",
            gameVersion = "1.20.4",
            loader = "Paper"
        )
        assertTrue(url, url.startsWith("https://api.modrinth.com/v2/project/paper/version?"))
        val gameVersions =
            URLDecoder.decode(url.substringAfter("game_versions=").substringBefore('&'), "UTF-8")
        assertEquals("""["1.20.4"]""", gameVersions)
        val loaders = URLDecoder.decode(url.substringAfter("loaders="), "UTF-8")
        assertEquals("""["paper"]""", loaders)
    }

    @Test
    fun `versions url without filters has no query string`() {
        val url = ModrinthApi.buildVersionsUrl(
            "https://api.modrinth.com/v2",
            "sodium",
            null,
            null
        )
        assertEquals("https://api.modrinth.com/v2/project/sodium/version", url)
    }

    @Test
    fun `project type mapping`() {
        assertEquals("plugin", ModrinthApi.projectTypeFor(ModTarget.PLUGINS))
        assertEquals("mod", ModrinthApi.projectTypeFor(ModTarget.MODS))
    }

    /* ------------------------------------------------------------------ */
    /* Parsing                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    fun `parses realistic search response`() {
        val projects = ModrinthApi.parseSearchResults(SEARCH_JSON)
        assertEquals(2, projects.size)
        val sodium = projects[0]
        assertEquals("AANobbMI", sodium.id)
        assertEquals("sodium", sodium.slug)
        assertEquals("Sodium", sodium.title)
        assertEquals("A rendering engine", sodium.description)
        assertEquals("https://cdn.modrinth.com/data/AANobbMI/icon.png", sodium.iconUrl)
        assertEquals(252141115L, sodium.downloads)
        assertEquals("mod", sodium.projectType)
        // Search hits put both loaders and categories in "categories".
        assertEquals(listOf("fabric", "quilt"), sodium.loaders)
        assertEquals(listOf("1.20.4", "1.21"), sodium.gameVersions)
        assertEquals("jellysquid3", sodium.author)

        val noIcon = projects[1]
        assertNull(noIcon.iconUrl)
        assertEquals(0L, noIcon.downloads)
        assertNull(noIcon.author)
    }

    @Test
    fun `search parser skips malformed hits instead of failing the page`() {
        val text = """
            {"hits":[
              {"project_id":"good","slug":"good","title":"Good","categories":["paper"],
               "versions":["1.20.4"],"project_type":"plugin","downloads":12},
              {"title":"missing id and slug"},
              {"slug":"idless"}
            ]}
        """.trimIndent()
        val projects = ModrinthApi.parseSearchResults(text)
        assertEquals(1, projects.size)
        assertEquals("good", projects[0].id)
    }

    @Test
    fun `search parser rejects non-json payload`() {
        try {
            ModrinthApi.parseSearchResults("<html>cloudflare says no</html>")
            fail("expected ModrinthPayloadException")
        } catch (e: ModrinthPayloadException) {
            assertTrue(e.message!!, e.message!!.contains("unreadable"))
        }
    }

    @Test
    fun `search parser rejects json object without hits`() {
        try {
            ModrinthApi.parseSearchResults("""{"error":"not found"}""")
            fail("expected ModrinthPayloadException")
        } catch (e: ModrinthPayloadException) {
            assertTrue(e.message!!, e.message!!.contains("without results"))
        }
    }

    @Test
    fun `parses realistic version response with primary file and sha1`() {
        val versions = ModrinthApi.parseVersions(VERSIONS_JSON)
        assertEquals(2, versions.size)
        val first = versions[0]
        assertEquals("4GyXKCLd", first.id)
        assertEquals("AANobbMI", first.projectId)
        assertEquals("Sodium 0.5.8", first.name)
        assertEquals("mc1.20.4-0.5.8", first.versionNumber)
        assertEquals(listOf("1.20.3", "1.20.4"), first.gameVersions)
        assertEquals(listOf("fabric", "quilt"), first.loaders)
        assertEquals("2024-02-01T20:33:48.832862Z", first.datePublished)
        assertEquals(1, first.files.size)
        val file = first.files[0]
        assertEquals("sodium-fabric-0.5.8+mc1.20.4.jar", file.filename)
        assertTrue(file.url.startsWith("https://cdn.modrinth.com/"))
        assertEquals(949085L, file.size)
        assertEquals("4c38d7b01660a27a98406767c613b3f28b6c9dfe", file.sha1)
        assertTrue(file.primary)
        assertNull(versions[1].files[0].sha1)
    }

    @Test
    fun `versions parser rejects a json object`() {
        try {
            ModrinthApi.parseVersions("""{"id":"x"}""")
            fail("expected ModrinthPayloadException")
        } catch (e: ModrinthPayloadException) {
            assertTrue(e.message!!, e.message!!.contains("unexpected version list"))
        }
    }

    @Test
    fun `parses empty version list`() {
        assertEquals(emptyList<Any>(), ModrinthApi.parseVersions("[]"))
    }

    /* ------------------------------------------------------------------ */
    /* File selection / naming                                             */
    /* ------------------------------------------------------------------ */

    @Test
    fun `primary file prefers flagged primary`() {
        val versions = ModrinthApi.parseVersions(VERSIONS_JSON)
        assertEquals("sodium-fabric-0.5.8+mc1.20.4.jar", ModrinthApi.primaryFile(versions[0])!!.filename)
    }

    @Test
    fun `primary file falls back to first jar then first file`() {
        val noPrimary = ModrinthApi.parseVersions(
            """[{"id":"v","project_id":"p","version_number":"1","files":[
                 {"filename":"readme.txt","url":"https://example.com/readme.txt","size":1},
                 {"filename":"plugin.jar","url":"https://example.com/plugin.jar","size":2,
                  "hashes":{"sha1":"abc"},"primary":false}]}]"""
        )[0]
        assertEquals("plugin.jar", ModrinthApi.primaryFile(noPrimary)!!.filename)

        val onlyText = ModrinthApi.parseVersions(
            """[{"id":"v","project_id":"p","files":[
                 {"filename":"readme.txt","url":"https://example.com/readme.txt","size":1}]}]"""
        )[0]
        assertEquals("readme.txt", ModrinthApi.primaryFile(onlyText)!!.filename)

        val empty = ModrinthApi.parseVersions(
            """[{"id":"v","project_id":"p","files":[]}]"""
        )[0]
        assertNull(ModrinthApi.primaryFile(empty))
    }

    @Test
    fun `sanitize keeps realistic plugin filename`() {
        assertEquals(
            "sodium-fabric-0.5.8+mc1.20.4.jar",
            ModrinthApi.sanitizeFilename("sodium-fabric-0.5.8+mc1.20.4.jar")
        )
        assertEquals("Essentials-2.20.1.jar", ModrinthApi.sanitizeFilename("Essentials-2.20.1.jar"))
    }

    @Test
    fun `sanitize strips directories and traversal`() {
        assertEquals("evil.jar", ModrinthApi.sanitizeFilename("../../evil.jar"))
        assertEquals("passwd.jar", ModrinthApi.sanitizeFilename("/etc/passwd"))
        assertEquals("x.jar", ModrinthApi.sanitizeFilename("C:\\Users\\me\\x.jar"))
        assertEquals("plugin.jar", ModrinthApi.sanitizeFilename("folder/sub/plugin"))
    }

    @Test
    fun `sanitize replaces unsafe characters and forces jar extension`() {
        assertEquals("my_plugin.jar", ModrinthApi.sanitizeFilename("my plugin"))
        assertEquals("a_b_c__.jar", ModrinthApi.sanitizeFilename("a b:c*?.jar"))
        assertEquals("download.jar", ModrinthApi.sanitizeFilename(""))
        assertEquals("download.jar", ModrinthApi.sanitizeFilename(".."))
    }

    @Test
    fun `sanitize bounds filename length but keeps extension`() {
        val name = ModrinthApi.sanitizeFilename("x".repeat(400) + ".jar")
        assertEquals(120, name.length)
        assertTrue(name, name.endsWith(".jar"))
    }

    @Test
    fun `sanitize never returns a path separator`() {
        for (candidate in listOf("../../a/b.jar", "..\\..\\c.jar", "d/e/f.jar")) {
            val safe = ModrinthApi.sanitizeFilename(candidate)
            assertTrue(safe, !safe.contains('/') && !safe.contains('\\'))
        }
    }

    // ---------------------------------------------------------------------

    companion object {
        private val SEARCH_JSON = """
            {
              "hits": [
                {
                  "project_id": "AANobbMI",
                  "project_type": "mod",
                  "slug": "sodium",
                  "author": "jellysquid3",
                  "title": "Sodium",
                  "description": "A rendering engine",
                  "categories": ["fabric", "quilt", "optimization"],
                  "versions": ["1.20.4", "1.21"],
                  "downloads": 252141115,
                  "icon_url": "https://cdn.modrinth.com/data/AANobbMI/icon.png",
                  "license": "LGPL-3.0-only",
                  "server_side": "unsupported"
                },
                {
                  "project_id": "noiconid",
                  "project_type": "plugin",
                  "slug": "noicon",
                  "title": "No Icon"
                }
              ],
              "offset": 0,
              "limit": 20,
              "total_hits": 2
            }
        """.trimIndent()

        private val VERSIONS_JSON = """
            [
              {
                "game_versions": ["1.20.3", "1.20.4"],
                "loaders": ["fabric", "quilt"],
                "id": "4GyXKCLd",
                "project_id": "AANobbMI",
                "name": "Sodium 0.5.8",
                "version_number": "mc1.20.4-0.5.8",
                "date_published": "2024-02-01T20:33:48.832862Z",
                "version_type": "release",
                "files": [
                  {
                    "id": "bjGDIeYv",
                    "hashes": {
                      "sha512": "bd00b9",
                      "sha1": "4c38d7b01660a27a98406767c613b3f28b6c9dfe"
                    },
                    "url": "https://cdn.modrinth.com/data/AANobbMI/versions/4GyXKCLd/sodium-fabric-0.5.8%2Bmc1.20.4.jar",
                    "filename": "sodium-fabric-0.5.8+mc1.20.4.jar",
                    "primary": true,
                    "size": 949085
                  }
                ]
              },
              {
                "game_versions": ["1.20.4"],
                "loaders": ["fabric"],
                "id": "older",
                "project_id": "AANobbMI",
                "name": "Sodium 0.5.7",
                "version_number": "mc1.20.4-0.5.7",
                "date_published": "2024-01-29T01:45:32.5Z",
                "files": [
                  {
                    "hashes": {"sha512": "aa"},
                    "url": "https://cdn.modrinth.com/data/AANobbMI/versions/older/sodium-0.5.7.jar",
                    "filename": "sodium-0.5.7.jar",
                    "primary": true,
                    "size": 941000
                  }
                ]
              }
            ]
        """.trimIndent()
    }
}
