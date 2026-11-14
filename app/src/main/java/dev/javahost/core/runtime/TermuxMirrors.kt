package dev.javahost.core.runtime

/**
 * Mirror URL construction and fallback ordering for the Termux APT repository.
 *
 * Kept pure (no Android imports) so the ordering/deduplication rules are unit
 * tested without a device.
 */
object TermuxMirrors {

    const val PACKAGES_GZ_PATH = "/dists/stable/main/binary-aarch64/Packages.gz"
    const val PACKAGES_PATH = "/dists/stable/main/binary-aarch64/Packages"

    /**
     * Mirrors users can pick in the runtime screen. All serve the same signed
     * Termux package set; latency and throughput vary a lot by network/country.
     *
     * Verified live from the development device:
     *  - packages.termux.dev sometimes stalls from mobile ISPs;
     *  - TUNA (China), mwt, grimler and USTC are usually fast alternatives.
     */
    data class MirrorPreset(
        val id: String,
        val label: String,
        val url: String,
        val note: String
    )

    val PRESETS: List<MirrorPreset> = listOf(
        MirrorPreset(
            id = "official",
            label = "Termux official",
            url = "https://packages.termux.dev/apt/termux-main",
            note = "Upstream Termux repository"
        ),
        MirrorPreset(
            id = "tuna",
            label = "TUNA (China)",
            url = "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
            note = "Usually the fastest mirror in Asia"
        ),
        MirrorPreset(
            id = "mwt",
            label = "MWT (global)",
            url = "https://mirror.mwt.me/termux/main",
            note = "CDN-backed global mirror"
        ),
        MirrorPreset(
            id = "grimler",
            label = "Grimler (Europe)",
            url = "https://grimler.se/termux/termux-main",
            note = "European mirror"
        ),
        MirrorPreset(
            id = "ustc",
            label = "USTC (China)",
            url = "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
            note = "University of Science and Technology of China"
        ),
        MirrorPreset(
            id = "bfsu",
            label = "BFSU (China)",
            url = "https://mirrors.bfsu.edu.cn/termux/apt/termux-main",
            note = "Beijing Foreign Studies University mirror"
        )
    )

    /**
     * Candidate mirror roots, in priority order: the user's override (if valid),
     * then the build-configured primary and fallback, then every known mirror as
     * a final fallback. Duplicates are removed and trailing slashes normalized.
     */
    fun candidates(configured: String, primary: String, fallback: String): List<String> {
        val result = LinkedHashSet<String>()
        for (raw in listOf(configured, primary, fallback) + PRESETS.map { it.url }) {
            normalizeMirror(raw)?.let(result::add)
        }
        return result.toList()
    }

    /**
     * Normalizes a mirror setting to a scheme+host+path root.
     *
     * Accepts values pasted from a browser or from Termux's `sources.list`
     * (e.g. `https://.../dists/stable/main/binary-aarch64/Packages.gz`).
     * Returns null for blank values and non-HTTPS URLs: the app's network
     * security config and the downloader both forbid cleartext.
     */
    fun normalizeMirror(raw: String): String? {
        var mirror = raw.trim()
        if (mirror.isEmpty()) return null
        if (!mirror.startsWith("https://", ignoreCase = true)) return null
        mirror = mirror.trimEnd('/')
        // Users sometimes paste a full repository path; reduce it to the root.
        val dists = mirror.indexOf("/dists/")
        if (dists > 0) mirror = mirror.substring(0, dists)
        mirror = mirror.trimEnd('/')
        if (mirror.length <= "https://".length) return null
        return mirror
    }

    fun packagesUrls(mirror: String): List<String> = listOf(
        packageUrl(mirror, PACKAGES_GZ_PATH),
        packageUrl(mirror, PACKAGES_PATH)
    )

    fun packageUrl(mirror: String, path: String): String =
        mirror.trimEnd('/') + "/" + path.trimStart('/')

    /** Human-readable name for a configured mirror URL, or null for auto/custom. */
    fun presetLabel(url: String): String? =
        PRESETS.firstOrNull { normalizeMirror(it.url) == normalizeMirror(url) }?.label
}
