package com.maragung.arrowide.toolchain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ToolchainManagerTest {

    private val root = Files.createTempDirectory("arrow-mgr").toFile()
    private val prefix = File(root, "usr")
    private val cache = File(root, "cache")

    private val environment = ToolchainEnvironment(prefix, cache, "aarch64")

    private class FakeRepoClient(val packages: List<RepoPackage>) : PackageRepoClient {
        override val baseUrls = listOf("https://repo.fake/termux-main")

        override suspend fun loadIndex(arch: String): List<RepoPackage> = packages
    }

    private class FakeDownloader(private val files: Map<String, File>) : PackageDownloader {

        /** When set, every download parks on it until completed. */
        var gate: CompletableDeferred<Unit>? = null

        val downloadedUrls = mutableListOf<String>()

        override suspend fun download(
            url: String,
            dest: File,
            onProgress: suspend (bytesRead: Long, total: Long?) -> Unit,
        ) {
            gate?.await()
            val source = files[url.substringAfterLast('/')]
                ?: throw IOException("no synthetic package for $url")
            dest.parentFile?.mkdirs()
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            downloadedUrls += url
            onProgress(source.length(), source.length())
        }
    }

    /** Builds a synthetic .deb, returns it plus its matching index entry. */
    private fun packageWith(
        name: String,
        version: String,
        depends: String = "",
        entries: List<SyntheticDeb.Entry>,
        sha256Override: String? = null,
    ): Pair<RepoPackage, File> {
        val deb = SyntheticDeb.build(
            File(root, "$name.deb"),
            entries,
        )
        val pkg = RepoPackage(
            name = name,
            version = version,
            filename = "pool/main/$name/$name.deb",
            sha256 = sha256Override ?: ChecksumVerifier.sha256Hex(deb),
            size = deb.length(),
            depends = depends,
        )
        return pkg to deb
    }

    private fun nodejsEntries(content: String = "node-binary") = listOf(
        SyntheticDeb.dir("./usr/bin/"),
        SyntheticDeb.executable("./usr/bin/node", content),
    )

    private fun managerFor(
        packages: List<RepoPackage>,
        debs: Map<String, File>,
    ): Pair<ToolchainManager, FakeDownloader> {
        val downloader = FakeDownloader(debs)
        val manager = ToolchainManager(
            environment,
            FakeRepoClient(packages),
            downloader,
            Dispatchers.Default,
        )
        return manager to downloader
    }

    private fun toolInfo(manager: ToolchainManager, id: String): ToolInfo =
        manager.tools.value.first { it.id == id }

    @Test
    fun isAvailableIsNullBeforeRefresh() {
        val (manager, _) = managerFor(emptyList(), emptyMap())
        assertNull(manager.isAvailable("nodejs"))
    }

    @Test
    fun refreshExposesAvailabilityAndVersions() = runBlocking {
        val (pkg, _) = packageWith("nodejs", "22.1.0", entries = nodejsEntries())
        val (manager, _) = managerFor(listOf(pkg), mapOf("nodejs.deb" to File(root, "nodejs.deb")))

        manager.refresh()

        assertEquals(java.lang.Boolean.TRUE, manager.isAvailable("nodejs"))
        // Optional tool absent from this architecture's index.
        assertEquals(java.lang.Boolean.FALSE, manager.isAvailable("bun"))
        val info = toolInfo(manager, "nodejs")
        assertEquals(ToolStatus.NOT_INSTALLED, info.status)
        assertEquals("22.1.0", info.availableVersion)
        assertNull(info.installedVersion)
    }

    @Test
    fun installInstallsTheToolAndItsDependencies() = runBlocking {
        val (nodePkg, nodeDeb) =
            packageWith("nodejs", "22.1.0", entries = nodejsEntries())
        val (libPkg, libDeb) = packageWith(
            "libnode",
            "120.0.0",
            entries = listOf(
                SyntheticDeb.dir("./usr/lib/"),
                SyntheticDeb.file("./usr/lib/libnode.so", "shared-lib"),
            ),
        )
        val nodeWithDep = nodePkg.copy(depends = "libnode")
        val (manager, _) = managerFor(
            listOf(nodeWithDep, libPkg),
            mapOf("nodejs.deb" to nodeDeb, "libnode.deb" to libDeb),
        )

        manager.refresh()
        manager.install("nodejs")

        val info = toolInfo(manager, "nodejs")
        assertEquals(ToolStatus.INSTALLED, info.status)
        assertEquals("22.1.0", info.installedVersion)
        assertNull(info.error)

        assertTrue(File(prefix, "bin/node").isFile)
        assertEquals("node-binary", File(prefix, "bin/node").readText())
        assertTrue("dependency is installed too", File(prefix, "lib/libnode.so").isFile)

        // The DONE operation stays visible for the UI.
        assertEquals(ToolPhase.DONE, manager.operations.value["nodejs"]?.phase)
    }

    @Test
    fun operationsFlowShowsPhasesWhileInstalling() = runBlocking {
        val (pkg, deb) = packageWith("nodejs", "22.1.0", entries = nodejsEntries())
        val (manager, downloader) = managerFor(
            listOf(pkg),
            mapOf("nodejs.deb" to deb),
        )
        manager.refresh()
        val gate = CompletableDeferred<Unit>()
        downloader.gate = gate

        withTimeout(10_000) {
            val job = launch { manager.install("nodejs") }

            // While the download is parked on the gate the tool is busy and
            // the DOWNLOADING phase is visible.
            while (manager.operations.value["nodejs"]?.phase != ToolPhase.DOWNLOADING) {
                delay(5)
            }
            assertEquals(ToolStatus.UPDATING_OR_INSTALLING, toolInfo(manager, "nodejs").status)

            gate.complete(Unit)
            job.join()
        }

        assertEquals(ToolStatus.INSTALLED, toolInfo(manager, "nodejs").status)
        assertEquals(ToolPhase.DONE, manager.operations.value["nodejs"]?.phase)
        assertEquals(1f, manager.operations.value["nodejs"]!!.progress)
    }

    @Test
    fun checksumMismatchFailsWithoutInstalling() = runBlocking {
        val (pkg, deb) = packageWith(
            "nodejs",
            "22.1.0",
            entries = nodejsEntries(),
            sha256Override = "0".repeat(64),
        )
        val (manager, _) = managerFor(listOf(pkg), mapOf("nodejs.deb" to deb))

        manager.refresh()
        manager.install("nodejs")

        val info = toolInfo(manager, "nodejs")
        assertEquals(ToolStatus.FAILED, info.status)
        assertNotNull(info.error)
        assertTrue(info.error!!.contains("mismatch"))
        assertFalse("nothing was extracted", File(prefix, "bin/node").exists())
        assertNull("no DONE operation for a failed install", manager.operations.value["nodejs"])
    }

    @Test
    fun missingRepositoryPackageFails() = runBlocking {
        val (manager, _) = managerFor(emptyList(), emptyMap())

        manager.refresh()
        manager.install("nodejs")

        val info = toolInfo(manager, "nodejs")
        assertEquals(ToolStatus.FAILED, info.status)
        assertTrue(info.error!!.contains("nodejs"))
    }

    @Test
    fun removeUninstallsTheTool() = runBlocking {
        val (pkg, deb) = packageWith("nodejs", "22.1.0", entries = nodejsEntries())
        val (manager, _) = managerFor(listOf(pkg), mapOf("nodejs.deb" to deb))

        manager.refresh()
        manager.install("nodejs")
        assertTrue(File(prefix, "bin/node").isFile)

        manager.remove("nodejs")

        assertEquals(ToolStatus.NOT_INSTALLED, toolInfo(manager, "nodejs").status)
        assertFalse(File(prefix, "bin/node").exists())
        assertNull(toolInfo(manager, "nodejs").installedVersion)
    }

    @Test
    fun updateReplacesTheInstalledVersion() = runBlocking {
        val (v1, debV1) = packageWith("nodejs", "22.1.0", entries = nodejsEntries("node-v1"))
        val (manager, _) = managerFor(listOf(v1), mapOf("nodejs.deb" to debV1))
        manager.refresh()
        manager.install("nodejs")
        assertEquals("node-v1", File(prefix, "bin/node").readText())

        val (v2, debV2) = packageWith("nodejs", "22.2.0", entries = nodejsEntries("node-v2"))
        val (manager2, _) = managerFor(listOf(v2), mapOf("nodejs.deb" to debV2))
        // Re-point the same manager's index by simulating a repo update:
        // a second manager instance over the same prefix.
        manager2.refresh()
        manager2.update("nodejs")

        val info = toolInfo(manager2, "nodejs")
        assertEquals(ToolStatus.INSTALLED, info.status)
        assertEquals("22.2.0", info.installedVersion)
        assertEquals("node-v2", File(prefix, "bin/node").readText())
    }

    @Test
    fun repairReinstallsOverABrokenState() = runBlocking {
        val (pkg, deb) = packageWith("nodejs", "22.1.0", entries = nodejsEntries())
        val (manager, _) = managerFor(listOf(pkg), mapOf("nodejs.deb" to deb))
        manager.refresh()
        manager.install("nodejs")

        // Break the installation behind the manager's back.
        File(prefix, "bin/node").delete()

        manager.repair("nodejs")

        val info = toolInfo(manager, "nodejs")
        assertEquals(ToolStatus.INSTALLED, info.status)
        assertTrue(File(prefix, "bin/node").isFile)
    }

    @Test
    fun offlineIndexFailureKeepsPreviousState() = runBlocking {
        val (pkg, deb) = packageWith("nodejs", "22.1.0", entries = nodejsEntries())
        var online = true
        val repo = object : PackageRepoClient {
            override val baseUrls = listOf("https://repo.fake/termux-main")
            override suspend fun loadIndex(arch: String): List<RepoPackage> {
                if (!online) throw IOException("offline")
                return listOf(pkg)
            }
        }
        val manager = ToolchainManager(environment, repo, FakeDownloader(mapOf("nodejs.deb" to deb)), Dispatchers.Default)

        manager.refresh()
        assertEquals(java.lang.Boolean.TRUE, manager.isAvailable("nodejs"))

        online = false
        manager.refresh()

        // The previously loaded index is kept (offline-first, plan #40).
        assertEquals(java.lang.Boolean.TRUE, manager.isAvailable("nodejs"))
    }
}
