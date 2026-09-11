package com.maragung.arrowide.git

/**
 * Pure parsers for git's machine-readable output formats. No I/O, no
 * android.* — every format below was verified byte-for-byte against
 * real git (2.47) porcelain output.
 *
 *  - Status: `git status --porcelain=v1 -z -b`
 *    Records are NUL-terminated. The first record is the `## ` header;
 *    entries are `XY <path>` with X = index status, Y = worktree status.
 *    Renames/copies occupy TWO records, new path first, original second:
 *    `R  new.txt\0old.txt\0` (with -z the `->` is dropped and the field
 *    order is reversed compared to the non-z form `R  old -> new`).
 *
 *  - Log: `git log --date=raw --pretty=format:<[LOG_FORMAT]>` (no -z).
 *    `format:` separates commits with a single newline; each record is
 *    terminated by the 0x1E record separator inside the format string,
 *    fields are separated by 0x1F. `--date=raw` renders dates as
 *    `<epoch-seconds> <tz-offset>`.
 *
 *  - Branches: `git branch -a --no-color` (git branch has NO -z mode).
 *    Line prefixes: `* ` current, `+ ` checked out in another worktree,
 *    two spaces otherwise; remotes appear as `remotes/<remote>/<branch>`
 *    and symbolic refs as `remotes/<remote>/HEAD -> <target>`.
 *    Detached HEAD shows as `* (HEAD detached at <sha>)`.
 *
 *  - Remotes: `git remote -v` — `<name>\t<url> (fetch)` / `(push)` lines.
 *    Only fetch lines are kept (each remote appears twice), and any URL
 *    userinfo (`user:pass@`, `token@`) is stripped (plan #38).
 */
object GitParser {

    /** Field separator inside [LOG_FORMAT] (0x1F). */
    private const val FIELD_SEPARATOR = '\u001F'

    /** Record separator emitted by [LOG_FORMAT] (0x1E). */
    private const val RECORD_SEPARATOR = '\u001E'

    /**
     * The pretty format [GitRepository.log] requests. Exposed so callers
     * and the parser can never drift apart.
     */
    const val LOG_FORMAT: String =
        "%H%x1f%h%x1f%an%x1f%ae%x1f%ad%x1f%s%x1f%b%x1e"

    /** Porcelain codes that mark an unmerged (conflicted) path. */
    private val UNMERGED_CODES = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")

    /**
     * Parses `git status --porcelain=v1 -z -b` output.
     *
     * @return null when the bytes are not a porcelain status (no `## `
     *         header) — e.g. an error message git printed instead
     */
    fun parseStatus(bytes: ByteArray): GitStatus? {
        val records = String(bytes, Charsets.UTF_8).split('\u0000')
        val header = records.firstOrNull() ?: return null
        if (!header.startsWith("## ")) return null

        var branch: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val branchInfo = header.removePrefix("## ")
        when {
            branchInfo == "HEAD (no branch)" -> branch = null

            branchInfo.startsWith("No commits yet on ") -> {
                branch = branchInfo.removePrefix("No commits yet on ")
            }

            else -> {
                val tracked = branchInfo.substringBefore(" [")
                if (tracked.contains("...")) {
                    branch = tracked.substringBefore("...")
                    upstream = tracked.substringAfter("...")
                } else {
                    branch = tracked
                }
                val bracketStart = branchInfo.indexOf('[')
                if (bracketStart >= 0) {
                    val bracketEnd = branchInfo.indexOf(']', bracketStart)
                    if (bracketEnd > bracketStart) {
                        for (part in branchInfo
                            .substring(bracketStart + 1, bracketEnd).split(',')) {
                            val token = part.trim()
                            when {
                                token.startsWith("ahead ") ->
                                    ahead = token.removePrefix("ahead ").toIntOrNull() ?: 0

                                token.startsWith("behind ") ->
                                    behind = token.removePrefix("behind ").toIntOrNull() ?: 0
                            }
                        }
                    }
                }
            }
        }

        val staged = mutableListOf<GitStatusEntry>()
        val unstaged = mutableListOf<GitStatusEntry>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<String>()

        var i = 1
        while (i < records.size) {
            val record = records[i]
            i++
            if (record.length < 4) continue // empty or malformed
            val code = record.substring(0, 2)
            val path = record.substring(3)

            // Rename/copy: the original path follows as its own record.
            var renamedFrom: String? = null
            if (code[0] == 'R' || code[0] == 'C' || code[1] == 'R' || code[1] == 'C') {
                if (i < records.size && records[i].isNotEmpty()) {
                    renamedFrom = records[i]
                    i++
                }
            }

            when {
                code in UNMERGED_CODES -> conflicted += path

                code == "??" -> untracked += path

                code == "!!" -> Unit // ignored file (--ignored not used, defensive)

                else -> {
                    if (code[0] != ' ') {
                        staged += GitStatusEntry(path, code[0], renamedFrom)
                    }
                    if (code[1] != ' ') {
                        unstaged += GitStatusEntry(path, code[1], renamedFrom)
                    }
                }
            }
        }

        return GitStatus(
            branch = branch,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
            conflicted = conflicted,
        )
    }

