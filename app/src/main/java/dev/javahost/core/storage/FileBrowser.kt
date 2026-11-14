package dev.javahost.core.storage

import dev.javahost.core.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Safe file manager for one server instance's working directory.
 *
 * Everything a server can write lives below `files/javahost/instances/<id>/`, so
 * the browser only ever exposes *relative* paths inside that tree. Two rules are
 * enforced on every operation, not just the ones the UI happens to use:
 *
 *  1. no path may contain `..`, be absolute, or carry a Windows drive prefix
 *     (see [SafePaths.normalizeRelative]);
 *  2. the canonical (symlink-resolved) target must stay inside the instance
 *     directory (see [SafePaths.isWithin]) - this blocks a `latest.log -> /data`
 *     style escape even when the link was created outside the app.
 *
 * All disk work runs on [Dispatchers.IO]; failures are returned as
 * `Result.failure(FileAccessException)` with a message suitable for a snackbar.
 */
class FileBrowser(
    private val instances: InstanceStore,
    private val paths: Paths
) {

    /** The instance working directory, or null for an unknown/unsafe id. */
    fun instanceRoot(instanceId: String): File? {
        if (!isSafeInstanceId(instanceId)) return null
        if (instances.get(instanceId) == null) return null
        return paths.workingDir(instanceId)
    }

    /**
     * Cheap, synchronous resolution used for preview/export. Returns null for
     * unknown instances and for paths that would escape the instance directory.
     * The file does not have to exist yet.
     */
    fun resolve(instanceId: String, relativePath: String): File? {
        val base = instanceRoot(instanceId) ?: return null
        val normalized = SafePaths.normalizeRelative(relativePath) ?: return null
        val target = if (normalized.isEmpty()) base else File(base, normalized)
        return target.takeIf { SafePaths.isWithin(base, it) }
    }

    /** Lists a directory (empty list when it does not exist yet). */
    suspend fun list(instanceId: String, relativeDir: String = ""): Result<List<FileEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = instanceRoot(instanceId)
                    ?: throw FileAccessException("Server instance not found")
                val directory = resolveFile(instanceId, base, relativeDir)
                if (!directory.exists()) return@runCatching emptyList()
                if (!directory.isDirectory) {
                    throw FileAccessException("\"${directory.name}\" is not a folder")
                }
                val children = directory.listFiles() ?: emptyArray()
                children.mapNotNull { child ->
                    // A symlink pointing outside the instance is listed as if it
                    // did not exist: reading it must never leak outside files.
                    if (!SafePaths.isWithin(base, child)) return@mapNotNull null
                    FileEntry(
                        name = child.name,
                        relativePath = child.relativeTo(base).invariantSeparatorsPath,
                        isDirectory = child.isDirectory,
                        sizeBytes = if (child.isFile) child.length() else 0L,
                        modifiedAt = child.lastModified()
                    )
                }.sortedWith(
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
            }
        }

    /** Convenience listing for the plugin manager. */
    suspend fun listPlugins(instanceId: String): Result<List<FileEntry>> =
        list(instanceId, "plugins")

    /** Convenience listing for the mod manager. */
    suspend fun listMods(instanceId: String): Result<List<FileEntry>> =
        list(instanceId, "mods")

    /**
     * Reads a text file. Capped at [maxBytes] (1 MB by default) and refuses
     * binary content so the editor cannot be used to corrupt a JAR.
     */
    suspend fun readText(
        instanceId: String,
        relativePath: String,
        maxBytes: Long = MAX_TEXT_BYTES
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (relativePath.isBlank()) throw FileAccessException("Choose a file to open")
            val base = instanceRoot(instanceId)
                ?: throw FileAccessException("Server instance not found")
            val file = resolveFile(instanceId, base, relativePath)
            if (!file.isFile) throw FileAccessException("\"${file.name}\" is not a file")
            val bytes = readAtMost(file, maxBytes)
                ?: throw FileAccessException(
                    "File is too large to open (limit ${formatSize(maxBytes)})"
                )
            if (looksBinary(bytes)) {
                throw FileAccessException("\"${file.name}\" looks like a binary file")
            }
            decodeText(bytes)
        }
    }

    /** Atomically writes UTF-8 text, creating parent directories as needed. */
    suspend fun writeText(
        instanceId: String,
        relativePath: String,
        text: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (relativePath.isBlank()) throw FileAccessException("Choose a file name")
            val base = instanceRoot(instanceId)
                ?: throw FileAccessException("Server instance not found")
            val target = resolveFile(instanceId, base, relativePath)
            if (target.isDirectory) throw FileAccessException("Cannot overwrite a folder")
            val parent = target.parentFile
                ?: throw FileAccessException("Invalid file path")
            if (!parent.exists() && !parent.mkdirs()) {
                throw FileAccessException("Could not create folder \"${parent.name}\"")
            }
            val temp = File(parent, ".${target.name}.tmp-${System.nanoTime()}")
            try {
                temp.writeText(text, Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    target.writeText(text, Charsets.UTF_8)
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            Unit
        }
    }

    /** Deletes a file or directory tree. Refuses to delete the instance root. */
    suspend fun delete(instanceId: String, relativePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (relativePath.isBlank()) {
                    throw FileAccessException("Refusing to delete the server folder itself")
                }
                val base = instanceRoot(instanceId)
                    ?: throw FileAccessException("Server instance not found")
                val target = resolveFile(instanceId, base, relativePath)
                if (target.canonicalFile == base.canonicalFile) {
                    throw FileAccessException("Refusing to delete the server folder itself")
                }
                if (!target.exists()) {
                    throw FileAccessException("\"${target.name}\" no longer exists")
                }
                if (!target.deleteRecursively()) {
                    throw FileAccessException("Could not delete \"${target.name}\"")
                }
                Unit
            }
        }

    /** Renames one entry inside its current directory; returns the new file. */
    suspend fun rename(
        instanceId: String,
        relativePath: String,
        newName: String
    ): Result<File?> = withContext(Dispatchers.IO) {
        runCatching<File?> {
            if (relativePath.isBlank()) {
                throw FileAccessException("Refusing to rename the server folder itself")
            }
            val base = instanceRoot(instanceId)
                ?: throw FileAccessException("Server instance not found")
            val source = resolveFile(instanceId, base, relativePath)
            if (!source.exists()) throw FileAccessException("\"${source.name}\" no longer exists")
            val cleanName = SafePaths.sanitizeFileName(newName, fallback = "")
            if (cleanName.isEmpty()) throw FileAccessException("Enter a valid name")
            val parent = source.parentFile ?: throw FileAccessException("Invalid file path")
            val destination = File(parent, cleanName)
            if (!SafePaths.isWithin(base, destination)) throw FileAccessException("Invalid name")
            if (destination.exists()) throw FileAccessException("\"$cleanName\" already exists")
            if (!source.renameTo(destination)) {
                throw FileAccessException("Could not rename \"${source.name}\"")
            }
            destination
        }
    }

    /** Creates `parentRelative/name` as a directory. */
    suspend fun mkdir(
        instanceId: String,
        parentRelative: String,
        name: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = SafePaths.sanitizeFileName(name, fallback = "")
            if (cleanName.isEmpty()) throw FileAccessException("Enter a folder name")
            val base = instanceRoot(instanceId)
                ?: throw FileAccessException("Server instance not found")
            val parent = resolveFile(instanceId, base, parentRelative)
            if (!parent.exists() && !parent.mkdirs()) {
                throw FileAccessException("Could not create \"$parentRelative\"")
            }
            if (!parent.isDirectory) throw FileAccessException("\"$parentRelative\" is not a folder")
            val directory = File(parent, cleanName)
            if (!SafePaths.isWithin(base, directory)) throw FileAccessException("Invalid name")
            if (directory.exists()) throw FileAccessException("\"$cleanName\" already exists")
            if (!directory.mkdir()) throw FileAccessException("Could not create \"$cleanName\"")
            directory
        }
    }

    /**
     * Imports a SAF [InputStream] into the instance tree. The caller owns (and
     * closes) [input]; this method only reads from it. Existing names are kept
     * by appending " (1)", " (2)", ... so an import never silently overwrites.
     */
    suspend fun importStream(
        instanceId: String,
        targetRelativeDir: String,
        fileName: String,
        input: InputStream
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val base = instanceRoot(instanceId)
                ?: throw FileAccessException("Server instance not found")
            val directory = resolveFile(instanceId, base, targetRelativeDir)
            if (directory.exists() && !directory.isDirectory) {
                throw FileAccessException("\"$targetRelativeDir\" is not a folder")
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw FileAccessException("Could not create the target folder")
            }
            val safeName = SafePaths.sanitizeFileName(fileName)
            val destination = SafePaths.uniqueFile(directory, safeName)
            if (!SafePaths.isWithin(base, destination)) throw FileAccessException("Invalid file name")
            val temp = File(directory, ".import-${System.nanoTime()}.part")
            try {
                temp.outputStream().buffered().use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
                if (!temp.renameTo(destination)) {
                    throw FileAccessException("Could not finish the import")
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
            destination
        }
    }

    /**
     * Streams a file into [destination] (e.g. a SAF output stream) without
     * loading it into memory. The caller owns both streams.
     */
    suspend fun exportTo(
        instanceId: String,
        relativePath: String,
        destination: OutputStream
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val base = instanceRoot(instanceId)
                ?: throw FileAccessException("Server instance not found")
            val file = resolveFile(instanceId, base, relativePath)
            if (!file.isFile) throw FileAccessException("\"${file.name}\" is not a file")
            var written = 0L
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            file.inputStream().buffered().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    destination.write(buffer, 0, read)
                    written += read
                }
            }
            destination.flush()
            written
        }
    }

    /* ------------------------------------------------------------------ */
    /* Internals                                                          */
    /* ------------------------------------------------------------------ */

    private fun resolveFile(instanceId: String, base: File, relativePath: String): File {
        val normalized = SafePaths.normalizeRelative(relativePath)
            ?: throw FileAccessException("Path outside the server folder is not allowed")
        val target = if (normalized.isEmpty()) base else File(base, normalized)
        if (!SafePaths.isWithin(base, target)) {
            throw FileAccessException("Path outside the server folder is not allowed")
        }
        return target
    }

    private fun readAtMost(file: File, maxBytes: Long): ByteArray? {
        if (file.length() > maxBytes) return null
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        val output = java.io.ByteArrayOutputStream(
            minOf(file.length(), 64L * 1024L).toInt().coerceAtLeast(32)
        )
        var total = 0L
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sampleSize = minOf(bytes.size, BINARY_SNIFF_BYTES)
        if (sampleSize == 0) return false
        var control = 0
        for (index in 0 until sampleSize) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 0x00) return true
            if (value < 0x20 &&
                value != 0x09 && // tab
                value != 0x0A && // LF
                value != 0x0D && // CR
                value != 0x0C // form feed
            ) {
                control++
            }
        }
        return control * 100 / sampleSize > 10
    }

    private fun decodeText(bytes: ByteArray): String {
        val offset = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            3
        } else {
            0
        }
        return String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes bytes"
    }

    private fun isSafeInstanceId(instanceId: String): Boolean =
        instanceId.isNotBlank() &&
            instanceId != "." &&
            instanceId != ".." &&
            !instanceId.contains('/') &&
            !instanceId.contains('\\')

    /** Thrown for every rejected/expected filesystem error; message is UI-ready. */
    class FileAccessException(message: String, cause: Throwable? = null) : IOException(message, cause)

    companion object {
        /** Text editor cap: 1 MiB. */
        const val MAX_TEXT_BYTES: Long = 1_048_576L

        private const val BINARY_SNIFF_BYTES = 8 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Pure path rules used by [FileBrowser], extracted so they can be unit tested
 * on a plain JVM (no Android, no Robolectric).
 */
object SafePaths {

    const val MAX_FILE_NAME_LENGTH = 128

    /**
     * Normalizes a *relative* path for use inside an instance directory.
     *
     * Returns null for anything that could escape: absolute POSIX paths, Windows
     * drive paths, NUL bytes and any `..` segment. Dot and empty segments are
     * dropped so `./world//region` becomes `world/region`.
     */
    fun normalizeRelative(relativePath: String): String? {
        if (relativePath.isEmpty()) return ""
        if (relativePath.indexOf('\u0000') >= 0) return null
        val unified = relativePath.replace('\\', '/')
        if (unified.startsWith("/")) return null
        if (unified.length >= 2 && unified[1] == ':') return null
        val segments = unified.split('/')
        val safe = ArrayList<String>(segments.size)
        for (segment in segments) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> return null
                segment.indexOf('\u0000') >= 0 -> return null
                else -> safe.add(segment)
            }
        }
        return safe.joinToString("/")
    }

    /**
     * True when [target]'s canonical (symlink-resolved) path is [base] or lives
     * strictly below it. Using canonical paths is what catches symlink escapes.
     */
    fun isWithin(base: File, target: File): Boolean {
        return try {
            val basePath = base.canonicalFile.path
            val targetPath = target.canonicalFile.path
            targetPath == basePath || targetPath.startsWith(basePath + File.separator)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Sanitizes a display/file name into one safe path segment. Directory
     * components are stripped, control characters and Windows-reserved
     * characters become `_`, and the result is capped at [MAX_FILE_NAME_LENGTH]
     * while keeping the extension. Falls back when nothing usable remains.
     */
    fun sanitizeFileName(displayName: String?, fallback: String = "imported-file"): String {
        val raw = displayName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        var cleaned = buildString(raw.length) {
            for (character in raw) {
                append(if (isUnsafeFileNameChar(character)) '_' else character)
            }
        }.trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." || cleaned.all { it == '.' }) {
            cleaned = fallback
        }
        if (cleaned.length > MAX_FILE_NAME_LENGTH) {
            val dot = cleaned.lastIndexOf('.')
            cleaned = if (dot in 1 until cleaned.length - 1) {
                val extension = cleaned.substring(dot).take(16)
                cleaned.substring(0, MAX_FILE_NAME_LENGTH - extension.length)
                    .trimEnd('.', ' ') + extension
            } else {
                cleaned.take(MAX_FILE_NAME_LENGTH)
            }
            if (cleaned.isEmpty()) cleaned = fallback
        }
        return cleaned
    }

    /** Returns a non-existing file name in [directory], adding " (n)" if needed. */
    fun uniqueFile(directory: File, fileName: String): File {
        val candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate
        val dot = fileName.lastIndexOf('.')
        val hasExtension = dot in 1 until fileName.length - 1
        val stem = if (hasExtension) fileName.substring(0, dot) else fileName
        val extension = if (hasExtension) fileName.substring(dot) else ""
        for (index in 1..999) {
            val next = File(directory, "$stem ($index)$extension")
            if (!next.exists()) return next
        }
        return File(directory, "$stem-${System.currentTimeMillis()}$extension")
    }

    private fun isUnsafeFileNameChar(character: Char): Boolean =
        character == '/' ||
            character == '\\' ||
            character == ':' ||
            character == '*' ||
            character == '?' ||
            character == '"' ||
            character == '<' ||
            character == '>' ||
            character == '|' ||
            character.code < 0x20 ||
            character.code == 0x7F
}
