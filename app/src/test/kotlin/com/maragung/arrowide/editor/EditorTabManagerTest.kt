package com.maragung.arrowide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditorTabManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager() = EditorTabManager()

    private fun newFile(name: String): File {
        val file = File(tmp.root, name)
        file.parentFile?.mkdirs()
        file.writeText("content of $name")
        return file
    }

    // ---- openFile / dedupe --------------------------------------------------

    @Test
    fun openFile_opensTabAndActivatesIt() {
        val m = manager()
        val file = newFile("a.txt")

        val tab = m.openFile(file)

        assertEquals(listOf(tab), m.tabs.value)
        assertEquals(tab, m.activeTab.value)
        assertEquals("a.txt", tab.displayName)
        assertFalse(tab.isDirty)
    }

    @Test
    fun openFile_dedupesByCanonicalPath() {
        val m = manager()
        val file = newFile("dir/../a.txt")
        val sameFile = File(file.canonicalPath)

        val first = m.openFile(file)
        val second = m.openFile(sameFile)

        assertEquals(first.id, second.id)
        assertEquals(1, m.tabs.value.size)
        assertEquals(first, m.activeTab.value)
    }

    @Test
    fun openFile_assignsLanguageIdFromExtension() {
        val m = manager()
        assertEquals("source.kotlin", m.openFile(newFile("x.kt")).languageId)
        assertEquals("source.python", m.openFile(newFile("x.py")).languageId)
        assertEquals("source.c", m.openFile(newFile("x.c")).languageId)
        assertEquals("", m.openFile(newFile("x.unknownext")).languageId)
    }

    // ---- close / activate ----------------------------------------------------

    @Test
    fun closeTab_removesTabAndActivatesNeighbor() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))
        val b = m.openFile(newFile("b.txt"))
        val c = m.openFile(newFile("c.txt"))

        m.closeTab(b.id)

        assertEquals(listOf(a, c), m.tabs.value)
        // Closing the middle tab activates the tab that took its place.
        assertEquals(c, m.activeTab.value)
    }

    @Test
    fun closeTab_lastTabClearsActive() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))

        m.closeTab(a.id)

        assertTrue(m.tabs.value.isEmpty())
        assertNull(m.activeTab.value)
    }

    @Test
    fun closeTab_unknownIdIsNoOp() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))

        m.closeTab("does-not-exist")

        assertEquals(listOf(a), m.tabs.value)
    }

    @Test
    fun closeTab_dropsBufferedContentAndCursor() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))
        m.updateContent(a.id, "edited")
        m.updateCursor(a.id, 3, 7)

        m.closeTab(a.id)

        assertNull(m.contentOf(a.id))
        assertNull(m.cursorOf(a.id))
    }

    @Test
    fun setActive_switchesActiveTab() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))
        val b = m.openFile(newFile("b.txt"))
        assertEquals(b, m.activeTab.value)

        m.setActive(a.id)

        assertEquals(a, m.activeTab.value)
    }

    @Test
    fun setActive_unknownIdKeepsCurrent() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))

        m.setActive("nope")

        assertEquals(a, m.activeTab.value)
    }

    // ---- dirty tracking --------------------------------------------------------

    @Test
    fun markDirtyAndMarkClean_updateTabInListAndActive() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))
        assertFalse(a.isDirty)

        m.markDirty(a.id)
        assertTrue(m.tabs.value.single().isDirty)
        assertTrue(m.activeTab.value!!.isDirty)
        assertTrue(m.isDirty(a.id)!!)

        m.markClean(a.id)
        assertFalse(m.tabs.value.single().isDirty)
        assertFalse(m.activeTab.value!!.isDirty)
        assertFalse(m.isDirty(a.id)!!)
    }

    @Test
    fun markDirty_unknownIdIsNoOp() {
        val m = manager()
        m.markDirty("nope")
        assertTrue(m.tabs.value.isEmpty())
        assertNull(m.isDirty("nope"))
    }

    // ---- content / cursor ----------------------------------------------------

    @Test
    fun updateContentAndCursor_storeLatestValues() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))

        m.updateContent(a.id, "hello")
        m.updateCursor(a.id, 12, 34)

        assertEquals("hello", m.contentOf(a.id))
        assertEquals(12 to 34, m.cursorOf(a.id))

        m.updateContent(a.id, "hello world")
        assertEquals("hello world", m.contentOf(a.id))
    }

    @Test
    fun updateContent_unknownIdIsIgnored() {
        val m = manager()
        m.updateContent("nope", "x")
        assertNull(m.contentOf("nope"))
    }

    // ---- recovery snapshot ----------------------------------------------------

    @Test
    fun snapshotForRecovery_containsOnlyDirtyTabs() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))
        val b = m.openFile(newFile("b.txt"))
        m.updateContent(a.id, "clean buffer") // not dirty -> excluded
        m.markDirty(b.id)
        m.updateContent(b.id, "dirty buffer")
        m.updateCursor(b.id, 5, 9)
        m.setActive(a.id)

        val snapshot = m.snapshotForRecovery()

        assertEquals(1, snapshot.tabs.size)
        val entry = snapshot.tabs.single()
        assertEquals(b.file.canonicalPath, entry.filePath)
        assertEquals("dirty buffer", entry.content)
        assertEquals(5, entry.cursorLine)
        assertEquals(9, entry.cursorColumn)
        assertEquals("b.txt", entry.displayName)
        assertEquals(b.languageId, entry.languageId)
        // Active tab is tracked even though it is clean.
        assertEquals(a.file.canonicalPath, snapshot.activeFilePath)
        assertTrue(snapshot.savedAtMillis > 0)
    }

    @Test
    fun snapshotForRecovery_emptyWhenNothingDirty() {
        val m = manager()
        m.openFile(newFile("a.txt"))

        val snapshot = m.snapshotForRecovery()

        assertTrue(snapshot.tabs.isEmpty())
    }

    // ---- lookup ---------------------------------------------------------------

    @Test
    fun tabFor_matchesByCanonicalPath() {
        val m = manager()
        val a = m.openFile(newFile("a.txt"))

        assertNotNull(m.tabFor(File(a.file.canonicalPath)))
        assertEquals(a.id, m.tabFor(File(a.file.path))?.id)
        assertNull(m.tabFor(newFile("other.txt")))
    }
}
