package com.maragung.arrowide.git

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [GitRepository] against a scripted [FakeGitProcess]: argument shapes,
 * stdin/env plumbing, askpass injection (plan #38) and the
 * errors-are-values contract. No git binary, no network.
 */
class GitRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repoDir(): File = File(tmp.root, "repo").apply { mkdirs() }

    private fun repo(
        fake: FakeGitProcess = FakeGitProcess(),
        identity: GitIdentity? = null,
        credentialsProvider: CredentialsProvider? = null,
        askpassCacheDir: File? = null,
    ): Pair<GitRepository, FakeGitProcess> =
        GitRepository(
            repoDir = repoDir(),
            process = fake,
            identityProvider = { identity },
            credentialsProvider = credentialsProvider,
            askpassCacheDir = askpassCacheDir,
        ) to fake

    private fun <T> ok(outcome: GitOutcome<T>): T =
        (outcome as GitOutcome.Ok<T>).value

    private fun error(outcome: GitOutcome<*>): GitOutcome.Error =
        outcome as GitOutcome.Error

    private class FixedProvider(private val credentials: GitCredentials?) : CredentialsProvider {
        override fun credentialsForUrl(url: String): GitCredentials? = credentials
    }

    // ---- repository state ------------------------------------------------------

    @Test
    fun isRepositoryChecksRevParseInsideWorkTree() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess("true\n")

        assertTrue(repo.isRepository())
        assertEquals(listOf("rev-parse", "--is-inside-work-tree"), fake.calls.single().args)
    }

    @Test
    fun isRepositoryIsFalseWhenGitFails() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueError(128, "fatal: not a git repository (or any of the parent directories): .git")

        assertFalse(repo.isRepository())
    }

    @Test
    fun isRepositoryIsFalseWhenOutputIsNotTrue() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess("false\n")

        assertFalse(repo.isRepository())
    }

    @Test
    fun statusRunsPorcelainAndParsesIt() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess(
            "## main...origin/main [ahead 1, behind 2]\u0000M  a.txt\u0000?? n.txt\u0000",
        )

        val status = ok(repo.status())

        assertEquals(listOf("status", "--porcelain=v1", "-z", "-b"), fake.calls.single().args)
        assertEquals("main", status.branch)
        assertEquals("origin/main", status.upstream)
        assertEquals(1, status.ahead)
        assertEquals(2, status.behind)
        assertEquals(listOf(GitStatusEntry("a.txt", 'M', null)), status.staged)
        assertEquals(listOf("n.txt"), status.untracked)
    }

    @Test
    fun statusSurfacesGitErrorsAsValues() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueError(128, "fatal: not a git repository (or any of the parent directories): .git")

        val err = error(repo.status())

        assertEquals(128, err.exitCode)
        assertTrue(err.stderr.contains("not a git repository"))
        assertTrue(err.message.contains("not a git repository"))
    }

    // ---- init -----------------------------------------------------------------

    @Test
    fun initUsesInitialBranchMain() = runBlocking {
        val (repo, fake) = repo()

        assertTrue(repo.init() is GitOutcome.Ok<*>)
        assertEquals(listOf("init", "--initial-branch=main"), fake.calls.single().args)
    }

    @Test
    fun initFallsBackWhenInitialBranchIsRejected() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueError(129, "error: unknown option `initial-branch'")
        fake.enqueueSuccess()

        assertTrue(repo.init() is GitOutcome.Ok<*>)
        assertEquals(listOf("init", "--initial-branch=main"), fake.calls[0].args)
        assertEquals(listOf("init"), fake.calls[1].args)
    }

    @Test
    fun initCreatesTheRepositoryDirectoryWhenMissing() = runBlocking {
        val fresh = File(tmp.root, "fresh")
        val fake = FakeGitProcess()
        val repo = GitRepository(fresh, fake)

        assertTrue(repo.init() is GitOutcome.Ok<*>)
        assertTrue("directory created", fresh.isDirectory)
    }

    // ---- log --------------------------------------------------------------------

    @Test
    fun logRequestsTheParserFormatWithMaxCount() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess(
            "aaaa\u001Faaa\u001FA\u001Fa@b.c\u001F1789161990 +0200\u001Fsubject\u001F\u001E",
        )

        val entries = ok(repo.log(maxCount = 25))

        assertEquals(
            listOf(
                "log", "-n", "25", "--date=raw",
                "--pretty=format:${GitParser.LOG_FORMAT}",
            ),
            fake.calls.single().args,
        )
        assertEquals(1, entries.size)
        assertEquals("aaaa", entries[0].sha)
        assertEquals("subject", entries[0].subject)
        assertEquals(1789161990L, entries[0].dateEpochSeconds)
    }

    @Test
    fun logWithoutCommitsYieldsAnEmptyList() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueError(128, "fatal: your current branch 'main' does not have any commits yet")

        assertTrue(ok(repo.log()).isEmpty())
    }

    @Test
    fun logRejectsNonPositiveMaxCountWithoutSpawning() = runBlocking {
        val (repo, fake) = repo()

        error(repo.log(maxCount = 0))
        assertTrue(fake.calls.isEmpty())
    }

    // ---- branches / remotes --------------------------------------------------------

    @Test
    fun branchesRunsBranchAllNoColor() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess("* main\n  develop\n  remotes/origin/main\n")

        val branches = ok(repo.branches())

        assertEquals(listOf("branch", "--no-color", "-a"), fake.calls.single().args)
        assertEquals("main", branches.current)
        assertEquals(listOf("main", "develop"), branches.locals)
        assertEquals(listOf("origin/main"), branches.remotes)
    }

    @Test
    fun remotesRunsRemoteVerboseAndStripsCredentials() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess(
            "origin\thttps://ghp-token@github.com/a/b.git (fetch)\n" +
                "origin\thttps://ghp-token@github.com/a/b.git (push)\n",
        )

        val remotes = ok(repo.remotes())

        assertEquals(listOf("remote", "-v"), fake.calls.single().args)
        assertEquals(listOf(GitRemote("origin", "https://github.com/a/b.git")), remotes)
    }

    // ---- diff ------------------------------------------------------------------

    @Test
    fun diffStagedUsesCachedAndPathSeparator() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.diffStaged("src/main.kt"))

        assertEquals(
            listOf("diff", "--no-color", "--cached", "--", "src/main.kt"),
            fake.calls.single().args,
        )
    }

    @Test
    fun diffUnstagedWithoutPath() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.diffUnstaged())

        assertEquals(listOf("diff", "--no-color"), fake.calls.single().args)
    }

    @Test
    fun diffFileDelegatesByStagedFlag() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.diffFile("a.txt", staged = true))
        ok(repo.diffFile("a.txt", staged = false))

        assertEquals(
            listOf("diff", "--no-color", "--cached", "--", "a.txt"),
            fake.calls[0].args,
        )
        assertEquals(listOf("diff", "--no-color", "--", "a.txt"), fake.calls[1].args)
    }

    // ---- index -----------------------------------------------------------------

    @Test
    fun stagePassesPathsBehindDoubleDash() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.stage(listOf("a.txt", "b.txt", "-dash-prefixed")))

        assertEquals(
            listOf("add", "--", "a.txt", "b.txt", "-dash-prefixed"),
            fake.calls.single().args,
        )
    }

    @Test
    fun unstageResetsQuietAgainstHeadWithPaths() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.unstage(listOf("a.txt")))

        assertEquals(listOf("reset", "-q", "HEAD", "--", "a.txt"), fake.calls.single().args)
    }

    @Test
    fun stageWithNoPathsIsANoOp() = runBlocking {
        val (repo, fake) = repo()

        assertTrue(ok(repo.stage(emptyList())) == Unit)
        assertTrue("git not spawned", fake.calls.isEmpty())
    }

    @Test
    fun stageAllRunsAddAll() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.stageAll())

        assertEquals(listOf("add", "-A"), fake.calls.single().args)
    }

    // ---- commit ----------------------------------------------------------------

    @Test
    fun commitSendsMessageViaStdinAndIdentityViaConfig() = runBlocking {
        val (repo, fake) = repo(identity = GitIdentity("Alice Dev", "alice@example.com"))
        val message = "Fix the build\n\nLonger explanation.\n"

        ok(repo.commit(message))

        val call = fake.calls.single()
        assertEquals(
            listOf(
                "-c", "user.name=Alice Dev",
                "-c", "user.email=alice@example.com",
                "commit", "--file=-",
            ),
            call.args,
        )
        assertEquals(message, String(call.stdin!!, Charsets.UTF_8))
    }

    @Test
    fun commitWithoutIdentityOmitsConfigFlags() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.commit("message"))

        assertEquals(listOf("commit", "--file=-"), fake.calls.single().args)
    }

    @Test
    fun commitWithBlankMessageFailsWithoutSpawning() = runBlocking {
        val (repo, fake) = repo()

        val err = error(repo.commit("   "))

        assertTrue(err.stderr.contains("empty"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun commitSurfacesGitFailure() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueError(1, "nothing to commit, working tree clean")

        val err = error(repo.commit("msg"))

        assertEquals(1, err.exitCode)
        assertTrue(err.stderr.contains("nothing to commit"))
    }

    // ---- network with askpass (plan #38) -------------------------------------------

    private val remoteUrl = "https://github.com/alice/repo.git"
    private val token = "ghp-secret-token-123"

    @Test
    fun pushInjectsAskpassWhenCredentialsExist() = runBlocking {
        val cache = tmp.newFolder("askpass")
        val (repo, fake) = repo(
            credentialsProvider = FixedProvider(GitCredentials("alice", token)),
            askpassCacheDir = cache,
        )
        fake.enqueueSuccess("$remoteUrl\n") // remote get-url --push
        fake.enqueueSuccess() // push
        var scriptText: String? = null
        fake.onCall = { call ->
            call.env["GIT_ASKPASS"]?.let { scriptText = File(it).readText() }
        }

        ok(repo.push("origin", "main"))

        assertEquals(listOf("remote", "get-url", "--push", "origin"), fake.calls[0].args)
        assertEquals(listOf("push", "origin", "main"), fake.calls[1].args)

        val env = fake.calls[1].env
        assertTrue("GIT_ASKPASS injected", env.containsKey("GIT_ASKPASS"))
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertTrue("script existed during the run", scriptText != null)
        assertTrue(scriptText!!.contains("'alice'"))
        assertTrue(scriptText!!.contains(token))
        assertTrue("script deleted afterwards", cache.listFiles()!!.isEmpty())
    }

    @Test
    fun pushTokenNeverAppearsInArgvOrEnvironment() = runBlocking {
        val cache = tmp.newFolder("askpass")
        val (repo, fake) = repo(
            credentialsProvider = FixedProvider(GitCredentials("alice", token)),
            askpassCacheDir = cache,
        )
        fake.enqueueSuccess("$remoteUrl\n")
        fake.enqueueSuccess()

        ok(repo.push("origin", "main"))

        for (call in fake.calls) {
            assertTrue(
                "token must never appear in argv",
                call.args.none { it.contains(token) },
            )
            assertTrue(
                "token must never appear in environment values",
                call.env.values.none { it.contains(token) },
            )
        }
    }

    @Test
    fun pushWithoutCredentialsForTheUrlSkipsAskpass() = runBlocking {
        val cache = tmp.newFolder("askpass")
        val (repo, fake) = repo(
            credentialsProvider = FixedProvider(null),
            askpassCacheDir = cache,
        )
        fake.enqueueSuccess("$remoteUrl\n")
        fake.enqueueSuccess()

        ok(repo.push("origin", "main"))

        assertTrue(fake.calls[1].env.isEmpty())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun fetchWithoutProviderRunsASingleCommand() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.fetch("origin"))

        assertEquals(listOf("fetch", "origin"), fake.calls.single().args)
        assertTrue(fake.calls.single().env.isEmpty())
    }

    @Test
    fun pushWithSetUpstreamPassesTheFlag() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess()

        ok(repo.push("origin", "main", setUpstream = true))

        assertEquals(listOf("push", "--set-upstream", "origin", "main"), fake.calls.single().args)
    }

    @Test
    fun pullPassesRemoteAndOptionalBranch() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.pull("origin", "develop"))

        assertEquals(listOf("pull", "origin", "develop"), fake.calls.single().args)
    }

    // ---- clone -----------------------------------------------------------------

    @Test
    fun cloneRunsInParentDirectoryWithUrlAndDestination() = runBlocking {
        val (repo, fake) = repo()
        val dest = File(tmp.root, "clone-dest")

        ok(repo.clone(remoteUrl, dest))

        val call = fake.calls.single()
        assertEquals(listOf("clone", remoteUrl, dest.absolutePath), call.args)
        assertEquals(tmp.root, call.cwd)
    }

    @Test
    fun cloneInjectsAskpassForTheCloneUrlWithoutResolvingRemotes() = runBlocking {
        val cache = tmp.newFolder("askpass")
        val (repo, fake) = repo(
            credentialsProvider = FixedProvider(GitCredentials("alice", token)),
            askpassCacheDir = cache,
        )

        ok(repo.clone(remoteUrl, File(tmp.root, "clone-dest")))

        assertEquals("no remote get-url round trip", 1, fake.calls.size)
        assertTrue(fake.calls[0].env.containsKey("GIT_ASKPASS"))
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    // ---- branches / stash -----------------------------------------------------------

    @Test
    fun checkoutAndCreateBranchArgumentShapes() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.checkout("develop"))
        ok(repo.createBranch("feature"))
        ok(repo.createBranch("feature", checkout = false))

        assertEquals(listOf("checkout", "develop"), fake.calls[0].args)
        assertEquals(listOf("checkout", "-b", "feature"), fake.calls[1].args)
        assertEquals(listOf("branch", "feature"), fake.calls[2].args)
    }

    @Test
    fun stashArgumentShapes() = runBlocking {
        val (repo, fake) = repo()

        ok(repo.stashPush())
        ok(repo.stashPush("wip while refactoring"))
        ok(repo.stashPop())

        assertEquals(listOf("stash", "push"), fake.calls[0].args)
        assertEquals(listOf("stash", "push", "-m", "wip while refactoring"), fake.calls[1].args)
        assertEquals(listOf("stash", "pop"), fake.calls[2].args)
    }

    @Test
    fun stashListParsesEntries() = runBlocking {
        val (repo, fake) = repo()
        fake.enqueueSuccess(
            "stash@{0}: WIP on main: abc1234 subject\n" +
                "stash@{1}: On main: other\n",
        )

        val stashes = ok(repo.stashList())

        assertEquals(
            listOf("stash@{0}: WIP on main: abc1234 subject", "stash@{1}: On main: other"),
            stashes,
        )
    }

    // ---- error contract ---------------------------------------------------------------

    @Test
    fun missingRepositoryDirectoryIsAnErrorNotAnException() = runBlocking {
        val missing = File(tmp.root, "does-not-exist")
        val fake = FakeGitProcess()
        val repo = GitRepository(missing, fake)

        val status = error(repo.status())
        val commit = error(repo.commit("msg"))
        val fetch = error(repo.fetch())

        assertTrue(status.stderr.contains("does not exist"))
        assertTrue(commit.stderr.contains("does not exist"))
        assertTrue(fetch.stderr.contains("does not exist"))
        assertTrue("git never spawned", fake.calls.isEmpty())
    }

    @Test
    fun processSpawnFailureIsAnErrorNotAnException() = runBlocking {
        val (repo, fake) = repo()
        fake.failure = IOException("Cannot run program \"git\"")

        val err = error(repo.status())

        assertTrue(err.stderr.contains("Failed to run git"))
        assertTrue(err.stderr.contains("Cannot run program"))
    }

    // ---- GitService --------------------------------------------------------------------

    @Test
    fun gitServiceReportsInstallationStateFromTheBinary() {
        val fake = FakeGitProcess()
        val service = GitService(
            process = fake,
            homeDir = tmp.newFolder("home"),
            askpassCacheDir = tmp.newFolder("askpass"),
            identityProvider = { null },
            credentialsProvider = FixedProvider(null),
        )

        assertFalse("binary does not exist", service.isGitInstalled())

        val installed = FakeGitProcess(gitBinary = tmp.newFile("git"))
        val serviceWithGit = GitService(
            process = installed,
            homeDir = tmp.newFolder("home2"),
            askpassCacheDir = tmp.newFolder("askpass2"),
            identityProvider = { GitIdentity("A", "a@b.c") },
            credentialsProvider = FixedProvider(null),
        )

        assertTrue("binary exists", serviceWithGit.isGitInstalled())
    }

    @Test
    fun gitServiceWiresRepositoriesWithIdentityAndCredentials() = runBlocking {
        val cache = tmp.newFolder("askpass")
        val fake = FakeGitProcess()
        val service = GitService(
            process = fake,
            homeDir = tmp.newFolder("home"),
            askpassCacheDir = cache,
            identityProvider = { GitIdentity("Alice Dev", "alice@example.com") },
            credentialsProvider = FixedProvider(GitCredentials("alice", token)),
        )
        val dir = repoDir()

        val repo = service.repositoryFor(dir)

        fake.enqueueSuccess("$remoteUrl\n")
        ok(repo.push("origin", "main"))
        ok(repo.commit("msg"))

        // Identity and credentials both flow through the service wiring:
        // push ran with the askpass script, commit carried the -c identity.
        assertEquals(listOf("remote", "get-url", "--push", "origin"), fake.calls[0].args)
        assertTrue(fake.calls[1].env.containsKey("GIT_ASKPASS"))
        assertEquals(
            listOf("-c", "user.name=Alice Dev", "-c", "user.email=alice@example.com", "commit", "--file=-"),
            fake.calls[2].args,
        )
    }
}
