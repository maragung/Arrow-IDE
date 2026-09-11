package com.maragung.arrowide.git

/**
 * Snapshot of `git status --porcelain=v1 -z -b` (parsed by
 * [GitParser.parseStatus]).
 *
 * @param branch    current branch name; null when detached
 *                  (`## HEAD (no branch)`)
 * @param upstream  upstream short ref (e.g. `origin/main`); null when the
 *                  branch has none or the repo has no commits yet
 * @param ahead     commits on [branch] not on [upstream]
 * @param behind    commits on [upstream] not on [branch]
 * @param staged    index changes vs HEAD
 * @param unstaged  worktree changes vs index
 * @param untracked untracked paths
 * @param conflicted paths with unresolved merge conflicts (UU, AA, ...)
 */
data class GitStatus(
    val branch: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val staged: List<GitStatusEntry>,
    val unstaged: List<GitStatusEntry>,
    val untracked: List<String>,
    val conflicted: List<String>,
)

/**
 * One changed path.
 *
 * @param path        path relative to the repository root
 * @param statusCode  porcelain status letter for this list ('M', 'A', 'D',
 *                    'R', 'C', 'U', ...); a path changed both staged and
 *                    unstaged appears in both lists with its respective code
 * @param renamedFrom original path for renames/copies, else null
 */
data class GitStatusEntry(
    val path: String,
    val statusCode: Char,
    val renamedFrom: String?,
)

/** One commit from `git log` (parsed by [GitParser.parseLog]). */
data class GitLogEntry(
    val sha: String,
    val shortSha: String,
    val authorName: String,
    val authorEmail: String,
    val dateEpochSeconds: Long,
    val subject: String,
    val body: String,
)

/** Branch list from `git branch -a --no-color` (parsed by [GitParser.parseBranches]). */
data class GitBranches(
    /** Current branch; null when HEAD is detached. */
    val current: String?,
    val locals: List<String>,
    /** Remote branches as `remote/branch` (e.g. `origin/main`). */
    val remotes: List<String>,
)

/**
 * One remote from `git remote -v`. [url] always has userinfo credentials
 * stripped (plan #38) — `https://token@github.com/...` never survives
 * into UI-visible data.
 */
data class GitRemote(
    val name: String,
    val url: String,
)

/** Commit identity (`user.name` / `user.email`). */
data class GitIdentity(
    val name: String,
    val email: String,
)

/**
 * Result of a repository operation: errors are VALUES, never exceptions,
 * so the UI can render them directly (plan #11). [Error.exitCode] is the
 * git exit code, or -1 when git could not be run or its output could not
 * be parsed (local failures — the message says which).
 */
sealed interface GitOutcome<out T> {

    data class Ok<T>(val value: T) : GitOutcome<T>

    data class Error(
        val exitCode: Int,
        val stderr: String,
        val stdout: String,
    ) : GitOutcome<Nothing> {

        /** First non-empty stderr line, for snackbars and logs. */
        val message: String
            get() = stderr.lineSequence().firstOrNull { it.isNotBlank() }
                ?: "git exited with code $exitCode"
    }
}
