package com.maragung.arrowide.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests against byte-exact samples captured from real git 2.47
 * (`status --porcelain=v1 -z -b`, the LOG_FORMAT log, `branch -a`,
 * `remote -v`). No git binary needed at test time.
 */
class GitParserTest {

    // ---- status ----------------------------------------------------------------

    @Test
    fun parseStatus_cleanRepository() {
        val status = GitParser.parseStatus("## main\u0000".toByteArray())!!

        assertEquals("main", status.branch)
        assertNull(status.upstream)
        assertEquals(0, status.ahead)
        assertEquals(0, status.behind)
        assertTrue("no staged", status.staged.isEmpty())
        assertTrue("no unstaged", status.unstaged.isEmpty())
        assertTrue("no untracked", status.untracked.isEmpty())
        assertTrue("no conflicted", status.conflicted.isEmpty())
    }

    @Test
    fun parseStatus_aheadAndBehind() {
        val bytes = "## main...origin/main [ahead 1, behind 2]\u0000".toByteArray()
        val status = GitParser.parseStatus(bytes)!!

        assertEquals("main", status.branch)
        assertEquals("origin/main", status.upstream)
        assertEquals(1, status.ahead)
        assertEquals(2, status.behind)
    }

    @Test
    fun parseStatus_aheadOnly() {
        val status = GitParser.parseStatus(
            "## main...origin/main [ahead 3]\u0000".toByteArray(),
        )!!

        assertEquals(3, status.ahead)
        assertEquals(0, status.behind)
    }

    @Test
    fun parseStatus_inSyncWithUpstream() {
        val status = GitParser.parseStatus(
            "## develop...origin/develop\u0000".toByteArray(),
        )!!

        assertEquals("develop", status.branch)
        assertEquals("origin/develop", status.upstream)
        assertEquals(0, status.ahead)
        assertEquals(0, status.behind)
    }

    @Test
    fun parseStatus_unbornBranch() {
        val status = GitParser.parseStatus(
            "## No commits yet on main\u0000".toByteArray(),
        )!!

        assertEquals("main", status.branch)
        assertNull(status.upstream)
        assertEquals(0, status.ahead)
        assertEquals(0, status.behind)
    }

    @Test
    fun parseStatus_detachedHead() {
        val status = GitParser.parseStatus(
            "## HEAD (no branch)\u0000".toByteArray(),
        )!!

        assertNull(status.branch)
        assertNull(status.upstream)
    }

    @Test
    fun parseStatus_stagedModifiedAndUntracked() {
        // Captured: one staged modification plus an untracked file.
        val bytes = "## main...origin/main [ahead 1]\u0000M  a.txt\u0000?? d.txt\u0000".toByteArray()
        val status = GitParser.parseStatus(bytes)!!

        assertEquals(listOf(GitStatusEntry("a.txt", 'M', null)), status.staged)
        assertTrue(status.unstaged.isEmpty())
        assertEquals(listOf("d.txt"), status.untracked)
    }

    @Test
    fun parseStatus_stagedAndUnstagedSameFile() {
        val bytes = "## main\u0000MM x.txt\u0000".toByteArray()
        val status = GitParser.parseStatus(bytes)!!

        assertEquals(listOf(GitStatusEntry("x.txt", 'M', null)), status.staged)
        assertEquals(listOf(GitStatusEntry("x.txt", 'M', null)), status.unstaged)
    }

    @Test
    fun parseStatus_renameComesAsTwoRecordsNewPathFirst() {
        // Captured after `git mv a.txt b.txt`: "R  b.txt\0a.txt\0" — the
        // new path is the entry, the original path follows as its own record.
        val bytes = "## main\u0000R  b.txt\u0000a.txt\u0000?? d.txt\u0000".toByteArray()
        val status = GitParser.parseStatus(bytes)!!

        assertEquals(listOf(GitStatusEntry("b.txt", 'R', "a.txt")), status.staged)
        assertEquals(listOf("d.txt"), status.untracked)
    }

