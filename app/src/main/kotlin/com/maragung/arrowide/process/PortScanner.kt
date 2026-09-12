package com.maragung.arrowide.process

import com.maragung.arrowide.terminal.TerminalSession
import java.io.File

/** Transport a listening socket was found on. */
enum class PortProtocol { TCP, TCP6 }

/**
 * One listening socket owned by this app's uid (plan #28 Port Manager).
 *
 * @param port       listening port number
 * @param protocol   TCP (IPv4) or TCP6 (IPv6)
 * @param pid        owning process id, or null when it could not be resolved
 * @param sessionIds terminal sessions whose PTY child owns the socket
 */
data class ListeningPort(
    val port: Int,
    val protocol: PortProtocol,
    val pid: Long?,
    val sessionIds: List<Int>,
)

/**
 * Detects the app's own listening TCP sockets (plan #27/#28) by parsing
 * `/proc/net/tcp{,6}` and resolving socket inodes to owning pids under
 * `/proc/<pid>/fd/`.
 *
 * Only rows belonging to [uid] are considered, and only processes readable
 * by the app (same uid) can be resolved — the scanner never sees or touches
 * other apps' sockets or processes, honoring the sandbox boundary (plan #25).
 *
 * @param uid      the app's own Linux uid (from `android.os.Process.myUid()`)
 * @param reader   seam over /proc file reads, so tests can feed fake tables
 * @param procRoot the /proc mount, so tests can point at a fake tree
 */
class PortScanner(
    private val uid: Int,
    private val reader: (String) -> List<String> = { File(it).readLines() },
    private val procRoot: File = File("/proc"),
) {

    /**
     * Scans once and maps every listening socket to its owning sessions.
     *
     * The result is sorted by port. Sockets whose owning process cannot be
     * resolved (already exited, or unreadable) appear with `pid == null` —
     * honest absence rather than a guess.
     */
    fun scan(sessions: List<TerminalSession>): List<ListeningPort> {
        val rows = listeningRows() ?: return emptyList()
        if (rows.isEmpty()) return emptyList()

        val inodeToPid = resolveInodeOwners()
        val pidToSessions = sessions
            .groupBy { it.process.pid }
            .mapValues { (_, owned) -> owned.map { it.id } }

        return rows
            .map { (port, protocol, inode) ->
                val pid = inodeToPid[inode]
                ListeningPort(
                    port = port,
                    protocol = protocol,
                    pid = pid,
                    sessionIds = pid?.let(pidToSessions::get).orEmpty(),
                )
            }
            .sortedBy { it.port }
    }

    /** Parsed listening rows: (port, protocol, socket inode). */
    private fun listeningRows(): List<Triple<Int, PortProtocol, String>>? {
        val rows = mutableListOf<Triple<Int, PortProtocol, String>>()
        for ((path, protocol) in listOf(
            "/proc/net/tcp" to PortProtocol.TCP,
            "/proc/net/tcp6" to PortProtocol.TCP6,
        )) {
            val lines = runCatching { reader(path) }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val row = parseRow(line, uid) ?: continue
                rows += Triple(row.first, protocol, row.second)
            }
        }
        return rows
    }

    /**
     * Parses one `/proc/net/tcp` row into `(port, inode)` when it is a
     * LISTEN socket owned by [uid]; null otherwise.
     *
     * Row shape: `sl local_address rem_address st uid timeout inode ...`,
     * where addresses are `HEXADDR:HEXPORT` (the port is plain big-endian
     * hex, e.g. `0BB8` = 3000).
     */
    private fun parseRow(line: String, uid: Int): Pair<Int, String>? {
        val columns = line.trim().split(Regex("\\s+"))
        if (columns.size < 10) return null
        val local = columns[1]
        val state = columns[3]
        val rowUid = columns[7].toIntOrNull() ?: return null
        val inode = columns[9]
        if (state != LISTEN_STATE) return null
        if (rowUid != uid) return null
        val port = local.substringAfterLast(':').toIntOrNull(16) ?: return null
        return port to inode
    }

    /**
     * Builds socket-inode -> pid by walking readable `/proc/<pid>/fd/`
     * entries. Symlinks there read `socket:[<inode>]`.
     */
    private fun resolveInodeOwners(): Map<String, Long> {
        val inodeToPid = mutableMapOf<String, Long>()
        val pids = procRoot.listFiles()
            ?.filter { it.name.toLongOrNull() != null }
            .orEmpty()
        for (pidDir in pids) {
            val fdDir = File(pidDir, "fd")
            if (!fdDir.canRead()) continue
            val fds = runCatching { fdDir.listFiles() }.getOrNull() ?: continue
            val pid = pidDir.name.toLong()
            for (fd in fds) {
                val target = runCatching { fd.canonicalPath }.getOrNull() ?: continue
                val inode = target.substringAfter(SOCKET_PREFIX, "")
                    .removeSuffix("]")
                if (target.startsWith(SOCKET_PREFIX) && inode.isNotEmpty()) {
                    inodeToPid[inode] = pid
                }
            }
        }
        return inodeToPid
    }

    private companion object {
        const val LISTEN_STATE = "0A"
        const val SOCKET_PREFIX = "socket:["
    }
}
