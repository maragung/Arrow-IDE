package com.maragung.arrowide.codebase

import com.maragung.arrowide.buildsystem.BuildSystemDetector
import com.maragung.arrowide.git.GitBranches
import com.maragung.arrowide.git.GitOutcome
import com.maragung.arrowide.git.GitService
import com.maragung.arrowide.git.GitStatus
import com.maragung.arrowide.toolchain.ToolchainManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * What the editor knows about the project it has open (plan #31):
 * workspace, git state, build system and interpreter versions, shown in
 * the status line and used to pre-fill commands and CI workflows.
 *
 * Every field is nullable and honestly null when unknown (plan #49): a
 * non-git workspace reports null branch and dirty count, not "main" and
 * 0; a tool that is not installed reports null, not a fabricated version.
 *
 * @param workspacePath    absolute path of the open workspace, null when closed
 * @param projectName      the workspace directory's name
 * @param gitBranch        current branch ([GitBranches.current]); null when
 *                         the workspace is not a repository or HEAD is detached
 * @param gitDirtyCount    staged + unstaged + untracked + conflicted paths;
 *                         null when git could not report a status
 * @param buildSystemName  display names of every detected build system
 *                         (plan #24), joined with ", "; null when no marker
 *                         file is present
 * @param nodeVersion      output of the installed node binary (`v22.x`),
 *                         null when unavailable
 * @param pythonVersion    output of the installed python binary (`3.x`),
 *                         null when unavailable
 */
data class CodebaseStatus(
    val workspacePath: String?,
    val projectName: String?,
    val gitBranch: String?,
    val gitDirtyCount: Int?,
    val buildSystemName: String?,
    val nodeVersion: String?,
    val pythonVersion: String?,
)

/**
 * Gathers [CodebaseStatus] for the current workspace (plan #31).
 *
 * [status] is a cold, single-shot flow (no polling — the UI re-collects on
 * workspace change): it emits an immediate status carrying only the
 * synchronous facts (workspace path and project name; everything else
 * null), then gathers the I/O-bound facts concurrently on [ioDispatcher]
 * and emits the complete status once. Every fact is wrapped
 * independently, so a non-repository workspace, a git spawn failure or a
 * missing tool yields nulls — never an exception, never fake data
 * (plan #49).
 *
 * Pure JVM — no android.* imports; the app container constructs it with
 * the real services and a [runVersion] seam that executes
 * `<prefix>/bin/node --version` etc. Construction performs no I/O.
 *
 * @param gitService            git plumbing (plan #10/#11)
 * @param toolchainManager      availability oracle (plan #4-#7): a tool the
 *                              package repository does not offer for this
 *                              device is never probed for a version
 * @param buildSystemDetector   marker-file detection (plan #24)
 * @param runVersion            executes the installed tool's version
 *                              command and returns its trimmed output, or
 *                              null on any failure; returning null (the
 *                              default) leaves the version fields null
 * @param ioDispatcher          dispatcher for the gathered facts
 */
class CodebaseStatusProvider(
    private val gitService: GitService,
    private val toolchainManager: ToolchainManager,
    private val buildSystemDetector: BuildSystemDetector,
    private val runVersion: suspend (toolId: String) -> String? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Status of [workspace], or the all-null status when it is null.
     *
     * Emits the synchronous facts first, the gathered facts second; with a
     * null workspace it completes after the single immediate emission.
     */
    fun status(workspace: File?): Flow<CodebaseStatus> = flow {
        val immediate = CodebaseStatus(
            workspacePath = workspace?.absolutePath,
            projectName = workspace?.name,
            gitBranch = null,
            gitDirtyCount = null,
            buildSystemName = null,
            nodeVersion = null,
            pythonVersion = null,
        )
        emit(immediate)
        if (workspace == null) return@flow

        val gathered = coroutineScope {
            // Git facts share one task so the two git invocations run in a
            // deterministic order; the other fact groups run in parallel.
            val git = async(ioDispatcher) {
                GitFacts(
                    branch = safely { currentBranch(workspace) },
                    dirtyCount = safely { dirtyCount(workspace) },
                )
            }
            val buildSystem = async(ioDispatcher) { safely { buildSystemName(workspace) } }
            val node = async(ioDispatcher) { safely { toolVersion(TOOL_NODEJS) } }
            val python = async(ioDispatcher) { safely { toolVersion(TOOL_PYTHON) } }
            val gitFacts = git.await()
            GatheredFacts(
                branch = gitFacts.branch,
                dirtyCount = gitFacts.dirtyCount,
                buildSystemName = buildSystem.await(),
                nodeVersion = node.await(),
                pythonVersion = python.await(),
            )
        }
        emit(
            immediate.copy(
                gitBranch = gathered.branch,
                gitDirtyCount = gathered.dirtyCount,
                buildSystemName = gathered.buildSystemName,
                nodeVersion = gathered.nodeVersion,
                pythonVersion = gathered.pythonVersion,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Facts
    // ------------------------------------------------------------------

    /** Current branch of [workspace]; null when not a repo or detached. */
    private suspend fun currentBranch(workspace: File): String? =
        when (val outcome = gitService.repositoryFor(workspace).branches()) {
            is GitOutcome.Ok<GitBranches> -> outcome.value.current
            is GitOutcome.Error -> null
        }

    /**
     * Staged + unstaged + untracked + conflicted paths of [workspace]
     * (the porcelain parser routes conflicted paths only into their own
     * list, so the sum double-counts nothing); null when git fails.
     */
    private suspend fun dirtyCount(workspace: File): Int? =
        when (val outcome = gitService.repositoryFor(workspace).status()) {
            is GitOutcome.Ok<GitStatus> -> with(outcome.value) {
                staged.size + unstaged.size + untracked.size + conflicted.size
            }

            is GitOutcome.Error -> null
        }

    /**
     * Display names of every build system detected in [workspace]
     * (plan #24), joined with ", "; null when no marker file exists.
     */
    private fun buildSystemName(workspace: File): String? =
        buildSystemDetector.detect(workspace)
            .joinToString { it.kind.displayName }
            .takeIf { it.isNotEmpty() }

    /**
     * Version of [toolId] via the [runVersion] seam, trimmed; blank output
     * counts as unknown. Tools the package repository definitely does not
     * offer for this device ([ToolchainManager.isAvailable] == false) are
     * skipped without probing — null (index not loaded yet) still probes,
     * since the binary may already be installed.
     */
    private suspend fun toolVersion(toolId: String): String? {
        if (toolchainManager.isAvailable(toolId) == false) return null
        return runVersion(toolId)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Runs [block], mapping any failure (except cancellation, which
     * propagates) to null — the same contract [GitRepository.run] uses.
     */
    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** The two git facts, gathered together. */
    private data class GitFacts(val branch: String?, val dirtyCount: Int?)

    /** All facts that require I/O. */
    private data class GatheredFacts(
        val branch: String?,
        val dirtyCount: Int?,
        val buildSystemName: String?,
        val nodeVersion: String?,
        val pythonVersion: String?,
    )

    private companion object {
        val TOOL_NODEJS = "nodejs"
        val TOOL_PYTHON = "python"
    }
}
