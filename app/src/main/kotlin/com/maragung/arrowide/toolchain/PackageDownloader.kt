package com.maragung.arrowide.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * Downloads a file to a destination path. Kept as an interface so the
 * installer is unit-testable with a fake that serves prebuilt synthetic
 * packages.
 */
interface PackageDownloader {

    /**
     * Downloads [url] into [dest] (fully written, rename-visible only on
     * success).
     *
     * @param onProgress invoked as bytes arrive with the number of bytes
     *        read so far and the total size when the server sent
     *        `Content-Length` (null otherwise)
     * @throws IOException on any network or disk failure; the destination
     *         is left untouched and the partial file removed
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: suspend (bytesRead: Long, total: Long?) -> Unit,
    )
}

/**
 * Real [PackageDownloader] on top of [HttpURLConnection]. All I/O runs on
 * [Dispatchers.IO]; a cancelled coroutine aborts the copy loop at the next
 * buffer boundary and the partial download is deleted.
 *
 * @param connectTimeoutMs TCP connect timeout
 * @param readTimeoutMs    socket read timeout; also bounds how long a
 *                         cancelled download can keep blocking
 */
class HttpPackageDownloader(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) : PackageDownloader {

    companion object {
        private const val USER_AGENT = "ArrowIDE/0.1"
        private const val BUFFER_BYTES = 64 * 1024

        /** Progress callbacks are throttled to one per this many bytes. */
        private const val PROGRESS_INTERVAL_BYTES = 256 * 1024L
    }

    override suspend fun download(
        url: String,
        dest: File,
        onProgress: suspend (bytesRead: Long, total: Long?) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)

                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP $code downloading ${dest.name}")
                }
                val total = connection.contentLengthLong.takeIf { it >= 0 }

                dest.parentFile?.let { Files.createDirectories(it.toPath()) }
                try {
                    connection.inputStream.use { input ->
                        Files.newOutputStream(tmp.toPath()).use { output ->
                            copyWithProgress(input, output, total, onProgress)
                        }
                    }
                } catch (e: Exception) {
                    // Network error, disk error or coroutine cancellation:
                    // never leave a partial download behind.
                    tmp.delete()
                    throw e
                }
                Files.move(
                    tmp.toPath(),
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                connection.disconnect()
            }
            Unit
        }
    }

    /**
     * Copy loop with a cancellation check per buffer and a throttled
     * progress callback (plus one final callback with the exact byte count).
     */
    private suspend fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long?,
        onProgress: suspend (bytesRead: Long, total: Long?) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var read = 0L
        var lastReported = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            read += n
            if (read - lastReported >= PROGRESS_INTERVAL_BYTES) {
                lastReported = read
                onProgress(read, total)
            }
        }
        onProgress(read, total)
    }
}
