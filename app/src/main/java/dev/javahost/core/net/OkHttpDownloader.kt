package dev.javahost.core.net

import dev.javahost.core.api.DownloadException
import dev.javahost.core.api.DownloadResult
import dev.javahost.core.api.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed [Downloader] shared by the JRE installer and the Modrinth client.
 *
 * Design notes:
 *  - All work happens on [Dispatchers.IO]; callers may cancel at any time. The
 *    body is read in chunks and cancellation is checked between chunks, so a
 *    cancel stops the transfer and removes the partial file.
 *  - Downloads land in a sibling `*.part` file and are only renamed into place
 *    after the transfer completed *and* all requested checksums matched. A
 *    cancelled or failed download never leaves a file at [destination].
 *  - Redirects are followed by OkHttp; HTTPS is required (the app always talks
 *    to HTTPS mirrors).
 */
class OkHttpDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = DEFAULT_USER_AGENT
) : Downloader {

    override suspend fun download(
        url: String,
        destination: File,
        expectedSha256: String?,
        expectedSha1: String?,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Refusing to download from a non-HTTPS URL: $url" }
        val parent = destination.absoluteFile.parentFile
            ?: throw DownloadException("Cannot resolve the parent directory for ${destination.path}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw DownloadException("Cannot create download directory ${parent.path}")
        }
        // A sibling temp file guarantees renameTo() stays on the same filesystem,
        // which is what makes the final swap atomic.
        val part = File(parent, destination.name + ".part")
        part.delete()
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        var completed = false
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadException(httpMessage(response.code, url))
                }
                val body = response.body
                    ?: throw DownloadException("Server returned an empty response for $url")
                val total = body.contentLength().coerceAtLeast(0L)
                var done = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                var lastReported = 0L
                val startedAt = System.nanoTime()
                FileOutputStream(part).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            out.write(buffer, 0, read)
                            sha256.update(buffer, 0, read)
                            sha1.update(buffer, 0, read)
                            done += read
                            // Throttle UI updates: at most ~10/s and every 256 KiB.
                            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                            if (done - lastReported >= PROGRESS_BYTES ||
                                elapsedMs >= PROGRESS_INTERVAL_MS
                            ) {
                                lastReported = done
                                onProgress(done, total)
                            }
                        }
                    }
                    out.fd.sync()
                }
                if (total > 0 && done != total) {
                    throw DownloadException(
                        "Incomplete download from $url (received $done of $total bytes). " +
                            "Check the connection and try again."
                    )
                }
                val actualSha256 = sha256.digest().toHex()
                if (expectedSha256 != null && !actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                    throw DownloadException(
                        "SHA-256 mismatch for ${destination.name}: expected ${expectedSha256.trim()}, " +
                            "got $actualSha256. The download may be corrupted; retry or pick another mirror."
                    )
                }
                if (expectedSha1 != null) {
                    val actualSha1 = sha1.digest().toHex()
                    if (!actualSha1.equals(expectedSha1.trim(), ignoreCase = true)) {
                        throw DownloadException(
                            "SHA-1 mismatch for ${destination.name}: expected ${expectedSha1.trim()}, " +
                                "got $actualSha1. The download may be corrupted; retry or pick another mirror."
                        )
                    }
                }
                onProgress(done, if (total > 0) total else done)
                destination.delete()
                if (!part.renameTo(destination)) {
                    // Extremely unlikely on the same directory, but never leave a
                    // `.part` behind: fall back to a copy then remove the temp file.
                    part.copyTo(destination, overwrite = true)
                    if (!part.delete()) {
                        part.deleteOnExit()
                    }
                }
                completed = true
                DownloadResult(file = destination, bytes = done, sha256 = actualSha256)
            }
        } catch (e: IOException) {
            throw if (e is DownloadException) e
            else DownloadException("Download failed for $url: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            if (!completed) {
                part.delete()
            }
        }
    }

    override suspend fun fetchBytes(url: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            val fetchClient = client.newBuilder()
                .callTimeout(FETCH_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            fetchClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw DownloadException(httpMessage(response.code, url))
                }
                val body = response.body
                    ?: throw DownloadException("Server returned an empty response for $url")
                val declared = body.contentLength()
                if (declared > maxBytes) {
                    throw DownloadException(
                        "Response from $url is $declared bytes, larger than the $maxBytes byte limit"
                    )
                }
                val expected = if (declared > 0) declared.toInt() else 16 * 1024
                val out = java.io.ByteArrayOutputStream(expected)
                val buffer = ByteArray(BUFFER_SIZE)
                var done = 0L
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        if (done > maxBytes) {
                            throw DownloadException(
                                "Response from $url exceeded the $maxBytes byte limit"
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw if (e is DownloadException) e
            else DownloadException("Request failed for $url: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override suspend fun fetchText(url: String, maxBytes: Long): String =
        fetchBytes(url, maxBytes).toString(Charsets.UTF_8)

    override suspend fun probe(url: String, timeoutMs: Long): Long? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        val started = System.nanoTime()
        try {
            val probeClient = client.newBuilder()
                .callTimeout(timeoutMs.coerceIn(500L, 30_000L), TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMs.coerceIn(500L, 30_000L), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.coerceIn(500L, 30_000L), TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", userAgent)
                .build()
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
            }
            (System.nanoTime() - started) / 1_000_000L
        } catch (t: Throwable) {
            null
        }
    }

    private fun httpMessage(code: Int, url: String): String = when (code) {
        401, 403 -> "Access denied (HTTP $code) at $url"
        404, 410 -> "Not found (HTTP $code) at $url. The mirror may be out of date; try another mirror."
        416 -> "Range request rejected (HTTP 416) at $url"
        in 500..599 -> "The mirror is having trouble (HTTP $code) at $url. Try again later or pick another mirror."
        else -> "Unexpected HTTP $code from $url"
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_BYTES = 256L * 1024
        private const val PROGRESS_INTERVAL_MS = 100L
        private const val FETCH_CALL_TIMEOUT_SECONDS = 45L
        const val DEFAULT_USER_AGENT = "JavaHost/1.1.6 (Android; general-purpose Java server host)"

        /** Shared client for callers that want connection pooling across features. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        internal fun ByteArray.toHex(): String {
            val chars = CharArray(size * 2)
            val digits = "0123456789abcdef"
            for (i in indices) {
                val b = this[i].toInt() and 0xff
                chars[i * 2] = digits[b ushr 4]
                chars[i * 2 + 1] = digits[b and 0x0f]
            }
            return String(chars)
        }
    }
}