    @Test
    fun parseStatus_stagedDeletionAndUnstagedModification() {
        val bytes = "## main\u0000D  gone.txt\u0000 M kept.txt\u0000".toByteArray()
        val status = GitParser.parseStatus(bytes)!!

        assertEquals(listOf(GitStatusEntry("gone.txt", 'D', null)), status.staged)
        assertEquals(listOf(GitStatusEntry("kept.txt", 'M', null)), status.unstaged)
    }

    @Test
    fun parseStatus_unmergedConflict() {
        val bytes =
            "## main...origin/main [ahead 1, behind 1]\u0000UU f.txt\u0000".toByteArray()
        val status = GitParser.parseStatus(bytes)!!

        assertEquals(listOf("f.txt"), status.conflicted)
        assertTrue(status.staged.isEmpty())
        assertTrue(status.unstaged.isEmpty())
    }

    @Test
    fun parseStatus_addedByThemConflictCode() {
        val status = GitParser.parseStatus("## main\u0000AA both.txt\u0000".toByteArray())!!

        assertEquals(listOf("both.txt"), status.conflicted)
    }

    @Test
    fun parseStatus_returnsNullForNonPorcelainOutput() {
        assertNull(GitParser.parseStatus(ByteArray(0)))
        assertNull(GitParser.parseStatus("fatal: not a git repository".toByteArray()))
    }

    // ---- log -------------------------------------------------------------------

    private fun logRecord(
        sha: String,
        shortSha: String,
        author: String,
        email: String,
        date: String,
        subject: String,
        body: String,
    ): String =
        "$sha\u001F$shortSha\u001F$author\u001F$email\u001F$date\u001F$subject\u001F$body\u001E"

    @Test
    fun parseLog_multipleRecordsWithBody() {
        // Shape captured from real git: records end with 0x1E, and format:
        // separates commits with one newline (so later records start \n).
        val bytes = (
            logRecord(
                "f8d1bad2afa9d7b65e76697b74b39c549d45ae7a", "f8d1bad", "A", "a@b.c",
                "1789161990 +0200", "subject line", "body line one\nbody line two\n",
            ) + "\n" +
                logRecord(
                    "cf063c6857aa68d1bbadc3865607634d56f0c432", "cf063c6", "A", "a@b.c",
                    "1789161823 +0200", "mine", "",
                ) + "\n" +
                logRecord(
                    "8fa605d0710965fe8e2efc635401ee6f0785bcba", "8fa605d", "A", "a@b.c",
                    "1789161823 +0200", "base", "",
                )
            ).toByteArray()

        val log = GitParser.parseLog(bytes)

        assertEquals(3, log.size)
        val first = log[0]
        assertEquals("f8d1bad2afa9d7b65e76697b74b39c549d45ae7a", first.sha)
        assertEquals("f8d1bad", first.shortSha)
        assertEquals("A", first.authorName)
        assertEquals("a@b.c", first.authorEmail)
        assertEquals(1789161990L, first.dateEpochSeconds)
        assertEquals("subject line", first.subject)
        assertEquals("body line one\nbody line two", first.body)

        assertEquals("mine", log[1].subject)
        assertEquals("", log[1].body)
        assertEquals("base", log[2].subject)
    }

    @Test
    fun parseLog_emptyInputYieldsNoEntries() {
        assertTrue(GitParser.parseLog(ByteArray(0)).isEmpty())
    }

    @Test
    fun parseLog_trailingSeparatorOnlyIsIgnored() {
        assertTrue(GitParser.parseLog("\u001E".toByteArray()).isEmpty())
    }

    @Test
    fun parseLog_recordWithWrongFieldCountIsSkipped() {
        val bytes = "sha\u001Fshort\u001Eok\u001F1\u001F2\u001F3\u001F4\u001F5\u001F6\u001E".toByteArray()
        val log = GitParser.parseLog(bytes)

        assertEquals(1, log.size)
        assertEquals("ok", log[0].sha)
    }

    @Test
    fun parseLog_malformedDateFallsBackToEpochZero() {
        val bytes = logRecord("s", "s", "A", "a@b.c", "not-a-date", "subj", "").toByteArray()

        assertEquals(0L, GitParser.parseLog(bytes).single().dateEpochSeconds)
    }

