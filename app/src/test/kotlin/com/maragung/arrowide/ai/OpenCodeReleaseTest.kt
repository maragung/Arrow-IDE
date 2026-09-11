package com.maragung.arrowide.ai

import com.maragung.arrowide.github.FakeTransport
import com.maragung.arrowide.github.jsonResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Release asset selection for [OpenCodeService.refreshLatestRelease]
 * against a scripted GitHub response: arm64/x64 musl mapping, missing
 * asset, HTTP and network failures, unparseable body. No network.
 */
class OpenCodeReleaseTest {

    private val root = Files.createTempDirectory("arrow-ai-release").toFile()
    private lateinit var fake: FakeTransport

    @Before
    fun setUp() {
        fake = FakeTransport()
    }

    private fun service(archAssetSuffix: String): OpenCodeService =
        OpenCodeService(
            transport = fake,
            binaryDir = File(root, "usr/bin"),
            homeDir = File(root, "home"),
            cacheDir = File(root, "cache"),
            archAssetSuffix = archAssetSuffix,
        )

    @Test
    fun picksArm64MuslAsset() = runBlocking {
        fake.route(RELEASES_URL, jsonResponse(200, RELEASE_JSON))

        val result = service("arm64-musl").refreshLatestRelease()

        assertTrue(result is AiResult.Ok<*>)
        val info = result.okValue()
        assertEquals("v0.6.5", info.tagName)
        assertEquals("opencode-linux-arm64-musl.tar.gz", info.assetName)
        assertEquals(
            "https://github.com/sst/opencode/releases/download/v0.6.5/opencode-linux-arm64-musl.tar.gz",
            info.assetUrl,
        )
        assertEquals(12345678L, info.assetSizeBytes)
    }

    @Test
    fun picksX64MuslAsset() = runBlocking {
        fake.route(RELEASES_URL, jsonResponse(200, RELEASE_JSON))

        val result = service("x64-musl").refreshLatestRelease()

        assertTrue(result is AiResult.Ok<*>)
        val info = result.okValue()
        assertEquals("opencode-linux-x64-musl.tar.gz", info.assetName)
        assertEquals(13579111L, info.assetSizeBytes)
    }

    @Test
    fun unsupportedArchitectureIsAnError() = runBlocking {
        fake.route(RELEASES_URL, jsonResponse(200, RELEASE_JSON))

        val result = service("armv7-musl").refreshLatestRelease()

        assertTrue(result is AiResult.Error)
        val error = result as AiResult.Error
        assertEquals(null, error.statusCode)
        assertTrue(error.message.contains("no asset"))
        assertTrue(error.message.contains("armv7-musl"))
    }

    @Test
    fun httpFailureMapsToStatusError() = runBlocking {
        fake.route(RELEASES_URL, jsonResponse(403, """{"message":"rate limited"}"""))

        val result = service("arm64-musl").refreshLatestRelease()

        assertTrue(result is AiResult.Error)
        assertEquals(403, (result as AiResult.Error).statusCode)
    }

    @Test
    fun networkFailureMapsToNullStatus() = runBlocking {
        fake.fail(RELEASES_URL, IOException("no route to host"))

        val result = service("arm64-musl").refreshLatestRelease()

        assertTrue(result is AiResult.Error)
        val error = result as AiResult.Error
        assertEquals(null, error.statusCode)
        assertTrue(error.message.contains("no route to host"))
    }

    @Test
    fun unparseableBodyIsAnError() = runBlocking {
        fake.route(RELEASES_URL, jsonResponse(200, "<html>not json</html>"))

        val result = service("arm64-musl").refreshLatestRelease()

        assertTrue(result is AiResult.Error)
        val error = result as AiResult.Error
        assertEquals(null, error.statusCode)
        assertTrue(error.message.contains("parse"))
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/sst/opencode/releases/latest"
    }
}