    /**
     * Parses `git log --date=raw --pretty=format:<[LOG_FORMAT]>` output.
     * Records are split on 0x1E; all but the first start with the newline
     * `format:` inserts between commits, which is stripped. Bodies keep
     * their embedded line breaks; the trailing newline before the record
     * separator is dropped.
     */
    fun parseLog(bytes: ByteArray): List<GitLogEntry> {
        if (bytes.isEmpty()) return emptyList()
        return String(bytes, Charsets.UTF_8)
            .split(RECORD_SEPARATOR)
            .map { it.removePrefix("\n") }
            .filter { it.isNotEmpty() }
            .mapNotNull { record ->
                val fields = record.split(FIELD_SEPARATOR)
                if (fields.size != 7) return@mapNotNull null
                val epochSeconds = fields[4].trim().split(' ')
                    .firstOrNull()?.toLongOrNull() ?: 0L
                GitLogEntry(
                    sha = fields[0],
                    shortSha = fields[1],
                    authorName = fields[2],
                    authorEmail = fields[3],
                    dateEpochSeconds = epochSeconds,
                    subject = fields[5],
                    body = fields[6].trimEnd('\n'),
                )
            }
    }

    /**
     * Parses `git branch -a --no-color` output. Detached HEAD yields
     * [GitBranches.current] == null; symbolic remote refs
     * (`remotes/origin/HEAD -> origin/main`) are dropped.
     */
    fun parseBranches(bytes: ByteArray): GitBranches {
        var current: String? = null
        val locals = mutableListOf<String>()
        val remotes = mutableListOf<String>()

        for (raw in String(bytes, Charsets.UTF_8).lines()) {
            if (raw.length < 3) continue
            val prefix = raw.substring(0, 2)
            if (prefix != "* " && prefix != "+ " && prefix != "  ") continue
            val name = raw.substring(2).trim()
            if (name.isEmpty()) continue

            if (isDetachedMarker(name)) continue

            if (prefix == "* ") {
                current = name
            } else if (name.startsWith("remotes/")) {
                val remoteRef = name.removePrefix("remotes/")
                if (!remoteRef.contains(" -> ")) {
                    remotes += remoteRef
                }
            } else {
                locals += name
            }
        }

        return GitBranches(current = current, locals = locals, remotes = remotes)
    }

    /** `* (HEAD detached at abc1234)` and the legacy `* (no branch)` forms. */
    private fun isDetachedMarker(name: String): Boolean =
        name.startsWith("(HEAD detached") ||
            name.startsWith("(no branch") ||
            name.startsWith("(detached from")

    /**
     * Parses `git remote -v` output: one [GitRemote] per remote (fetch
     * lines only; push lines are duplicates), URLs credential-stripped
     * via [stripUrlCredentials].
     */
    fun parseRemotes(bytes: ByteArray): List<GitRemote> {
        val byName = LinkedHashMap<String, String>()
        for (raw in String(bytes, Charsets.UTF_8).lines()) {
            if (!raw.endsWith(" (fetch)")) continue
            val body = raw.removeSuffix(" (fetch)")
            val name = body.substringBefore('\t').trim()
            val url = body.substringAfter('\t', "").trim()
            if (name.isEmpty() || url.isEmpty()) continue
            byName[name] = stripUrlCredentials(url)
        }
        return byName.map { GitRemote(it.key, it.value) }
    }

    /**
     * Removes userinfo (`user:pass@`, `token@`, `user@`) from URLs that
     * carry a scheme, e.g. `https://token@github.com/a/b` ->
     * `https://github.com/a/b`. SCP-style URLs without a scheme
     * (`git@github.com:a/b.git`) are returned unchanged — the `git@` user
     * is public knowledge and carries no secret.
     */
    fun stripUrlCredentials(url: String): String =
        url.replaceFirst(Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@"), "$1")
}
