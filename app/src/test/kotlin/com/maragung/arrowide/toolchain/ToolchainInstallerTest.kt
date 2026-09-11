package com.maragung.arrowide.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ToolchainInstallerTest {

    private val root = Files.createTempDirectory("arrow-install").toFile()
    private val prefix = File(root, "usr")
    private val cache = File(root, "cache").apply { mkdirs() }

    private val installer = ToolchainInstaller(
        ToolchainEnvironment(prefix, cache, "aarch64"),
        Dispatchers.Default,
    )

    private fun pkg(name: String, version: String = "1.0") = RepoPackage(
        name = name,
        version = version,
        filename = "pool/$name.deb",
        sha256 = "00",
        size = 1,
        depends = "",
    )

    private fun debFor(name: String, entries: List<SyntheticDeb.Entry>): File =
        SyntheticDeb.build(File(cache, "$name.deb"), entries)

    @Test
    fun installExtractsIntoPrefixWithUsrStripped() = runBlocking {
        val deb = debFor(
            "nodejs",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/node", "node-binary"),
                SyntheticDeb.symlink("./usr/bin/npm", "node"),
                SyntheticDeb.file("./usr/lib/node.js", "lib"),
            ),
        )

        val manifest = installer.install(pkg("nodejs", "22.1.0"), deb)

        // ./usr/bin/node lands at prefix/bin/node.
        val node = File(prefix, "bin/node")
        assertTrue(node.isFile)
        assertEquals("node-binary", node.readText())
        assertTrue(node.canExecute())
        assertTrue(Files.isSymbolicLink(File(prefix, "bin/npm").toPath()))
        assertEquals("lib", File(prefix, "lib/node.js").readText())

        assertEquals("nodejs", manifest.packageName)
        assertEquals("22.1.0", manifest.version)
        assertTrue("bin/node" in manifest.files)
        assertEquals("node", manifest.symlinks["bin/npm"])
    }

    @Test
    fun manifestIsPersistedAndReadable() = runBlocking {
        val deb = debFor(
            "git",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/git", "git-binary"),
            ),
        )

        installer.install(pkg("git", "2.44.0"), deb)

        val manifests = installer.readManifests()
        assertEquals(setOf("git"), manifests.keys)
        assertEquals("2.44.0", manifests.getValue("git").version)
        assertTrue(File(prefix, "var/arrow-ide/manifests/git.json").isFile)
    }

    @Test
    fun stagingAndBackupDirectoriesAreCleanedUp() = runBlocking {
        val deb = debFor(
            "curl",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/curl", "curl-binary"),
            ),
        )

        installer.install(pkg("curl"), deb)

        assertFalse(File(root, "usr-staging-curl").exists())
        assertFalse(File(root, "usr-backup-curl").exists())
    }

    @Test
    fun removeDeletesOnlyThatPackagesFiles() = runBlocking {
        val gitDeb = debFor(
            "git",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/git", "git-binary"),
                SyntheticDeb.file("./usr/share/git/config", "[core]"),
            ),
        )
        val curlDeb = debFor(
            "curl",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/curl", "curl-binary"),
            ),
        )

        val gitManifest = installer.install(pkg("git"), gitDeb)
        installer.install(pkg("curl"), curlDeb)
        assertTrue(File(prefix, "bin/git").isFile)

        installer.remove(gitManifest)

        assertFalse("git's own file is gone", File(prefix, "bin/git").isFile)
        assertFalse("git's shared dir content is gone", File(prefix, "share/git/config").exists())
        assertTrue("other packages are untouched", File(prefix, "bin/curl").isFile)
        assertTrue("manifest file is deleted", "git" !in installer.readManifests())
        // bin/ still holds curl's file, so it must survive pruning.
        assertTrue(File(prefix, "bin").isDirectory)
    }

    @Test
    fun removePrunesEmptyDirectoriesUpward() = runBlocking {
        val deb = debFor(
            "jq",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/jq", "jq-binary"),
            ),
        )

        val manifest = installer.install(pkg("jq"), deb)
        installer.remove(manifest)

        assertFalse(File(prefix, "bin").exists())
        // The manifest tree must survive.
        assertTrue(File(prefix, "var/arrow-ide/manifests").isDirectory)
    }

    @Test
    fun overlappingFileIsOverwrittenAndStillRemovable() = runBlocking {
        val first = debFor(
            "pkg-one",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.file("./usr/bin/shared", "from-pkg-one"),
                SyntheticDeb.executable("./usr/bin/tool-one", "one"),
            ),
        )
        val second = debFor(
            "pkg-two",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.file("./usr/bin/shared", "from-pkg-two"),
                SyntheticDeb.executable("./usr/bin/tool-two", "two"),
            ),
        )

        val firstManifest = installer.install(pkg("pkg-one"), first)
        installer.install(pkg("pkg-two"), second)
        // Last install wins for overlapping files.
        assertEquals("from-pkg-two", File(prefix, "bin/shared").readText())

        installer.remove(installer.readManifests().getValue("pkg-two"))

        // The overlapping file is claimed by pkg-one too, so it survives.
        assertTrue(File(prefix, "bin/shared").isFile)
        assertEquals("from-pkg-two", File(prefix, "bin/shared").readText())
        assertFalse(File(prefix, "bin/tool-two").exists())
        assertTrue(File(prefix, "bin/tool-one").isFile)

        installer.remove(firstManifest)
        assertFalse(File(prefix, "bin/shared").exists())
    }

    @Test
    fun failedInstallMidMergeRollsBack() = runBlocking {
        // Seed the prefix with a file that pkg-two overwrites, plus a
        // non-empty directory where pkg-two later wants a file (that move
        // fails and forces a rollback after partial merges).
        val seed = debFor(
            "seed",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.file("./usr/bin/shared", "original"),
                SyntheticDeb.executable("./usr/bin/tool-seed", "seed"),
            ),
        )
        installer.install(pkg("seed"), seed)

        File(prefix, "lib/occupied").apply {
            parentFile.mkdirs()
            mkdir()
            File(this, "keep.txt").writeText("keep")
        }

        val bad = debFor(
            "pkg-bad",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.file("./usr/bin/shared", "overwritten-by-bad"),
                SyntheticDeb.executable("./usr/bin/tool-bad", "bad"),
                SyntheticDeb.file("./usr/lib/occupied", "cannot-land-here"),
            ),
        )

        try {
            installer.install(pkg("pkg-bad"), bad)
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            // expected
        }

        // Rollback: overwritten file restored, new file removed, seed intact.
        assertEquals("original", File(prefix, "bin/shared").readText())
        assertFalse(File(prefix, "bin/tool-bad").exists())
        assertTrue(File(prefix, "bin/tool-seed").isFile)
        // The blocking directory is untouched.
        assertEquals("keep", File(prefix, "lib/occupied/keep.txt").readText())
        assertFalse("no manifest for the failed package", "pkg-bad" in installer.readManifests())
        assertFalse(File(root, "usr-staging-pkg-bad").exists())
        assertFalse(File(root, "usr-backup-pkg-bad").exists())
    }

    @Test
    fun corruptDebLeavesPrefixUntouched() = runBlocking {
        val seed = debFor(
            "seed2",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/tool-seed", "seed"),
            ),
        )
        installer.install(pkg("seed2"), seed)

        val corrupt = File(cache, "corrupt.deb")
        corrupt.writeBytes(byteArrayOf(1, 2, 3, 4))

        try {
            installer.install(pkg("corrupt"), corrupt)
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            // expected
        }

        assertTrue(File(prefix, "bin/tool-seed").isFile)
        assertTrue(installer.readManifests().keys == setOf("seed2"))
    }

    @Test
    fun reinstallOverExistingPackageKeepsWorkingState() = runBlocking {
        val v1 = debFor(
            "make",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/make", "make-v1"),
            ),
        )
        val v2 = debFor(
            "make",
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/make", "make-v2"),
            ),
        )

        installer.install(pkg("make", "4.3"), v1)
        val manifest = installer.install(pkg("make", "4.4"), v2)

        assertEquals("make-v2", File(prefix, "bin/make").readText())
        assertEquals("4.4", manifest.version)
        assertEquals(1, installer.readManifests().keys.count { it == "make" })
    }
}