    // ---- branches ----------------------------------------------------------------

    @Test
    fun parseBranches_localAndRemoteWithSymbolicHead() {
        // Captured from `git branch -a --no-color` in a clone.
        val bytes = (
            "* main\n" +
                "  develop\n" +
                "+ worktree-branch\n" +
                "  remotes/origin/HEAD -> origin/main\n" +
                "  remotes/origin/main\n" +
                "  remotes/origin/dev\n"
            ).toByteArray()

        val branches = GitParser.parseBranches(bytes)

        assertEquals("main", branches.current)
        assertEquals(listOf("main", "develop", "worktree-branch"), branches.locals)
        assertEquals(listOf("origin/main", "origin/dev"), branches.remotes)
    }

    @Test
    fun parseBranches_detachedHeadHasNoCurrent() {
        val bytes = "* (HEAD detached at abc1234)\n  main\n".toByteArray()

        val branches = GitParser.parseBranches(bytes)

        assertNull(branches.current)
        assertEquals(listOf("main"), branches.locals)
    }

    @Test
    fun parseBranches_legacyNoBranchMarker() {
        val branches = GitParser.parseBranches("* (no branch)\n".toByteArray())

        assertNull(branches.current)
        assertTrue(branches.locals.isEmpty())
    }

    @Test
    fun parseBranches_emptyRepository() {
        val branches = GitParser.parseBranches(ByteArray(0))

        assertNull(branches.current)
        assertTrue(branches.locals.isEmpty())
        assertTrue(branches.remotes.isEmpty())
    }

    // ---- remotes ----------------------------------------------------------------

    @Test
    fun parseRemotes_keepsFetchLinesAndStripsCredentials() {
        val bytes = (
            "origin\thttps://user:pass@example.com/a/b.git (fetch)\n" +
                "origin\thttps://user:pass@example.com/a/b.git (push)\n" +
                "work\thttps://user:secret@github.com/x/y.git (fetch)\n" +
                "work\thttps://user:secret@github.com/x/y.git (push)\n"
            ).toByteArray()

        val remotes = GitParser.parseRemotes(bytes)

        assertEquals(
            listOf(
                GitRemote("origin", "https://example.com/a/b.git"),
                GitRemote("work", "https://github.com/x/y.git"),
            ),
            remotes,
        )
    }

    @Test
    fun parseRemotes_scpStyleUrlsAreKept() {
        val remotes = GitParser.parseRemotes(
            "origin\tgit@github.com:a/b.git (fetch)\n".toByteArray(),
        )

        assertEquals(listOf(GitRemote("origin", "git@github.com:a/b.git")), remotes)
    }

    @Test
    fun parseRemotes_emptyOutput() {
        assertTrue(GitParser.parseRemotes(ByteArray(0)).isEmpty())
    }

    // ---- URL credential stripping --------------------------------------------------

    @Test
    fun stripUrlCredentials_userAndPassword() {
        assertEquals(
            "https://github.com/a/b.git",
            GitParser.stripUrlCredentials("https://alice:secret@github.com/a/b.git"),
        )
    }

    @Test
    fun stripUrlCredentials_tokenAsUser() {
        assertEquals(
            "https://github.com/a/b.git",
            GitParser.stripUrlCredentials("https://ghp123abc@github.com/a/b.git"),
        )
    }

    @Test
    fun stripUrlCredentials_bareUser() {
        assertEquals(
            "http://example.com/x",
            GitParser.stripUrlCredentials("http://alice@example.com/x"),
        )
    }

    @Test
    fun stripUrlCredentials_noUserInfoUnchanged() {
        assertEquals(
            "https://github.com/a/b.git",
            GitParser.stripUrlCredentials("https://github.com/a/b.git"),
        )
    }

    @Test
    fun stripUrlCredentials_scpStyleUnchanged() {
        assertEquals(
            "git@github.com:a/b.git",
            GitParser.stripUrlCredentials("git@github.com:a/b.git"),
        )
    }
}
