package com.maragung.arrowide.packages

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ProjectPackageManager]: manifest detection, dependency listing, and
 * honest command generation (null when the toolchain tool is missing).
 */
class ProjectPackageManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun workspace(vararg files: Pair<String, String>): File =
        tmp.newFolder().apply {
            files.forEach { (name, content) -> File(this, name).writeText(content) }
        }

    // ---- detection ----------------------------------------------------------

    @Test
    fun detectFindsNodeAndPythonManifests() {
        val node = workspace("package.json" to "{}")
        assertEquals(listOf(PackageEcosystem.NODE), ProjectPackageManager { true }.detect(node))

        val python = workspace("requirements.txt" to "flask")
        assertEquals(
            listOf(PackageEcosystem.PYTHON),
            ProjectPackageManager { true }.detect(python),
        )

        val both = workspace(
            "package.json" to "{}",
            "requirements.txt" to "flask",
        )
        assertEquals(
            listOf(PackageEcosystem.NODE, PackageEcosystem.PYTHON),
            ProjectPackageManager { true }.detect(both),
        )
    }

    @Test
    fun detectIgnoresMissingManifests() {
        val empty = workspace("README.md" to "hi")
        assertTrue(ProjectPackageManager { true }.detect(empty).isEmpty())
    }

    // ---- listing ------------------------------------------------------------

    @Test
    fun listPackagesReadsNodeDependenciesAndDevDependencies() = runBlocking {
        val ws = workspace(
            "package.json" to """
                {
                  "name": "demo",
                  "dependencies": { "express": "^4.18.0", "react": "18.2.0" },
                  "devDependencies": { "vite": "~5.0.1" }
                }
            """.trimIndent(),
        )
        val packages = ProjectPackageManager { true }.listPackages(ws)

        assertEquals(3, packages.size)
        val express = packages.first { it.name == "express" }
        assertEquals("4.18.0", express.version)
        assertEquals(PackageEcosystem.NODE, express.ecosystem)
        assertEquals(false, express.isDev)
        val vite = packages.first { it.name == "vite" }
        assertEquals("5.0.1", vite.version)
        assertTrue(vite.isDev)
    }

    @Test
    fun listPackagesReadsRequirementsTxtVariants() = runBlocking {
        val ws = workspace(
            "requirements.txt" to """
                # comment line
                flask==3.0.0
                requests>=2.31
                numpy
                -e .
            """.trimIndent(),
        )
        val packages = ProjectPackageManager { true }.listPackages(ws)

        assertEquals(3, packages.size)
        val flask = packages.first { it.name == "flask" }
        assertEquals("3.0.0", flask.version)
        val requests = packages.first { it.name == "requests" }
        assertNull(requests.version)
        val numpy = packages.first { it.name == "numpy" }
        assertNull(numpy.version)
        assertTrue(packages.none { it.name == "-e" })
    }

    @Test
    fun invalidPackageJsonYieldsNoNodePackages() = runBlocking {
        val ws = workspace("package.json" to "{ not json")
        val packages = ProjectPackageManager { true }.listPackages(ws)
        assertTrue(packages.isEmpty())
    }

    // ---- command generation ---------------------------------------------------

    @Test
    fun installCommandUsesNpmByDefault() {
        val ws = workspace("package.json" to "{}")
        val cmd = ProjectPackageManager { true }
            .installCommand(PackageEcosystem.NODE, "express", ws)
        assertEquals("npm add express", cmd?.command)
    }

    @Test
    fun lockfilesChoosePnpmAndYarn() {
        val pnpm = workspace("package.json" to "{}", "pnpm-lock.yaml" to "")
        val yarn = workspace("package.json" to "{}", "yarn.lock" to "")
        val manager = ProjectPackageManager { true }

        assertEquals(
            "pnpm add express",
            manager.installCommand(PackageEcosystem.NODE, "express", pnpm)?.command,
        )
        assertEquals(
            "yarn add express",
            manager.installCommand(PackageEcosystem.NODE, "express", yarn)?.command,
        )
    }

    @Test
    fun missingToolYieldsNullCommands() {
        val ws = workspace("package.json" to "{}", "requirements.txt" to "flask")
        val manager = ProjectPackageManager { false }

        assertNull(manager.installCommand(PackageEcosystem.NODE, "express", ws))
        assertNull(manager.installCommand(PackageEcosystem.PYTHON, "flask", ws))
    }

    @Test
    fun pipCommandsUsePipDirectly() {
        val ws = workspace("requirements.txt" to "flask")
        val manager = ProjectPackageManager { true }

        assertEquals(
            "pip install flask",
            manager.installCommand(PackageEcosystem.PYTHON, "flask", ws)?.command,
        )
        assertEquals(
            "pip uninstall -y flask",
            manager.removeCommand(PackageEcosystem.PYTHON, "flask", ws)?.command,
        )
        assertEquals(
            "pip install --upgrade flask",
            manager.updateCommand(PackageEcosystem.PYTHON, "flask", ws)?.command,
        )
    }

    @Test
    fun npmRemoveAndUpdateUseNativeSubcommands() {
        val ws = workspace("package.json" to "{}")
        val manager = ProjectPackageManager { true }

        assertEquals(
            "npm remove express",
            manager.removeCommand(PackageEcosystem.NODE, "express", ws)?.command,
        )
        assertEquals(
            "npm update express",
            manager.updateCommand(PackageEcosystem.NODE, "express", ws)?.command,
        )
    }

    @Test
    fun yarnRemoveUsesYarnRemove() {
        val ws = workspace("package.json" to "{}", "yarn.lock" to "")
        val manager = ProjectPackageManager { true }

        assertEquals(
            "yarn remove express",
            manager.removeCommand(PackageEcosystem.NODE, "express", ws)?.command,
        )
    }
}
