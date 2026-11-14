package dev.javahost.core.api

import java.io.File
import java.io.IOException

data class DownloadResult(
    val file: File,
    val bytes: Long,
    val sha256: String? = null
)

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal HTTP contract shared by the JRE installer and Modrinth integration.
 * Implementations must verify checksums when expected values are supplied and
 * must be cancellation-cooperative (plain coroutine cancellation is enough).
 */
interface Downloader {
    suspend fun download(
        url: String,
        destination: File,
        expectedSha256: String? = null,
        expectedSha1: String? = null,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> }
    ): DownloadResult

    suspend fun fetchBytes(url: String, maxBytes: Long = 16L * 1024 * 1024): ByteArray

    suspend fun fetchText(url: String, maxBytes: Long = 16L * 1024 * 1024): String

    /**
     * Measures a cheap round-trip to [url] (HEAD request) and returns elapsed
     * milliseconds, or null when the host is unreachable/slow. Used to order
     * mirrors for "Automatic (fastest)" downloads; implementations without
     * network access may return null (the default).
     */
    suspend fun probe(url: String, timeoutMs: Long = 5_000L): Long? = null
}
