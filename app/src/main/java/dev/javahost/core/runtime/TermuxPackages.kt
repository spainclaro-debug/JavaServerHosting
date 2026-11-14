package dev.javahost.core.runtime

import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * One package stanza from a Termux APT `Packages` index.
 *
 * Only the fields the installer needs are modelled. `sha256` may be empty for
 * very old indices, in which case the installer still verifies size and may
 * skip the checksum with a warning.
 */
data class TermuxPackage(
    val name: String,
    val version: String,
    val filename: String,
    val sha256: String,
    val size: Long,
    val depends: List<List<DependencyAlternative>> = emptyList(),
    val provides: List<String> = emptyList(),
    val architecture: String? = null
) {
    /** Raw dependency alternative groups as `a | b` strings, for diagnostics/tests. */
    val dependsRaw: List<String>
        get() = depends.map { group -> group.joinToString(" | ") { it.name } }

    val requiresCaCertificatesJava: Boolean
        get() = depends.any { group -> group.any { it.name == "ca-certificates-java" } }
}

/** One alternative inside a `Depends:` group, e.g. `libfoo (>= 1.2)` or `libbar:any`. */
data class DependencyAlternative(
    val name: String,
    val constraint: String? = null
)

class MissingTermuxPackageException(message: String) : IOException(message)

/**
 * Pure parser for Debian-style `Packages` files.
 *
 * The parser deliberately knows nothing about Android or the network so it can
 * be unit-tested hermetically. It tolerates CRLF line endings, folded
 * continuation lines, unknown fields and stanzas with missing optional data.
 * A stanza needs at least `Package`, `Version` and `Filename` to be usable.
 */
object TermuxPackagesParser {

    fun parse(text: String): List<TermuxPackage> {
        val result = ArrayList<TermuxPackage>()
        val fields = LinkedHashMap<String, String>()
        var currentKey: String? = null

        fun flush() {
            try {
                toPackage(fields)?.let(result::add)
            } finally {
                fields.clear()
                currentKey = null
            }
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) {
                if (fields.isNotEmpty()) flush()
                continue
            }
            val first = line[0]
            if (first == ' ' || first == '\t') {
                // RFC822 continuation line: append to the previous field.
                val key = currentKey ?: continue
                val previous = fields[key]
                fields[key] = if (previous.isNullOrEmpty()) line.trim() else previous + "\n" + line.trim()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            fields[key] = line.substring(colon + 1).trim()
            currentKey = key
        }
        if (fields.isNotEmpty()) flush()
        return result
    }

    /** Parse the gzip-compressed variant served by Termux mirrors (`Packages.gz`). */
    fun parseGzip(bytes: ByteArray): List<TermuxPackage> =
        GZIPInputStream(bytes.inputStream()).use { input ->
            parse(input.readBytes().toString(Charsets.UTF_8))
        }

    private fun toPackage(fields: Map<String, String>): TermuxPackage? {
        val name = fields["package"] ?: return null
        val version = fields["version"] ?: return null
        val filename = fields["filename"] ?: return null
        val size = fields["size"]?.trim()?.toLongOrNull() ?: 0L
        val sha256 = fields["sha256"]?.trim()?.lowercase().orEmpty()
        return TermuxPackage(
            name = name,
            version = version,
            filename = filename,
            sha256 = sha256,
            size = size,
            depends = parseDepends(fields["depends"]),
            provides = parseProvides(fields["provides"]),
            architecture = fields["architecture"]
        )
    }

    /** Split a `Depends:`/`Pre-Depends:` value into OR groups of alternatives. */
    fun parseDepends(raw: String?): List<List<DependencyAlternative>> {
        if (raw.isNullOrBlank()) return emptyList()
        return splitTopLevel(raw, ',')
            .mapNotNull { group ->
                val alternatives = splitTopLevel(group, '|')
                    .mapNotNull(::parseAlternative)
                alternatives.takeIf { it.isNotEmpty() }
            }
    }

