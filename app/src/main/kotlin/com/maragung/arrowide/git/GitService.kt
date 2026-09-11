package com.maragung.arrowide.git

import java.io.File

/**
 * App-scoped entry point into the git layer (plan #10/#11): wires the
 * shared [GitProcess] (the toolchain's git binary), the commit identity
 * and the credential lookup into per-directory [GitRepository]s.
 *
 * Pure JVM — no android.* imports; the app container instantiates it
 * with `AndroidGitProcess(File(context.filesDir, "usr"), ...)`-style
 * arguments once and hands it to the Source Control UI, the GitHub
 * client and the CI helper. Construction performs no I/O.
 *
 * @param process              git runner
 * @param homeDir              HOME for git processes (the terminal home);
 *                              kept for callers wiring default locations
 * @param askpassCacheDir      app-private directory for temporary
 *                              GIT_ASKPASS scripts (plan #38)
 * @param identityProvider     commit identity or null
 * @param credentialsProvider  remote credential lookup (secure storage
 *                              in the app)
 */
class GitService(
    private val process: GitProcess,
    val homeDir: File,
    private val askpassCacheDir: File,
    private val identityProvider: () -> GitIdentity? = { null },
    private val credentialsProvider: CredentialsProvider,
) {

    /** Whether the toolchain's git binary is installed (Toolchain Manager). */
    fun isGitInstalled(): Boolean = process.gitBinary.isFile

    /**
     * A [GitRepository] operating on [dir]. The directory need not be a
     * repository yet — call [GitRepository.init] or [GitRepository.clone]
     * to make it one; [GitRepository.isRepository] checks cheaply.
     */
    fun repositoryFor(dir: File): GitRepository = GitRepository(
        repoDir = dir,
        process = process,
        identityProvider = identityProvider,
        credentialsProvider = credentialsProvider,
        askpassCacheDir = askpassCacheDir,
    )
}
