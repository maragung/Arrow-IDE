package com.maragung.arrowide.workspace

import com.maragung.arrowide.workspace.PathSafety.PathTraversalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FileTreeRepositoryTest {

    private fun newRepo(): Pair<FileTreeRepository, File> {
        val root = Files.createTempDirectory("arrow-tree").toFile()
        return FileTreeRepository(root) to root
    }

    @Test
    fun `listing is dirs first then alphabetical`() {
        val (repo, root) = newRepo()
        File(root, "zebra.txt").writeText("z")
        File(root, "beta").mkdirs()
        File(root, "apple.txt").writeText("a")
        File(root, "alpha").mkdirs()

        val names = repo.listDir("").map { it.name }
        assertEquals(listOf("alpha", "beta", "apple.txt", "zebra.txt"), names)
    }

    @Test
    fun `entries carry relative path and metadata`() {
        val (repo, root) = newRepo()
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() {}")

        val dirEntry = repo.listDir("").single()
        assertTrue(dirEntry.isDirectory)
        assertEquals("src", dirEntry.path)

        val fileEntry = repo.listDir("src").single()
        assertEquals("Main.kt", fileEntry.name)
        assertEquals("src/Main.kt", fileEntry.path)
        assertFalse(fileEntry.isDirectory)
        assertTrue(fileEntry.size > 0)
        assertTrue(fileEntry.lastModified > 0)
    }

    @Test
    fun `traversal is rejected`() {
        val (repo, _) = newRepo()
        var thrown = false
        try {
            repo.listDir("../outside")
        } catch (e: PathTraversalException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `listing missing path fails`() {
        val (repo, _) = newRepo()
        var thrown = false
        try {
            repo.listDir("no-such-dir")
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `nested paths list correctly`() {
        val (repo, root) = newRepo()
        File(root, "a/b/c").mkdirs()
        File(root, "a/b/c/deep.txt").writeText("d")

        val deep = repo.listDir("a/b/c").single()
        assertEquals("deep.txt", deep.name)
        assertEquals("a/b/c/deep.txt", deep.path)
    }

    @Test
    fun `createFile and createDirectory round trip`() {
        val (repo, root) = newRepo()
        repo.createDirectory("", "src")
        val entry = repo.createFile("src", "main.kt")

        assertEquals("src/main.kt", entry.path)
        assertFalse(entry.isDirectory)
        assertTrue(File(root, "src/main.kt").isFile)

        var thrown = false
        try {
            repo.createFile("src", "main.kt")
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `create with invalid name is rejected`() {
        val (repo, _) = newRepo()
        for (invalid in listOf("", " ", "..", "a/b")) {
            var thrown = false
            try {
                repo.createFile("", invalid)
            } catch (e: IllegalArgumentException) {
                thrown = true
            }
            assertTrue("expected rejection for '$invalid'", thrown)
        }
    }

    @Test
    fun `rename moves entry within its parent`() {
        val (repo, root) = newRepo()
        File(root, "old.txt").writeText("hi")

        val renamed = repo.rename("old.txt", "new.txt")

        assertEquals("new.txt", renamed.name)
        assertFalse(File(root, "old.txt").exists())
        assertTrue(File(root, "new.txt").exists())
        assertEquals("hi", File(root, "new.txt").readText())
    }

    @Test
    fun `rename onto existing name is rejected`() {
        val (repo, root) = newRepo()
        File(root, "a.txt").writeText("a")
        File(root, "b.txt").writeText("b")

        var thrown = false
        try {
            repo.rename("a.txt", "b.txt")
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `delete removes files and directories recursively`() {
        val (repo, root) = newRepo()
        File(root, "dir").mkdirs()
        File(root, "dir/inner.txt").writeText("x")
        File(root, "plain.txt").writeText("y")

        repo.delete("dir")
        repo.delete("plain.txt")

        assertFalse(File(root, "dir").exists())
        assertFalse(File(root, "plain.txt").exists())
    }

    @Test
    fun `delete of root is rejected`() {
        val (repo, _) = newRepo()
        var thrown = false
        try {
            repo.delete("")
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