    private fun parseProvides(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return splitTopLevel(raw, ',')
            .mapNotNull { parseAlternative(it)?.name }
    }

    private fun parseAlternative(raw: String): DependencyAlternative? {
        var token = raw.trim()
        if (token.isEmpty()) return null
        var constraint: String? = null
        val paren = token.indexOf('(')
        if (paren >= 0) {
            val close = token.indexOf(')', paren)
            if (close > paren) {
                constraint = token.substring(paren + 1, close).trim()
                token = (token.substring(0, paren) + token.substring(close + 1)).trim()
            }
        }
        // Drop architecture restrictions like `[arm64]` and multiarch qualifiers.
        token = token.substringBefore('[').trim()
        token = token.substringBefore(':').trim()
        if (token.isEmpty()) return null
        return DependencyAlternative(name = token, constraint = constraint)
    }

    /**
     * Split on [separator] but ignore separators inside parentheses, which
     * matters for version constraints such as `foo (>= 1.2, < 2.0)`.
     */
    private fun splitTopLevel(raw: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        val builder = StringBuilder()
        var depth = 0
        for (c in raw) {
            when (c) {
                '(' -> {
                    depth++
                    builder.append(c)
                }
                ')' -> {
                    if (depth > 0) depth--
                    builder.append(c)
                }
                separator -> if (depth == 0) {
                    parts.add(builder.toString())
                    builder.clear()
                } else {
                    builder.append(c)
                }
                else -> builder.append(c)
            }
        }
        parts.add(builder.toString())
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Debian version ordering (`[epoch:]upstream[-revision]`), enough to pick the
 * newest available package when a mirror publishes several versions.
 * See Debian Policy 5.6.12 for the comparison rules implemented here.
 */
object DebianVersion {
    fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val va = split(a)
        val vb = split(b)
        if (va.epoch != vb.epoch) return va.epoch.compareTo(vb.epoch)
        val upstream = compareFragment(va.upstream, vb.upstream)
        if (upstream != 0) return upstream
        return compareFragment(va.revision, vb.revision)
    }

    private data class Parts(val epoch: Long, val upstream: String, val revision: String)

    private fun split(version: String): Parts {
        var rest = version.trim()
        var epoch = 0L
        val colon = rest.indexOf(':')
        if (colon > 0 && rest.substring(0, colon).all(Char::isDigit)) {
            epoch = rest.substring(0, colon).toLong()
            rest = rest.substring(colon + 1)
        }
        val dash = rest.lastIndexOf('-')
        return if (dash >= 0) {
            Parts(epoch, rest.substring(0, dash), rest.substring(dash + 1))
        } else {
            Parts(epoch, rest, "")
        }
    }

    private fun compareFragment(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length || j < b.length) {
            // 1) Compare the non-digit run character by character.
            while ((i < a.length && !a[i].isDigit()) || (j < b.length && !b[j].isDigit())) {
                val ca = if (i < a.length && !a[i].isDigit()) a[i] else null
                val cb = if (j < b.length && !b[j].isDigit()) b[j] else null
                val oa = order(ca)
                val ob = order(cb)
                if (oa != ob) return oa.compareTo(ob)
                if (ca != null) i++
                if (cb != null) j++
            }
            // 2) Compare the following digit runs numerically, ignoring zeros.
            while (i < a.length && a[i] == '0') i++
            while (j < b.length && b[j] == '0') j++
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val lenA = i - startA
            val lenB = j - startB
            if (lenA != lenB) return lenA.compareTo(lenB)
            for (k in 0 until lenA) {
                val d = a[startA + k].compareTo(b[startB + k])
                if (d != 0) return d
            }
        }
        return 0
    }

    /**
     * Debian character order: `~` before end-of-string, end-of-string before
     * anything else, letters before non-letters (ASCII order within each class).
     */
    private fun order(c: Char?): Int = when {
        c == null -> 0
        c == '~' -> -1
        c.isLetter() -> c.code
        else -> c.code + 256
    }
}

