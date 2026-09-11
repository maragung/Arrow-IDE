package com.maragung.arrowide.toolchain

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Streaming SHA-256 verification of downloaded `.deb` files (plan #7: a
 * tool must never be extracted — let alone executed — before its checksum
 * matches the repository index).
 */
object ChecksumVerifier {

    private const val BUFFER_BYTES = 64 * 1024
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * Computes the lowercase hex SHA-256 of [file], streaming in
     * [BUFFER_BYTES] chunks so arbitrarily large packages never load into
     * memory.
     *
     * @throws IOException when the file cannot be read
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    /**
     * Verifies that [file] hashes to [expectedHex] (case-insensitive).
     *
     * @throws ToolchainException on a mismatch or an unreadable file
     */
    fun verify(file: File, expectedHex: String) {
        if (expectedHex.isBlank()) {
            throw ToolchainException(
                "Repository index has no SHA-256 for ${file.name}; refusing to install"
            )
        }
        val actual = try {
            sha256Hex(file)
        } catch (e: IOException) {
            throw ToolchainException("Cannot read ${file.path} for verification", e)
        }
        if (!actual.equals(expectedHex.trim(), ignoreCase = true)) {
            throw ToolchainException(
                "SHA-256 mismatch for ${file.name}: expected ${expectedHex.trim()}, got $actual"
            )
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
