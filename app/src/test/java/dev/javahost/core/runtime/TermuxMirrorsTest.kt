package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxMirrorsTest {

    private val primary = "https://packages.termux.dev/apt/termux-main"
    private val fallback = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"

    @Test
    fun ordersConfiguredThenPrimaryThenFallback() {
        val candidates = TermuxMirrors.candidates(
            configured = "https://my.mirror.example/termux/apt/termux-main",
            primary = primary,
            fallback = fallback
        )
        assertEquals(
            "https://my.mirror.example/termux/apt/termux-main",
            candidates.first()
        )
        assertEquals(primary, candidates[1])
        assertEquals(fallback, candidates[2])
        assertTrue(candidates.containsAll(TermuxMirrors.PRESETS.map { it.url }))
    }

    @Test
    fun emptyConfiguredFallsBackToBuildMirrors() {
        assertEquals(
            listOf(primary, fallback),
            TermuxMirrors.candidates("", primary, fallback).take(2)
        )
        assertEquals(
            listOf(primary, fallback),
            TermuxMirrors.candidates("   ", primary, fallback).take(2)
        )
    }

    @Test
    fun deduplicatesTrailingSlashVariants() {
        val candidates = TermuxMirrors.candidates(
            configured = "$primary/",
            primary = primary,
            fallback = primary
        )
        assertEquals(primary, candidates.first())
        assertEquals(1, candidates.count { it == primary })
    }

    @Test
    fun normalizesFullPackagesUrlToMirrorRoot() {
        assertEquals(
            primary,
            TermuxMirrors.normalizeMirror("$primary/dists/stable/main/binary-aarch64/Packages.gz")
        )
        assertEquals(
            primary,
            TermuxMirrors.normalizeMirror("$primary/dists/stable")
        )
    }

    @Test
    fun rejectsCleartextAndBlankMirrors() {
        assertNull(TermuxMirrors.normalizeMirror("http://insecure.example/termux"))
        assertNull(TermuxMirrors.normalizeMirror(""))
        assertNull(TermuxMirrors.normalizeMirror("   "))
        assertNull(TermuxMirrors.normalizeMirror("packages.termux.dev/apt/termux-main"))
        assertEquals(null, TermuxMirrors.normalizeMirror("https://"))
    }

    @Test
    fun presetsAreHttpsAndUnique() {
        assertTrue(TermuxMirrors.PRESETS.isNotEmpty())
        val urls = TermuxMirrors.PRESETS.map { it.url }
        assertEquals(urls.size, urls.distinct().size)
        assertTrue(urls.all { it.startsWith("https://") })
        assertTrue(urls.all { TermuxMirrors.normalizeMirror(it) == it })
    }

    @Test
    fun presetLabelResolvesKnownMirrorsAndCustomValues() {
        assertEquals("TUNA (China)", TermuxMirrors.presetLabel(TermuxMirrors.PRESETS[1].url))
        assertNull(TermuxMirrors.presetLabel("https://example.invalid/termux"))
    }

    @Test
    fun buildsPackageUrls() {
        assertEquals(
            "$primary/dists/stable/main/binary-aarch64/Packages.gz",
            TermuxMirrors.packageUrl(primary, TermuxMirrors.PACKAGES_GZ_PATH)
        )
        assertEquals(
            "$primary/pool/main/o/openjdk-21/openjdk-21_21.0.13-2_aarch64.deb",
            TermuxMirrors.packageUrl(
                primary,
                "/pool/main/o/openjdk-21/openjdk-21_21.0.13-2_aarch64.deb"
            )
        )
    }

    @Test
    fun packagesUrlsTriesGzipFirstThenPlain() {
        val urls = TermuxMirrors.packagesUrls(primary)
        assertEquals(2, urls.size)
        assertTrue(urls[0].endsWith("Packages.gz"))
        assertTrue(urls[1].endsWith("Packages"))
    }
}
