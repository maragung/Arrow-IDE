package com.maragung.arrowide.codebase

import com.maragung.arrowide.buildsystem.BuildSystemDetector
import com.maragung.arrowide.git.CredentialsProvider
import com.maragung.arrowide.git.FakeGitProcess
import com.maragung.arrowide.git.GitCredentials
import com.maragung.arrowide.git.GitService
import com.maragung.arrowide.toolchain.PackageDownloader
import com.maragung.arrowide.toolchain.PackageRepoClient
import com.maragung.arrowide.toolchain.RepoPackage
import com.maragung.arrowide.toolchain.ToolchainEnvironment
import com.maragung.arrowide.toolchain.ToolchainManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [CodebaseStatusProvider] against a scripted [FakeGitProcess], an empty
 * package repository and the real [BuildSystemDetector] on temporary
 * folders: the immediate-then-gathered emission contract, honest nulls for
 * non-git workspaces / git failures / missing tools (plan #31, #49). No
 * git binary, no network, no interpreter is executed.
 */
class CodebaseStatusProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FixedProvider : CredentialsProvider {
        override fun credentialsForUrl(url: String): GitCredentials? = null
    }

    private class EmptyRepoClient : PackageRepoClient {
        override val baseUrls = listOf("https://repo.fake/termux-main")

        override suspend fun loadIndex(arch: String): List<RepoPackage> = emptyList()
    }

    private class NoopDownloader : PackageDownloader {
        override suspend fun download(
            url: String,
            dest: File,
            onProgress: suspend (bytesRead: Long, total: Long?) -> Unit,
        ) = Unit
    }

    /** A manager over an empty index; [refresh] makes isAvailable == false. */
    private fun toolchainManager(): ToolchainManager = ToolchainManager(
        environment = ToolchainEnvironment(
            prefixDir = File(tmp.root, "usr"),
            cacheDir = File(tmp.root, "cache"),
            arch = "aarch64",
        ),
        repoClient = EmptyRepoClient(),
        downloader = NoopDownloader(),
        ioDispatcher = Dispatchers.IO,
    )

    /**
     * A provider over the scripted git process, a manager and a version
     * seam answering from [versions]; the second return value records the
     * tool ids the provider actually probed (both probes run concurrently,
     * so it is a concurrent collection and its order is unspecified).
     */
    private fun harness(
        git: FakeGitProcess = FakeGitProcess(),
        versions: Map<String, String> = emptyMap(),
        manager: ToolchainManager = toolchainManager(),
    ): Pair<CodebaseStatusProvider, ConcurrentLinkedQueue<String>> {
        val versionCalls = ConcurrentLinkedQueue<String>()
        val provider = CodebaseStatusProvider(
            gitService = GitService(
                process = git,
                homeDir = tmp.newFolder(),
                askpassCacheDir = tmp.newFolder(),
                identityProvider = { null },
                credentialsProvider = FixedProvider(),
            ),
            toolchainManager = manager,
            buildSystemDetector = BuildSystemDetector(),
            runVersion = { toolId ->
                versionCalls += toolId
                versions[toolId]
            },
            ioDispatcher = Dispatchers.IO,
        )
        return provider to versionCalls
    }

    private fun workspace(name: String): File =
        File(tmp.root, name).apply { mkdirs() }

    // ---- full facts ---------------------------------------------------------

    @Test
    fun emitsImmediateFactsThenTheGatheredOnes() = runBlocking {
        val ws = workspace("MyApp")
        File(ws, "package.json").writeText(
            """{"name":"my-app","version":"1.2.3","scripts":{"build":"vite build"}}""",
        )
        val git = FakeGitProcess()
        // The provider probes the branch first, then the status.
        git.enqueueSuccess("* main\n  feature\n")
        git.enqueueSuccess(
            "## main...origin/main\u0000" +
                "M  a.txt\u0000" +
                " M b.txt\u0000" +
                "?? n.txt\u0000" +
                "UU c.txt\u0000",
        )
        val (provider, _) = harness(
            git,
            versions = mapOf(
                "nodejs" to "v22.11.0\n", // trailing newline: provider trims
                "python" to "Python 3.12.1",
            ),
        )

        val emissions = provider.status(ws).toList()

        assertEquals(2, emissions.size)
        val immediate = emissions[0]
        assertEquals(ws.absolutePath, immediate.workspacePath)
        assertEquals("MyApp", immediate.projectName)
        assertNull(immediate.gitBranch)
        assertNull(immediate.gitDirtyCount)
        assertNull(immediate.buildSystemName)
        assertNull(immediate.nodeVersion)
        assertNull(immediate.pythonVersion)

        val gathered = emissions[1]
        assertEquals(ws.absolutePath, gathered.workspacePath)
        assertEquals("MyApp", gathered.projectName)
        assertEquals("main", gathered.gitBranch)
        assertEquals("staged + unstaged + untracked + conflicted", 4, gathered.gitDirtyCount)
        assertEquals("Node.js", gathered.buildSystemName)
        assertEquals("v22.11.0", gathered.nodeVersion)
        assertEquals("Python 3.12.1", gathered.pythonVersion)
    }

    // ---- honest nulls: git ---------------------------------------------------

    @Test
    fun nonGitWorkspaceYieldsNullGitFactsNotErrors() = runBlocking {
        val ws = workspace("plain")
        val git = FakeGitProcess()
        git.enqueueError(128, "fatal: not a git repository (or any of the parent directories): .git")
        git.enqueueError(128, "fatal: not a git repository (or any of the parent directories): .git")
        val (provider, _) = harness(git)

        val gathered = provider.status(ws).toList().last()

        assertNull(gathered.gitBranch)
        assertNull(gathered.gitDirtyCount)
        // The facts that do not need git still surface.
        assertEquals("plain", gathered.projectName)
        assertNull(gathered.buildSystemName) // no marker file either
    }

    @Test
    fun gitSpawnFailureIsNullsNotACrash() = runBlocking {
        val ws = workspace("spawny")
        val git = FakeGitProcess()
        git.failure = IOException("Cannot run program \"git\"")
        val (provider, _) = harness(git)

        val gathered = provider.status(ws).toList().last()

        assertNull(gathered.gitBranch)
        assertNull(gathered.gitDirtyCount)
        assertEquals("spawny", gathered.projectName)
    }

    @Test
    fun detachedHeadReportsNullBranchButADirtyCount() = runBlocking {
        val ws = workspace("detached")
        val git = FakeGitProcess()
        git.enqueueSuccess("* (HEAD detached at abc1234)\n")
        git.enqueueSuccess("## HEAD (no branch)\u0000?? new.txt\u0000")
        val (provider, _) = harness(git)

        val gathered = provider.status(ws).toList().last()

        assertNull(gathered.gitBranch)
        assertEquals(1, gathered.gitDirtyCount)
    }

    // ---- honest nulls: tools -------------------------------------------------

    @Test
    fun missingToolsReportNullVersions() = runBlocking {
        val ws = workspace("bare")
        val (provider, versionCalls) = harness(versions = emptyMap())

        val gathered = provider.status(ws).toList().last()

        assertNull(gathered.nodeVersion)
        assertNull(gathered.pythonVersion)
        // Both tools were honestly probed; the seam reported "not there".
        // (The probes run concurrently, so the call ORDER is not asserted.)
        assertEquals(setOf("nodejs", "python"), versionCalls.toSet())
        assertEquals(2, versionCalls.size)
    }

    @Test
    fun toolsUnavailableInTheRepositoryAreNotProbed() = runBlocking {
        val ws = workspace("noarch")
        val manager = toolchainManager()
        manager.refresh() // empty index -> isAvailable == false
        val (provider, versionCalls) = harness(
            versions = mapOf("nodejs" to "v22.11.0"),
            manager = manager,
        )

        val gathered = provider.status(ws).toList().last()

        assertNull(gathered.nodeVersion)
        assertNull(gathered.pythonVersion)
        assertTrue("runVersion never invoked", versionCalls.isEmpty())
    }

    // ---- workspace / build system -------------------------------------------

    @Test
    fun nullWorkspaceEmitsOneAllNullStatus() = runBlocking {
        val (provider, versionCalls) = harness()

        val emissions = provider.status(null).toList()

        assertEquals(1, emissions.size)
        val status = emissions.single()
        assertNull(status.workspacePath)
        assertNull(status.projectName)
        assertNull(status.gitBranch)
        assertNull(status.gitDirtyCount)
        assertNull(status.buildSystemName)
        assertNull(status.nodeVersion)
        assertNull(status.pythonVersion)
        assertTrue("nothing probed without a workspace", versionCalls.isEmpty())
    }

    @Test
    fun buildSystemNamesPassThroughAndJoinMultipleMarkers() = runBlocking {
        val ws = workspace("mixed")
        File(ws, "requirements.txt").writeText("flask\n")
        File(ws, "Makefile").writeText("build:\n\tgcc main.c\n")

        val git = FakeGitProcess() // no enqueued output -> not-a-repo nulls
        val (provider, _) = harness(git)

        val gathered = provider.status(ws).toList().last()

        // Detection order (plan #24): PYTHON before MAKE.
        assertEquals("Python, Make", gathered.buildSystemName)
    }
}
