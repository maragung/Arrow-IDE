package com.maragung.arrowide.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecoveryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = RecoveryStore(tmp.root)

    private fun snapshot(vararg entries: RecoveryTabEntry, active: String? = null) =
        RecoverySnapshot(
            tabs = entries.toList(),
            activeFilePath = active,
            savedAtMillis = 1234567890L
        )

    @Test
    fun saveAndRestore_roundTrip() = runBlocking {
        val store = store()
        val state = snapshot(
            RecoveryTabEntry(
                filePath = "/data/ws/Main.kt",
                content = "fun main() {\r\n}\r\n",
                cursorLine = 2,
                cursorColumn = 5,
                displayName = "Main.kt",
                languageId = "source.kotlin"
            ),
            RecoveryTabEntry(
                filePath = "/data/ws/notes.md",
                content = "# Notes\n- mixed\r\n- endings\n",
                cursorLine = 0,
                cursorColumn = 0
            ),
            active = "/data/ws/Main.kt"
        )

        store.save(state)

        val restored = store.restore()
        assertEquals(state, restored)
    }

    @Test
    fun restore_returnsNullWhenNothingSaved() = runBlocking {
        assertNull(store().restore())
    }

    @Test
    fun restore_returnsNullForCorruptedJson() = runBlocking {
        val store = store()
        store.save(snapshot(RecoveryTabEntry("a", "b", 0, 0)))

        // Corrupt the persisted file behind the store's back.
        val recoveryFile = File(File(tmp.root, "recovery"), "recovery.json")
        assertTrue(recoveryFile.isFile)
        recoveryFile.writeText("{ this is ] not valid json !!!")

        assertNull(store.restore())
    }

    @Test
    fun restore_toleratesUnknownFields() = runBlocking {
        val store = store()
        store.save(snapshot(RecoveryTabEntry("a", "b", 1, 2)))
        val recoveryFile = File(File(tmp.root, "recovery"), "recovery.json")
        recoveryFile.writeText(
            recoveryFile.readText().replaceFirst("{", """{"futureField": 42,""")
        )

        val restored = store.restore()
        assertEquals("a", restored?.tabs?.single()?.filePath)
    }

    @Test
    fun clear_removesPersistedState() = runBlocking {
        val store = store()
        store.save(snapshot(RecoveryTabEntry("a", "b", 0, 0)))
        val recoveryFile = File(File(tmp.root, "recovery"), "recovery.json")
        assertTrue(recoveryFile.isFile)

        store.clear()

        assertFalse(recoveryFile.exists())
        assertNull(store.restore())
    }

    @Test
    fun clear_isSafeWhenNothingSaved() {
        store().clear() // must not throw
    }

    @Test
    fun save_isAtomic_noTempFileLeftBehind() = runBlocking {
        val store = store()
        store.save(snapshot(RecoveryTabEntry("a", "b", 0, 0)))

        val recoveryDir = File(tmp.root, "recovery")
        val files = recoveryDir.listFiles()!!.map { it.name }
        assertEquals(listOf("recovery.json"), files)
    }

    @Test
    fun save_overwritesPreviousSnapshot() = runBlocking {
        val store = store()
        store.save(snapshot(RecoveryTabEntry("a", "first", 0, 0)))
        store.save(snapshot(RecoveryTabEntry("b", "second", 9, 9)))

        val restored = store.restore()
        assertEquals(1, restored!!.tabs.size)
        assertEquals("b", restored.tabs.single().filePath)
    }
}
