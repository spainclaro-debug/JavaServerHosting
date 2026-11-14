package dev.javahost.core.runtime

import java.io.File

/**
 * Path validation for archive extraction.
 *
 * Debian data tarballs (and user-supplied JRE archives) are untrusted input:
 * an entry named `../../..` or an absolute path must never be written outside
 * the staging directory. All mapping code funnels through these helpers so the
 * security rule lives in exactly one place and is easy to unit test.
 */
object ArchivePaths {

    /** Normalizes an archive entry name: drops `./`, trailing slashes and a leading slash. */
    fun normalizeEntryName(raw: String): String {
        var name = raw.replace('\\', '/')
        while (name.startsWith("./")) name = name.substring(2)
        // An absolute path inside a tar is a red flag; strip it because after
        // normalization it is still confined to the destination directory.
        name = name.trimStart('/')
        if (name == ".") return ""
        return name.trimEnd('/')
    }

    /**
     * Returns true when [relative] contains no `..` segments and is not empty,
     * i.e. it is safe to resolve below a known base directory.
     */
    fun isSafeRelative(relative: String): Boolean {
        if (relative.isEmpty()) return false
        if (relative.startsWith('/')) return false
        val segments = relative.split('/')
        return segments.none { it == ".." || it.isEmpty() } &&
            segments.isNotEmpty() &&
            !relative.contains('\u0000')
    }

    /** True when the path (relative to a package root) is inside [prefix] or equal to it. */
    fun isInsideOrEqual(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith("$prefix/")

    /**
     * Resolve [relative] under [base] and refuse anything that escapes it.
     *
     * Both the textual check and the canonical-path check are performed: the
     * first gives clear errors, the second catches symlinked parents.
     */
    fun resolveChild(base: File, relative: String): File {
        if (!isSafeRelative(relative)) {
            throw ArchiveFormatException(
                "Refusing to extract an unsafe archive entry '$relative'. " +
                    "The archive contains a path traversal attempt."
            )
        }
        val baseCanonical = base.canonicalFile
        val target = File(baseCanonical, relative)
        val targetCanonical = runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }
        val basePath = baseCanonical.path
        val targetPath = targetCanonical.path
        if (targetPath != basePath && !targetPath.startsWith(basePath + File.separator)) {
            throw ArchiveFormatException(
                "Refusing to extract '$relative' outside the staging directory. " +
                    "The archive contains a path traversal attempt."
            )
        }
        return target
    }

    /**
     * Sanitize a symlink target from an archive. Relative targets that stay
     * inside [base] are preserved; absolute or escaping targets return null so
     * the extractor can skip the link instead of creating a dangling/unsafe one.
     */
    fun safeLinkTarget(base: File, linkRelative: String, rawTarget: String): String? {
        if (rawTarget.isEmpty()) return null
        if (rawTarget.startsWith('/') || rawTarget.startsWith('\\')) return null
        if (rawTarget.contains('\u0000')) return null
        val linkFile = File(base, linkRelative).absoluteFile
        val resolved = runCatching {
            File(linkFile.parentFile ?: base, rawTarget).canonicalFile
        }.getOrNull() ?: return null
        val baseCanonical = runCatching { base.canonicalFile }.getOrElse { base.absoluteFile }
        val basePath = baseCanonical.path
        val resolvedPath = resolved.path
        if (resolvedPath != basePath && !resolvedPath.startsWith(basePath + File.separator)) {
            return null
        }
        return rawTarget
    }
}
