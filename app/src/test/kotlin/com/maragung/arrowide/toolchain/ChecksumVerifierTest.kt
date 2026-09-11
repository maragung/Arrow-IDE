package com.maragung.arrowide.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChecksumVerifierTest {

    // sha256("hello")
    private val helloSha256 =
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun tempFile(content: String): File {
        val file = Files.createTempFile("arrow-sum", ".bin").toFile()
        file.writeText(content)
        return file
    }

    @Test
    fun computesKnownSha256() {
        assertEquals(helloSha256, ChecksumVerifier.sha256Hex(tempFile("hello")))
    }

    @Test
    fun verificationIsCaseInsensitive() {
        ChecksumVerifier.verify(tempFile("hello"), helloSha256.uppercase())
    }

    @Test
    fun matchingChecksumPasses() {
        ChecksumVerifier.verify(tempFile("hello"), helloSha256)
    }

    @Test
    fun mismatchThrows() {
        val file = tempFile("hello")
        try {
            ChecksumVerifier.verify(file, "0".repeat(64))
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("mismatch"))
        }
    }

    @Test
    fun emptyExpectedChecksumIsRejected() {
        try {
            ChecksumVerifier.verify(tempFile("hello"), "")
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("no SHA-256"))
        }
    }
}
