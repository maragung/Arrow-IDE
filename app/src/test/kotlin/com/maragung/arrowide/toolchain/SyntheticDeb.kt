package com.maragung.arrowide.toolchain

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Builds synthetic `.deb` archives for tests, mirroring the real Termux
 * layout: an `ar` container with `debian-binary` and `data.tar.xz`
 * members, the payload rooted at `./usr/`.
 */
object SyntheticDeb {

    /** One `data.tar` entry. Exactly one of the kinds applies. */
    data class Entry(
        val path: String,
        val content: ByteArray = ByteArray(0),
        val mode: Int = 0b110_100_100, // 0644
        val symlinkTarget: String? = null,
        val isDir: Boolean = false,
    )

    /** Regular file entry. */
    fun file(path: String, content: String, mode: Int = 0b110_100_100) =
        Entry(path = path, content = content.toByteArray(Charsets.UTF_8), mode = mode)

    /** Executable regular file entry (0755). */
    fun executable(path: String, content: String) =
        file(path, content, mode = 0b111_101_101)

    /** Directory entry ([path] should end with `/`). */
    fun dir(path: String) = Entry(path = path, isDir = true, mode = 0b111_101_101)

    /** Symlink entry pointing at [target]. */
    fun symlink(path: String, target: String) =
        Entry(path = path, symlinkTarget = target, mode = 0b111_111_111)

    /**
     * Writes a `.deb` with the given data entries to [dest] and returns it.
     */
    fun build(dest: File, entries: List<Entry>): File {
        val dataTarXz = buildDataTarXz(entries)

        FileOutputStream(dest).use { fileOut ->
            ArArchiveOutputStream(fileOut).use { ar ->
                ar.putArchiveEntry(ArArchiveEntry("debian-binary", 4L))
                ar.write("2.0\n".toByteArray(Charsets.US_ASCII))
                ar.closeArchiveEntry()

                ar.putArchiveEntry(ArArchiveEntry("data.tar.xz", dataTarXz.size.toLong()))
                ar.write(dataTarXz)
                ar.closeArchiveEntry()

                ar.finish()
            }
        }
        return dest
    }

    private fun buildDataTarXz(entries: List<Entry>): ByteArray {
        val buffer = ByteArrayOutputStream()
        XZCompressorOutputStream(buffer).use { xzOut ->
            TarArchiveOutputStream(xzOut).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (entry in entries) {
                    val tarEntry = when {
                        entry.isDir -> TarArchiveEntry(normalizeDir(entry.path))
                        entry.symlinkTarget != null ->
                            TarArchiveEntry(entry.path, TarConstants.LF_SYMLINK)
                        else -> TarArchiveEntry(entry.path)
                    }
                    tarEntry.setMode(entry.mode)
                    if (entry.symlinkTarget != null) {
                        tarEntry.setLinkName(entry.symlinkTarget)
                    } else if (!entry.isDir) {
                        tarEntry.setSize(entry.content.size.toLong())
                    }
                    tar.putArchiveEntry(tarEntry)
                    if (entry.symlinkTarget == null && !entry.isDir) {
                        tar.write(entry.content)
                    }
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return buffer.toByteArray()
    }

    private fun normalizeDir(path: String): String =
        if (path.endsWith("/")) path else "$path/"
}
