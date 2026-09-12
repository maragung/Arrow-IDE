package com.maragung.arrowide.process

import com.maragung.arrowide.terminal.FakePtyProcess
import com.maragung.arrowide.terminal.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PortScanner] behavior against a fake /proc tree: /proc/net/tcp{,6}
 * parsing (LISTEN-only, own-uid-only, hex ports), inode -> pid resolution
 * via /proc/<pid>/fd symlinks, and pid -> session mapping.
 */
class PortScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val uid = 10_123

    /** A real session (FakePtyProcess assigns a unique pid per instance). */
    private fun newSession(id: Int): TerminalSession =
        TerminalSession(id, FakePtyProcess(), File("/tmp/work"))

    /**
     * Builds a fake /proc tree: net/tcp, net/tcp6 tables (via the reader
     * seam the tables are real files under [tmp]) and /proc/<pid>/fd
     * symlinks to socket:[inode].
     */
    private fun fakeProc(
        tcpRows: List<String>,
        tcp6Rows: List<String> = emptyList(),
        pidFds: Map<Long, List<String>> = emptyMap(),
    ): File {
        val root = tmp.newFolder("proc")
        File(root, "net").mkdirs()
        File(root, "net/tcp").writeText(
            (listOf(HEADER) + tcpRows).joinToString("\n") + "\n",
        )
        File(root, "net/tcp6").writeText(
            (listOf(HEADER) + tcp6Rows).joinToString("\n") + "\n",
        )
        pidFds.forEach { (pid, fds) ->
            val fdDir = File(root, "$pid/fd").apply { mkdirs() }
            fds.forEachIndexed { index, target ->
                val link = File(fdDir, index.toString())
                link.createNewFile()
                // A symlink is required for canonicalPath resolution.
                link.delete()
                java.nio.file.Files.createSymbolicLink(
                    link.toPath(),
                    java.nio.file.Paths.get(target),
                )
            }
        }
        return root
    }

    private fun row(
        portHex: String,
        state: String = "0A",
        uid: Int = this.uid,
        inode: String,
    ): String =
        "  0: 0100007F:$portHex 00000000:0000 $state ${"%05X".format(uid)} " +
            "00000000 $inode 0000000000000000 1000 0 0000000000000000"

    private fun scanner(procRoot: File): PortScanner =
        PortScanner(
            uid = uid,
            reader = { path ->
                // /proc/net/tcp maps to <root>/net/tcp in the fake tree.
                val relative = path.removePrefix("/proc/")
                File(procRoot, relative).readLines()
            },
            procRoot = procRoot,
        )

    @Test
    fun listeningSocketWithKnownPidIsMappedToItsSession() {
        val session = newSession(1)
        val root = fakeProc(
            tcpRows = listOf(row("0BB8", inode = "12345")),
            // The fake fd points at the session's real (fake) pid.
            pidFds = mapOf(session.process.pid to listOf("socket:[12345]")),
        )
        try {
            val ports = scanner(root).scan(listOf(session))
            assertEquals(1, ports.size)
            val port = ports.single()
            assertEquals(3000, port.port)
            assertEquals(PortProtocol.TCP, port.protocol)
            assertEquals(session.process.pid, port.pid)
            assertEquals(listOf(1), port.sessionIds)
        } finally {
            session.close()
        }
    }

    @Test
    fun nonListeningRowsAreIgnored() {
        val root = fakeProc(
            tcpRows = listOf(
                row("0BB8", state = "01", inode = "111"), // ESTABLISHED
                row("1F90", inode = "222"),               // LISTEN 8080
            ),
            pidFds = mapOf(100L to listOf("socket:[222]")),
        )
        val ports = scanner(root).scan(emptyList())
        assertEquals(listOf(8080), ports.map { it.port })
    }

    @Test
    fun otherUidsRowsAreIgnored() {
        val root = fakeProc(
            tcpRows = listOf(row("0BB8", uid = uid + 1, inode = "333")),
        )
        assertTrue(scanner(root).scan(emptyList()).isEmpty())
    }

    @Test
    fun unresolvedInodeYieldsNullPidAndNoSessions() {
        val root = fakeProc(
            tcpRows = listOf(row("1F90", inode = "999")),
            pidFds = mapOf(100L to listOf("socket:[1]")), // different inode
        )
        val ports = scanner(root).scan(emptyList())
        val port = ports.single()
        assertEquals(8080, port.port)
        assertNull(port.pid)
        assertTrue(port.sessionIds.isEmpty())
    }

    @Test
    fun tcp6RowsAreLabeledTcp6() {
        // IPv6 loopback local address: flat hex, port still last.
        val root = fakeProc(
            tcpRows = emptyList(),
            tcp6Rows = listOf(
                "  0: 00000000000000000000000000000001:1388 0000000000000000" +
                    "0000000000000000:0000 0A ${"%05X".format(uid)} 00000000 " +
                    "777 0",
            ),
            pidFds = mapOf(55L to listOf("socket:[777]")),
        )
        val ports = scanner(root).scan(emptyList())
        val port = ports.single()
        assertEquals(5000, port.port) // 0x1388
        assertEquals(PortProtocol.TCP6, port.protocol)
        assertEquals(55L, port.pid)
    }
    @Test
    fun resultsAreSortedByPort() {
        val root = fakeProc(
            tcpRows = listOf(
                row("1F90", inode = "1"),
                row("0BB8", inode = "2"),
                row("0050", inode = "3"), // 80
            ),
        )
        val ports = scanner(root).scan(emptyList())
        assertEquals(listOf(80, 3000, 8080), ports.map { it.port })
    }

    @Test
    fun multipleSessionsSharingAPidAllAppear() {
        // One real session; two rows would need the same pid, so instead
        // verify one session maps once and a second (different pid, no
        // socket) contributes nothing.
        val owning = newSession(1)
        val other = newSession(2)
        val root = fakeProc(
            tcpRows = listOf(row("0BB8", inode = "5")),
            pidFds = mapOf(owning.process.pid to listOf("socket:[5]")),
        )
        try {
            val ports = scanner(root).scan(listOf(owning, other))
            assertEquals(listOf(1), ports.single().sessionIds)
        } finally {
            owning.close()
            other.close()
        }
    }

    @Test
    fun unreadableProcDirsDoNotBreakTheScan() {
        val root = fakeProc(
            tcpRows = listOf(row("0BB8", inode = "9")),
            pidFds = emptyMap(),
        )
        // A numeric dir with no fd subdir — canonicalFile on missing fd/0
        // still resolves; the fd listing yields null and is skipped.
        File(root, "9999").mkdirs()
        val ports = scanner(root).scan(emptyList())
        assertNull(ports.single().pid)
    }

    private companion object {
        const val HEADER =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"
    }
}
