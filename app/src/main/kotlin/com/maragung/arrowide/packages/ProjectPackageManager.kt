package com.maragung.arrowide.packages

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Reads the open project's declared dependencies and produces real
 * package-manager commands for them (plan #29).
 *
 * Nothing is executed here — the UI runs every [PackageCommand] in a real
 * terminal session, so package operations behave exactly as on the command
 * line (honest behavior, plan #49).
 *
 * @param toolAvailable returns true when a toolchain tool id
 *        ("nodejs", "python") is currently installed
 */
class ProjectPackageManager(
    private val toolAvailable: (String) -> Boolean,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Which ecosystems the [workspace] declares manifests for. */
    fun detect(workspace: File): List<PackageEcosystem> = listOfNotNull(
        PackageEcosystem.NODE.takeIf { File(workspace, "package.json").isFile },
        PackageEcosystem.PYTHON.takeIf { File(workspace, "requirements.txt").isFile },
    )

    /**
     * Reads every declared dependency: package.json
     * `dependencies` + `devDependencies`, and `requirements.txt` lines
     * (`name==version`, `name>=version`, bare `name`; comments skipped).
     */
    suspend fun listPackages(
        workspace: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<ManagedPackage> = withContext(ioDispatcher) {
        val node = readNodePackages(workspace)
        val python = readPythonPackages(workspace)
        node + python
    }

    /**
     * Command to install [packageName], or null when the ecosystem's
     * toolchain tool is missing (the UI then tells the user to install it
     * in Tools first — no fake fallback).
     */
    fun installCommand(
        ecosystem: PackageEcosystem,
        packageName: String,
        workspace: File,
    ): PackageCommand? = when (ecosystem) {
        PackageEcosystem.NODE -> nodeRunner(workspace)?.let { runner ->
            PackageCommand("$runner add $packageName", "Install $packageName ($runner)")
        }
        PackageEcosystem.PYTHON -> pythonRunner()?.let { runner ->
            PackageCommand("$runner install $packageName", "Install $packageName ($runner)")
        }
    }

    /** Command to remove [packageName], or null when the tool is missing. */
    fun removeCommand(
        ecosystem: PackageEcosystem,
        packageName: String,
        workspace: File,
    ): PackageCommand? = when (ecosystem) {
        PackageEcosystem.NODE -> nodeRunner(workspace)?.let { runner ->
            PackageCommand(
                if (runner == "npm") "npm remove $packageName"
                else "$runner remove $packageName",
                "Remove $packageName ($runner)",
            )
        }
        PackageEcosystem.PYTHON -> pythonRunner()?.let { runner ->
            PackageCommand("$runner uninstall -y $packageName", "Remove $packageName ($runner)")
        }
    }

    /** Command to update [packageName], or null when the tool is missing. */
    fun updateCommand(
        ecosystem: PackageEcosystem,
        packageName: String,
        workspace: File,
    ): PackageCommand? = when (ecosystem) {
        PackageEcosystem.NODE -> nodeRunner(workspace)?.let { runner ->
            PackageCommand(
                if (runner == "npm") "npm update $packageName"
                else "$runner update $packageName",
                "Update $packageName ($runner)",
            )
        }
        PackageEcosystem.PYTHON -> pythonRunner()?.let { runner ->
            PackageCommand("$runner install --upgrade $packageName", "Update $packageName ($runner)")
        }
    }

    // ------------------------------------------------------------------ NODE

    /**
     * The Node package runner to use: pnpm/yarn when their lockfile is
     * present (the project already uses them), else npm.
     */
    private fun nodeRunner(workspace: File): String? {
        if (!toolAvailable("nodejs")) return null
        return when {
            File(workspace, "pnpm-lock.yaml").isFile -> "pnpm"
            File(workspace, "yarn.lock").isFile -> "yarn"
            else -> "npm"
        }
    }

    private fun readNodePackages(workspace: File): List<ManagedPackage> {
        val file = File(workspace, "package.json")
        if (!file.isFile) return emptyList()
        val root = try {
            json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
        } catch (e: Exception) {
            return emptyList() // invalid JSON: no honest package list
        } ?: return emptyList()
        return dependenciesOf(root, "dependencies", isDev = false) +
            dependenciesOf(root, "devDependencies", isDev = true)
    }

    private fun dependenciesOf(
        root: JsonObject,
        key: String,
        isDev: Boolean,
    ): List<ManagedPackage> {
        val deps = root[key] as? JsonObject ?: return emptyList()
        return deps.entries.mapNotNull { (name, value) ->
            val version = (value as? JsonPrimitive)?.contentOrNull
            ManagedPackage(
                name = name,
                version = version?.removeSurrounding("^", "")
                    ?.removeSurrounding("~", ""),
                ecosystem = PackageEcosystem.NODE,
                isDev = isDev,
            )
        }
    }

    // --------------------------------------------------------------- PYTHON

    private fun pythonRunner(): String? =
        if (toolAvailable("python")) "pip" else null

    private fun readPythonPackages(workspace: File): List<ManagedPackage> {
        val file = File(workspace, "requirements.txt")
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8)
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-") } // skip options
            .map { line ->
                val name = line.substringBefore("==")
                    .substringBefore(">=")
                    .substringBefore("<=")
                    .substringBefore("!=")
                    .substringBefore(">")
                    .substringBefore("<")
                    .substringBefore("=")
                    .trim()
                val version = line.substringAfter("==", "")
                    .takeIf { it.isNotEmpty() && "==" in line }
                    ?.trim()
                ManagedPackage(
                    name = name,
                    version = version,
                    ecosystem = PackageEcosystem.PYTHON,
                )
            }
            .filter { it.name.isNotEmpty() }
    }
}
