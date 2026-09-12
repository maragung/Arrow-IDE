package com.maragung.arrowide.packages

import java.io.File

/** Package ecosystems the manager understands (plan #29). */
enum class PackageEcosystem { NODE, PYTHON }

/**
 * One declared dependency of the open project.
 *
 * @param name      package name (e.g. "express")
 * @param version   pinned version as written, or null when unpinned
 * @param ecosystem where the package is declared
 * @param isDev     true for devDependencies / dev groups
 */
data class ManagedPackage(
    val name: String,
    val version: String?,
    val ecosystem: PackageEcosystem,
    val isDev: Boolean = false,
)

/**
 * A real package-manager command ready to run in a terminal session.
 * The UI shows [label] and executes [command]; this class never runs
 * anything itself.
 *
 * @param command full shell command line (e.g. "npm install express")
 * @param label   short UI label (e.g. "Install express")
 */
data class PackageCommand(
    val command: String,
    val label: String,
)
