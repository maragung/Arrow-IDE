package com.maragung.arrowide.ai

import com.maragung.arrowide.toolchain.PackageDownloader
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * Scripted [PackageDownloader] for installer tests: writes prebuilt bytes
 * to the destination (reporting full progress) or throws when the
 * provider does. No network.
 */
class FakeDownloader(private val bytesProvider: () -> ByteArray) : PackageDownloader {

    /** The last URL handed to [download]. */
    var lastUrl: String? = null
        private set

    override suspend fun download(
        url: String,
        dest: File,
        onProgress: suspend (bytesRead: Long, total: Long?) -> Unit,
    ) {
        lastUrl = url
        val bytes = bytesProvider()
        dest.parentFile?.let { Files.createDirectories(it.toPath()) }
        dest.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
    }
}

/**
 * Builds a real tar.gz in memory from (entryName, content) pairs — the
 * same archive shape the sst/opencode release assets use (a top-level
 * directory containing the `opencode` binary).
 */
fun tarGz(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { gzip ->
        TarArchiveOutputStream(gzip).use { tar ->
            for ((name, content) in entries) {
                val payload = content.toByteArray(Charsets.UTF_8)
                val entry = TarArchiveEntry(name)
                entry.size = payload.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(payload)
                tar.closeArchiveEntry()
            }
        }
    }
    return out.toByteArray()
}

/** A minimal in-memory [Process] fake. */
class FakeProcess : Process() {

    @Volatile
    private var running = true

    /** Marks the process as terminated. */
    fun kill() {
        running = false
    }

    override fun isAlive(): Boolean = running

    override fun exitValue(): Int {
        check(!running) { "process is still running" }
        return 0
    }

    override fun waitFor(): Int {
        while (running) Thread.sleep(5)
        return 0
    }

    override fun destroy() {
        running = false
    }

    override fun destroyForcibly(): Process {
        running = false
        return this
    }

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
}

/**
 * [OpenCodeServerProcess] fake: never spawns anything, counts
 * start/stop calls, reports a scripted output tail and liveness.
 */
class FakeServerProcess(
    binary: File,
    homeDir: File,
    port: Int,
    workingDir: File? = null,
    private val process: Process = FakeProcess(),
    private val tail: String = "",
) : OpenCodeServerProcess(binary, homeDir, port, workingDir) {

    var startCalls = 0
        private set

    var stopCalls = 0
        private set

    override fun start(): Process {
        startCalls++
        return process
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun outputTail(maxChars: Int): String = tail

    override fun stop() {
        stopCalls++
        process.destroy()
    }
}

/**
 * Unwraps an [AiResult.Ok] in one step, failing the test with the error
 * details otherwise (Kotlin requires type arguments on generic casts, so
 * the usual assertTrue-then-cast dance is clumsy here).
 */
fun <T> AiResult<T>.okValue(): T = when (this) {
    is AiResult.Ok -> value
    is AiResult.Error ->
        throw AssertionError("Expected Ok, got Error(statusCode=$statusCode, message='$message')")
}

/** A realistic GET /repos/sst/opencode/releases/latest body. */
const val RELEASE_JSON = """
{
  "tag_name": "v0.6.5",
  "assets": [
    {
      "name": "opencode-linux-arm64-musl.tar.gz",
      "browser_download_url": "https://github.com/sst/opencode/releases/download/v0.6.5/opencode-linux-arm64-musl.tar.gz",
      "size": 12345678
    },
    {
      "name": "opencode-linux-x64-musl.tar.gz",
      "browser_download_url": "https://github.com/sst/opencode/releases/download/v0.6.5/opencode-linux-x64-musl.tar.gz",
      "size": 13579111
    },
    {
      "name": "opencode-windows-x64.zip",
      "browser_download_url": "https://github.com/sst/opencode/releases/download/v0.6.5/opencode-windows-x64.zip",
      "size": 999
    }
  ]
}
"""