/**
 * Resolves the transitive `Depends:` closure for a package, dropping packages
 * that are irrelevant or harmful on Android (audio plugins, system CA bundle).
 *
 * The resolver is intentionally strict about the root package and about
 * dependencies that are neither present nor explicitly skipped: a missing
 * shared library is much easier to diagnose here than as a silent JVM crash.
 */
class TermuxDependencyResolver(
    packages: Collection<TermuxPackage>,
    private val skip: Set<String> = DEFAULT_SKIP
) {
    private val byName: Map<String, TermuxPackage> = buildMap {
        for (pkg in packages) {
            val current = get(pkg.name)
            if (current == null || DebianVersion.compare(pkg.version, current.version) > 0) {
                put(pkg.name, pkg)
            }
        }
    }

    private val byProvides: Map<String, TermuxPackage> = buildMap {
        for (pkg in packages) {
            for (virtual in pkg.provides) {
                val name = virtual.substringBefore('(').substringBefore(':').trim()
                if (name.isEmpty()) continue
                val current = get(name)
                if (current == null || DebianVersion.compare(pkg.version, current.version) > 0) {
                    put(name, pkg)
                }
            }
        }
    }

    fun find(name: String): TermuxPackage? = byName[name] ?: byProvides[name]

    /** True when [name] is present in the index (not counting skipped packages). */
    fun isAvailable(name: String): Boolean = name !in skip && find(name) != null

    /**
     * Resolve [root] plus the optional [extras] support packages. The result is
     * topologically friendly (a package always appears after the packages that
     * pulled it in) and free of duplicates.
     */
    fun resolve(
        root: String,
        extras: List<String> = RUNTIME_SUPPORT_PACKAGES
    ): List<TermuxPackage> {
        val rootPackage = find(root)
            ?: throw MissingTermuxPackageException(
                "The package '$root' is not present in this mirror's index. " +
                    "Try another mirror or a different Java major version."
            )
        val ordered = LinkedHashMap<String, TermuxPackage>()

        fun visit(pkg: TermuxPackage, requestedBy: String?) {
            if (ordered.putIfAbsent(pkg.name, pkg) != null) return
            for (group in pkg.depends) {
                val resolvable = group.filter { it.name !in skip }
                val chosen = resolvable.firstOrNull { find(it.name) != null }
                if (chosen == null) {
                    if (resolvable.isEmpty()) continue // the whole group is skipped on purpose
                    val wanted = resolvable.joinToString(" | ") { it.name }
                    throw MissingTermuxPackageException(
                        "Cannot resolve dependency '$wanted' required by '${pkg.name}'" +
                            (requestedBy?.let { " (via $it)" } ?: "") +
                            ". The mirror index looks incomplete; try another mirror."
                    )
                }
                visit(find(chosen.name)!!, pkg.name)
            }
        }

        visit(rootPackage, null)
        for (extra in extras) {
            if (extra in skip) continue
            val pkg = find(extra) ?: continue
            visit(pkg, root)
        }
        return ordered.values.toList()
    }

    companion object {
        /**
         * `ca-certificates` installs a system-wide bundle only used by Termux's
         * own tools; we overlay ca-certificates-java's `cacerts` into the JRE
         * instead. `alsa-plugins` and its (audio-only) dependency tree are
         * useless on Android and would drag in unrelated native libraries.
         */
        val DEFAULT_SKIP: Set<String> = setOf(
            "alsa-plugins",
            "alsa-lib",
            "pulseaudio",
            "libandroid-sysv-semaphore",
            "ca-certificates"
        )

        /**
         * Support packages the JRE links against at runtime. They are normally
         * part of `openjdk-N`'s Depends; listing them again makes installation
         * resilient against a mirror forgetting a dependency.
         */
        val RUNTIME_SUPPORT_PACKAGES: List<String> = listOf(
            "ca-certificates-java",
            "libandroid-shmem",
            "libandroid-spawn",
            "libiconv",
            "zlib",
            "libjpeg-turbo",
            "littlecms"
        )
    }
}
