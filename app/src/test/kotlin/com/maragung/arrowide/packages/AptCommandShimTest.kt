package com.maragung.arrowide.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * [AptCommandShim] contract tests: shim placement/permissions and the
 * queue round-trip. The script itself is executed through the host
 * `/bin/sh` when available; otherwise those assertions are skipped.
 */
class AptCommandShimTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shim(): AptCommandShim = AptCommandShim(tmp.root)

    @Test
    fun ensureInstalledWritesBothShimsToBin() = runBlocking {
        shim().ensureInstalled()

        assertTrue(File(tmp.root, "bin/apt").isFile)
        assertTrue(File(tmp.root, "bin/apt-get").isFile)
    }

    @Test
    fun ensureInstalledLeavesShimsExecutable() = runBlocking {
        shim().ensureInstalled()

        assertTrue(File(tmp.root, "bin/apt").canExecute())
        assertTrue(File(tmp.root, "bin/apt-get").canExecute())
    }

    @Test
    fun isInstalledFlipsAfterEnsureInstalled() = runBlocking {
        val shim = shim()
        assertFalse(shim.isInstalled())

        shim.ensureInstalled()

        assertTrue(shim.isInstalled())
    }

    @Test
    fun queuedCommandsParsesLinesWrittenByTheScript() = runBlocking {
        assumeTrue(File("/bin/sh").exists())
        val shim = shim()
        shim.ensureInstalled()

        val process = ProcessBuilder(
            "/bin/sh",
            File(tmp.root, "bin/apt").absolutePath,
            "install",
            "nodejs",
        ).start()
        assertEquals(0, process.waitFor())

        assertEquals(listOf("apt install nodejs"), shim.queuedCommands())
    }

    @Test
    fun queuedCommandsClearsTheQueueAfterReading() = runBlocking {
        assumeTrue(File("/bin/sh").exists())
        val shim = shim()
        shim.ensureInstalled()

        val process = ProcessBuilder(
            "/bin/sh",
            File(tmp.root, "bin/apt").absolutePath,
            "install",
            "python",
        ).start()
        assertEquals(0, process.waitFor())

        assertEquals(listOf("apt install python"), shim.queuedCommands())
        assertEquals(emptyList<String>(), shim.queuedCommands())
    }

    @Test
    fun queuedCommandsIsEmptyWithoutAQueueFile() = runBlocking {
        assertEquals(emptyList<String>(), shim().queuedCommands())
    }

    @Test
    fun peekCommandsReturnsLinesWithoutClearingTheQueue() = runBlocking {
        File(tmp.root, "var").mkdirs()
        File(tmp.root, "var/apt-queue")
            .writeText("apt install nodejs\napt update\n")

        val shim = shim()

        assertEquals(listOf("apt install nodejs", "apt update"), shim.peekCommands())
        assertEquals(listOf("apt install nodejs", "apt update"), shim.peekCommands())
    }

    @Test
    fun peekCommandsSkipsBlankLinesLikeQueuedCommands() = runBlocking {
        File(tmp.root, "var").mkdirs()
        File(tmp.root, "var/apt-queue").writeText("apt install git\n\n   \napt update\n")

        assertEquals(
            listOf("apt install git", "apt update"),
            shim().peekCommands()
        )
    }

    @Test
    fun peekCommandsIsEmptyWithoutAQueueFile() = runBlocking {
        assertEquals(emptyList<String>(), shim().peekCommands())
    }

    @Test
    fun peekThenDrainReturnsTheSameLines() = runBlocking {
        File(tmp.root, "var").mkdirs()
        File(tmp.root, "var/apt-queue").writeText("apt install python\n")

        val shim = shim()
        val peeked = shim.peekCommands()

        assertEquals(peeked, shim.queuedCommands())
        assertEquals(emptyList<String>(), shim.peekCommands())
    }

    @Test
    fun requeueAppendsLinesToAnExistingQueue() = runBlocking {
        File(tmp.root, "var").mkdirs()
        File(tmp.root, "var/apt-queue").writeText("apt install git\n")

        shim().requeue(listOf("apt update", "apt install curl"))

        assertEquals(
            listOf("apt install git", "apt update", "apt install curl"),
            shim().peekCommands()
        )
    }

    @Test
    fun requeueCreatesMissingParentDirectories() = runBlocking {
        assertFalse(File(tmp.root, "var").isDirectory)

        shim().requeue(listOf("apt install nodejs", "apt update"))

        assertEquals(
            listOf("apt install nodejs", "apt update"),
            shim().queuedCommands()
        )
    }

    @Test
    fun requeueWithNoLinesLeavesAnAbsentQueueAbsent() = runBlocking {
        shim().requeue(emptyList())

        assertFalse(File(tmp.root, "var/apt-queue").isFile)
        assertEquals(emptyList<String>(), shim().peekCommands())
    }

    @Test
    fun requeueRestoresDrainedLinesForTheNextApply() = runBlocking {
        File(tmp.root, "var").mkdirs()
        File(tmp.root, "var/apt-queue").writeText("apt remove vim\n")

        val shim = shim()
        val drained = shim.queuedCommands()
        assertEquals(emptyList<String>(), shim.peekCommands())

        shim().requeue(drained)

        assertEquals(listOf("apt remove vim"), shim.peekCommands())
    }
}
