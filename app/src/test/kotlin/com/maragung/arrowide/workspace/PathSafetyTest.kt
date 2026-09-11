package com.maragung.arrowide.workspace

import com.maragung.arrowide.workspace.PathSafety.PathTraversalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PathSafetyTest {

    private fun tempRoot(): File = Files.createTempDirectory("arrow-ws").toFile()

    @Test
    fun `relative path resolves inside root`() {
        val root = tempRoot()
        val resolved = PathSafety.resolveWithin(root, "src/main.kt")
        assertTrue(resolved.toPath().startsWith(root.canonicalFile.toPath()))
    }

    @Test
    fun `root itself is allowed`() {
        val root = tempRoot()
        assertEquals(root.canonicalFile, PathSafety.resolveWithin(root, "."))
        assertEquals(root.canonicalFile, PathSafety.resolveWithin(root, ""))
    }

    @Test
    fun `nested path returns canonical location`() {
        val root = tempRoot()
        val resolved = PathSafety.resolveWithin(root, "a/b/../c.txt")
        assertEquals(File(root.canonicalFile, "a/c.txt"), resolved)
    }

    @Test
    fun `traversal with dotdot is rejected`() {
        val root = tempRoot()
        var thrown = false
        try {
            PathSafety.resolveWithin(root, "../outside.txt")
        } catch (e: PathTraversalException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `absolute path outside root is rejected`() {
        val root = tempRoot()
        var thrown = false
        try {
            PathSafety.resolveWithin(root, "/etc/passwd")
        } catch (e: PathTraversalException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `absolute path inside root is allowed`() {
        val root = tempRoot()
        val inside = File(root, "notes.md").apply { createNewFile() }
        assertEquals(inside.canonicalFile, PathSafety.resolveWithin(root, inside.absolutePath))
    }

    @Test
    fun `sneaky traversal is rejected`() {
        val root = tempRoot()
        var thrown = false
        try {
            PathSafety.resolveWithin(root, "a/../../escape.sh")
        } catch (e: PathTraversalException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
