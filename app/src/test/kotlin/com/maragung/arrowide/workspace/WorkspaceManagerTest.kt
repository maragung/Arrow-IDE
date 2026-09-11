package com.maragung.arrowide.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class WorkspaceManagerTest {

    private fun newManager(): WorkspaceManager {
        val home = Files.createTempDirectory("arrow-home").toFile()
        return WorkspaceManager(home)
    }

    @Test
    fun `projects dir is created under home`() {
        val home = Files.createTempDirectory("arrow-home").toFile()
        val manager = WorkspaceManager(home)
        assertTrue(manager.projectsDir.isDirectory)
        assertEquals(File(home, "projects").canonicalPath, manager.projectsDir.canonicalPath)
    }

    @Test
    fun `createProject creates directory and seeds README`() = runBlocking {
        val manager = newManager()
        val dir = manager.createProject("demo")
        assertTrue(dir.isDirectory)
        val readme = File(dir, "README.md")
        assertTrue(readme.isFile)
        assertTrue(readme.readText().contains("demo"))
    }

    @Test
    fun `createProject rejects invalid names`() = runBlocking {
        val manager = newManager()
        for (invalid in listOf("", " ", "/", "..", ".", "a/b", "a\\b")) {
            var thrown = false
            try {
                manager.createProject(invalid)
            } catch (e: IllegalArgumentException) {
                thrown = true
            }
            assertTrue("expected rejection for '$invalid'", thrown)
        }
    }

    @Test
    fun `listProjects reflects creation`() = runBlocking {
        val manager = newManager()
        assertTrue(manager.listProjects().isEmpty())

        manager.createProject("alpha")
        manager.createProject("beta")

        val names = manager.listProjects().map { it.name }
        assertEquals(setOf("alpha", "beta"), names.toSet())

        val alpha = manager.listProjects().first { it.name == "alpha" }
        assertEquals(File(manager.projectsDir, "alpha").canonicalPath, alpha.dir.canonicalPath)
    }

    @Test
    fun `deleteProject removes directory`() = runBlocking {
        val manager = newManager()
        val dir = manager.createProject("gone")
        manager.deleteProject("gone")
        assertFalse(dir.exists())
        assertTrue(manager.listProjects().isEmpty())
    }

    @Test
    fun `deleteProject rejects invalid names`() = runBlocking {
        val manager = newManager()
        var thrown = false
        try {
            manager.deleteProject("../escape")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `double create fails gracefully`() = runBlocking {
        val manager = newManager()
        manager.createProject("dup")
        var thrown = false
        try {
            manager.createProject("dup")
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue(thrown)
        // The original project is untouched.
        assertTrue(File(manager.projectsDir, "dup/README.md").isFile)
    }

    @Test
    fun `openProject returns validated directory and rejects traversal`() = runBlocking {
        val manager = newManager()
        val dir = manager.createProject("openable")
        assertEquals(dir.canonicalFile, manager.openProject("openable"))

        var thrown = false
        try {
            manager.openProject("../escape")
        } catch (e: Exception) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `openProject on missing project fails`() = runBlocking {
        val manager = newManager()
        var thrown = false
        try {
            manager.openProject("does-not-exist")
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `setCurrentWorkspace updates flow`() = runBlocking {
        val manager = newManager()
        assertNull(manager.currentWorkspace.value)

        val dir = manager.createProject("ws")
        manager.setCurrentWorkspace(dir)
        assertEquals(dir, manager.currentWorkspace.value)

        manager.setCurrentWorkspace(null)
        assertNull(manager.currentWorkspace.value)
    }
}
