package com.maragung.arrowide.git

import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Facade over one working copy (plan #10/#11): every operation shells out
 * to the real git binary through [GitProcess] using machine-readable
 * output forms (`--porcelain=v1 -z -b`, the [GitParser.LOG_FORMAT] log
 * format, `--no-color`) and returns [GitOutcome] — repository failures
 * are values, never exceptions, so the Source Control UI can render
 * them as-is.
 *
 * Construction performs no I/O; nothing is spawned until an operation
 * runs. Operations are safe to call from any dispatcher (they internally
 * run on Dispatchers.IO via [executeSuspending]).
 *
 * Identity: when [identityProvider] yields a [GitIdentity] it is passed
 * per-invocation via `-c user.name=… -c user.email=…` (never written to
 * stored config); when it yields null git's own behavior applies — a
 * missing identity makes git fail, and that stderr is surfaced verbatim.
 *
 * Credentials (plan #38): when [credentialsProvider] AND
 * [askpassCacheDir] are both supplied, network operations
 * (fetch/pull/push/clone) inject a temporary GIT_ASKPASS script carrying
 * the looked-up credentials; the token never appears in argv or stored
 * config, and the script is deleted immediately after use.
 *
 * @param repoDir              the working copy directory (need not exist
 *                             yet for [init] and [clone]; every other
 *                             operation errors if it is missing)
 * @param process              git runner
 * @param identityProvider     commit identity, or null to use git's config
 * @param credentialsProvider  remote credential lookup
 * @param askpassCacheDir      directory for temporary askpass scripts
 */
class GitRepository(
    val repoDir: File,
    private val process: GitProcess,
    private val identityProvider: () -> GitIdentity? = { null },
    private val credentialsProvider: CredentialsProvider? = null,
    private val askpassCacheDir: File? = null,
) {

    private val askpass: AskpassCredentialsProvider? =
        if (credentialsProvider != null && askpassCacheDir != null) {
            AskpassCredentialsProvider(credentialsProvider, askpassCacheDir)
        } else {
            null
        }

    // ------------------------------------------------------------------
    // Repository state
    // ------------------------------------------------------------------

    /**
     * Whether [repoDir] lies inside a git work tree
     * (`rev-parse --is-inside-work-tree` exit 0 and "true"). False for a
     * missing directory, a non-repository or a spawn failure — callers
     * wanting the reason use [status] and read its error.
     */
    suspend fun isRepository(): Boolean {
        val result = run(listOf("rev-parse", "--is-inside-work-tree"))
        val ok = result as? GitOutcome.Ok<GitProcessResult> ?: return false
        return ok.value.stdoutText.trim() == "true"
    }

    /**
     * `git init --initial-branch=main`; retries without the flag when the
     * installed git predates 2.28 (older binaries reject the option).
     * Creates [repoDir] when missing.
     */
    suspend fun init(): GitOutcome<Unit> {
        val dirReady = try {
            repoDir.isDirectory || repoDir.mkdirs()
        } catch (e: SecurityException) {
            false
        }
        if (!dirReady) {
            return GitOutcome.Error(-1, "Cannot create repository directory: $repoDir", "")
        }
        val withFlag = run(listOf("init", "--initial-branch=main"))
        if (withFlag is GitOutcome.Ok<*>) return GitOutcome.Ok(Unit)
        // git < 2.28: "error: unknown option `initial-branch'" — retry plain.
        return run(listOf("init")).toUnitOutcome()
    }

    /** Status snapshot (`status --porcelain=v1 -z -b`). */
    suspend fun status(): GitOutcome<GitStatus> {
        missingDirError()?.let { return it }
        return run(listOf("status", "--porcelain=v1", "-z", "-b")).mapValue { result ->
            GitParser.parseStatus(result.stdout)
                ?: return GitOutcome.Error(
                    -1,
                    "Unrecognized git status output",
                    result.stdoutText,
                )
        }
    }

    /**
     * Commit history, newest first (`log -n [maxCount]`).
     *
     * A repository whose current branch has no commits yet yields an
     * empty list (git exits 128 with "does not have any commits yet").
     *
     * @param maxCount maximum number of commits; must be positive
     */
    suspend fun log(maxCount: Int = 100): GitOutcome<List<GitLogEntry>> {
        if (maxCount <= 0) {
            return GitOutcome.Error(-1, "maxCount must be positive: $maxCount", "")
        }
        missingDirError()?.let { return it }
        val result = run(
            listOf(
                "log",
                "-n", maxCount.toString(),
                "--date=raw",
                "--pretty=format:${GitParser.LOG_FORMAT}",
            ),
        )
        return when (result) {
            is GitOutcome.Ok<*> ->
                GitOutcome.Ok(GitParser.parseLog((result.value as GitProcessResult).stdout))

            is GitOutcome.Error ->
                if (result.stderr.contains("does not have any commits yet")) {
                    GitOutcome.Ok(emptyList())
                } else {
                    result
                }
        }
    }

    /** Local and remote branches (`branch -a --no-color`). */
    suspend fun branches(): GitOutcome<GitBranches> {
        missingDirError()?.let { return it }
        return run(listOf("branch", "--no-color", "-a"))
            .mapValue { GitParser.parseBranches(it.stdout) }
    }

    /** Remotes (`remote -v`), URLs credential-stripped for display. */
    suspend fun remotes(): GitOutcome<List<GitRemote>> {
        missingDirError()?.let { return it }
        return run(listOf("remote", "-v"))
            .mapValue { GitParser.parseRemotes(it.stdout) }
    }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    /** Unified diff of staged changes (`diff --cached`), optionally one path. */
    suspend fun diffStaged(path: String? = null): GitOutcome<String> {
        missingDirError()?.let { return it }
        return run(diffArgs(cached = true, path = path))
            .mapValue { it.stdoutText }
    }

    /** Unified diff of unstaged changes (`diff`), optionally one path. */
    suspend fun diffUnstaged(path: String? = null): GitOutcome<String> {
        missingDirError()?.let { return it }
        return run(diffArgs(cached = false, path = path))
            .mapValue { it.stdoutText }
    }

    /** [diffStaged] or [diffUnstaged] for exactly one [path]. */
    suspend fun diffFile(path: String, staged: Boolean): GitOutcome<String> =
        if (staged) diffStaged(path) else diffUnstaged(path)

    private fun diffArgs(cached: Boolean, path: String?): List<String> =
        buildList {
            add("diff")
            add("--no-color")
            if (cached) add("--cached")
            if (path != null) {
                add("--")
                add(path)
            }
        }

    // ------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------

    /**
     * Stages [paths] (`add -- <paths>`); paths are passed as separate
     * argv entries behind `--`, so names starting with `-` stay paths.
     * An empty list is a successful no-op.
     */
    suspend fun stage(paths: List<String>): GitOutcome<Unit> {
        if (paths.isEmpty()) return GitOutcome.Ok(Unit)
        missingDirError()?.let { return it }
        return run(listOf("add", "--") + paths).toUnitOutcome()
    }

    /**
     * Unstages [paths] (`reset -q HEAD -- <paths>`). On a branch with no
     * commits yet HEAD does not resolve and git fails — its stderr is
     * surfaced (the `rm --cached` fallback is deliberately not attempted).
     */
    suspend fun unstage(paths: List<String>): GitOutcome<Unit> {
        if (paths.isEmpty()) return GitOutcome.Ok(Unit)
        missingDirError()?.let { return it }
        return run(listOf("reset", "-q", "HEAD", "--") + paths).toUnitOutcome()
    }

    /** Stages every change, including removals and untracked files (`add -A`). */
    suspend fun stageAll(): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        return run(listOf("add", "-A")).toUnitOutcome()
    }

    // ------------------------------------------------------------------
    // Commit
    // ------------------------------------------------------------------

    /**
     * Commits the staged index.
     *
     * The message ALWAYS goes through stdin (`commit --file=-`) — no argv
     * length limit, no quoting issues. Identity (when the provider yields
     * one) rides along as `-c user.name=… -c user.email=…`. A blank
     * message is rejected locally without spawning git.
     */
    suspend fun commit(message: String): GitOutcome<Unit> {
        if (message.isBlank()) {
            return GitOutcome.Error(-1, "Commit message is empty", "")
        }
        missingDirError()?.let { return it }
        val args = buildList {
            identityProvider()?.let { identity ->
                add("-c")
                add("user.name=${identity.name}")
                add("-c")
                add("user.email=${identity.email}")
            }
            add("commit")
            add("--file=-")
        }
        return run(args, stdin = message.toByteArray(Charsets.UTF_8)).toUnitOutcome()
    }

    // ------------------------------------------------------------------
    // Network (credentials via temporary GIT_ASKPASS, plan #38)
    // ------------------------------------------------------------------

    /** Fetches [remote] (`fetch <remote>`). */
    suspend fun fetch(remote: String = "origin"): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        val url = askpass?.let { remoteUrl(remote, forPush = false) }
        return runNetwork(listOf("fetch", remote), url).toUnitOutcome()
    }

    /**
     * Pulls [remote] into the current branch (`pull <remote> [<branch>]`);
     * [branch] null lets git use the upstream configuration.
     */
    suspend fun pull(
        remote: String = "origin",
        branch: String? = null,
    ): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        val url = askpass?.let { remoteUrl(remote, forPush = false) }
        val args = buildList {
            add("pull")
            add(remote)
            branch?.let { add(it) }
        }
        return runNetwork(args, url).toUnitOutcome()
    }

    /**
     * Pushes to [remote] (`push [--set-upstream] <remote> [<branch>]`);
     * [branch] null lets git use `push.default` (simple: the current
     * branch to its upstream).
     */
    suspend fun push(
        remote: String = "origin",
        branch: String? = null,
        setUpstream: Boolean = false,
    ): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        val url = askpass?.let { remoteUrl(remote, forPush = true) }
        val args = buildList {
            add("push")
            if (setUpstream) add("--set-upstream")
            add(remote)
            branch?.let { add(it) }
        }
        return runNetwork(args, url).toUnitOutcome()
    }

    /**
     * Clones [url] into [destDir] (`git clone <url> <dir>`) and wires the
     * askpass credentials for the URL when a provider is set. The argv
     * contains the URL — which never carries the token. [destDir] must
     * not exist or be empty (git enforces this); its parent is created
     * when missing. [repoDir] is not touched.
     */
    suspend fun clone(url: String, destDir: File): GitOutcome<Unit> {
        val parent = destDir.absoluteFile.parentFile
            ?: return GitOutcome.Error(-1, "Clone destination has no parent: $destDir", "")
        val parentReady = try {
            parent.isDirectory || parent.mkdirs()
        } catch (e: SecurityException) {
            false
        }
        if (!parentReady) {
            return GitOutcome.Error(-1, "Cannot create clone parent directory: $parent", "")
        }
        return runNetwork(
            listOf("clone", url, destDir.absolutePath),
            url,
            cwd = parent,
        ).toUnitOutcome()
    }

    // ------------------------------------------------------------------
    // Branches / stash
    // ------------------------------------------------------------------

    /** Checks out an existing [branch]. */
    suspend fun checkout(branch: String): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        return run(listOf("checkout", branch)).toUnitOutcome()
    }

    /**
     * Creates [name]; checks it out (`checkout -b`) unless [checkout] is
     * false (`branch`).
     */
    suspend fun createBranch(name: String, checkout: Boolean = true): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        val args = if (checkout) listOf("checkout", "-b", name) else listOf("branch", name)
        return run(args).toUnitOutcome()
    }

    /** Stashes local changes (`stash push [-m <message>]`). */
    suspend fun stashPush(message: String? = null): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        val args = buildList {
            add("stash")
            add("push")
            message?.let {
                add("-m")
                add(it)
            }
        }
        return run(args).toUnitOutcome()
    }

    /** Applies and drops the newest stash entry (`stash pop`). */
    suspend fun stashPop(): GitOutcome<Unit> {
        missingDirError()?.let { return it }
        return run(listOf("stash", "pop")).toUnitOutcome()
    }

    /** Stash entries as their `stash list` description lines, newest first. */
    suspend fun stashList(): GitOutcome<List<String>> {
        missingDirError()?.let { return it }
        return run(listOf("stash", "list"))
            .mapValue { result ->
                result.stdoutText.lines().filter { it.isNotBlank() }
            }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Error for a missing [repoDir], or null when it exists. */
    private fun missingDirError(): GitOutcome.Error? =
        if (repoDir.isDirectory) {
            null
        } else {
            GitOutcome.Error(-1, "Repository directory does not exist: $repoDir", "")
        }

    /**
     * Resolves a remote's URL for credential lookup (`remote get-url
     * [--push]`). Runs only when credentials are wired; null means "no
     * URL — run without askpass" and lets git report the real problem.
     */
    private suspend fun remoteUrl(remote: String, forPush: Boolean): String? {
        val args = buildList {
            add("remote")
            add("get-url")
            if (forPush) add("--push")
            add(remote)
        }
        val result = run(args)
        val ok = result as? GitOutcome.Ok<GitProcessResult> ?: return null
        return ok.value.stdoutText.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Runs a network command with askpass credentials injected for [url]
     * when both a provider and a URL are available.
     */
    private suspend fun runNetwork(
        args: List<String>,
        url: String?,
        cwd: File = repoDir,
    ): GitOutcome<GitProcessResult> {
        val provider = askpass ?: return run(args, cwd = cwd)
        if (url == null) return run(args, cwd = cwd)
        return provider.withAskpassEnv(url) { env ->
            run(args, cwd = cwd, extraEnv = env)
        }
    }

    /**
     * Runs git in [cwd] (default [repoDir]) and maps every failure —
     * non-zero exit OR inability to spawn the process — to
     * [GitOutcome.Error]. Only cancellation propagates.
     */
    private suspend fun run(
        args: List<String>,
        cwd: File = repoDir,
        extraEnv: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
    ): GitOutcome<GitProcessResult> = try {
        process.executeSuspending(args, cwd, extraEnv, stdin).let { result ->
            if (result.exitCode == 0) {
                GitOutcome.Ok(result)
            } else {
                GitOutcome.Error(
                    result.exitCode,
                    result.stderrText.trim(),
                    result.stdoutText.trim(),
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GitOutcome.Error(
            -1,
            "Failed to run git: ${e.message ?: e.javaClass.simpleName}",
            "",
        )
    }

    /** Success keeps [GitProcessResult]; failure becomes a Unit error. */
    private fun GitOutcome<GitProcessResult>.toUnitOutcome(): GitOutcome<Unit> =
        when (this) {
            is GitOutcome.Ok<*> -> GitOutcome.Ok(Unit)
            is GitOutcome.Error -> this
        }

    /** Maps a successful result's stdout via [value]; passes errors through. */
    private inline fun <T> GitOutcome<GitProcessResult>.mapValue(
        value: (GitProcessResult) -> T,
    ): GitOutcome<T> = when (val result = this) {
        is GitOutcome.Ok<*> -> GitOutcome.Ok(value(result.value as GitProcessResult))
        is GitOutcome.Error -> result
    }
}
