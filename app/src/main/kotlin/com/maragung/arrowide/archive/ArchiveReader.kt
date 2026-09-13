package com.maragung.arrowide.archive

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/** One member of an archive as listed by [ArchiveReader]. */
data class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
)

/** Result of [ArchiveReader.read]: a parsed listing, or "this is not an archive". */
sealed interface ArchiveContents {
    data class Zip(val entries: List<ArchiveEntry>) : ArchiveContents
    data class Tar(val entries: List<ArchiveEntry>) : ArchiveContents
    data object NotAnArchive : ArchiveContents
}

/**
 * Reads archive listings without extracting (plan #30 Archive Tools).
 *
 * ZIP listings come from the End of Central Directory record and central
 * directory only — nothing is decompressed. TAR listings walk the 512-byte
 * headers, skipping data blocks; `.tar.gz` / `.tgz` are streamed through
 * [GZIPInputStream] because gzip decompresses sequentially. `.tar.bz2` is
 * not listed (no sequential codec available here).
 *
 * Pure Kotlin — no Android dependencies; safe to run in JVM unit tests.
 *
 * @param maxEntries listing stops after this many entries
 */
class ArchiveReader(private val maxEntries: Int = 10_000) {

    /**
     * Lists [file]'s contents. The format is detected from magic bytes:
     * `PK\x03\x04` / `PK\x05\x06` / `PK\x07\x08` for ZIP, `ustar` at
     * offset 257 for TAR, `1F 8B` for gzip (streamed through the tar
     * parser). Anything else — including `.tar.bz2` — yields
     * [ArchiveContents.NotAnArchive].
     */
    fun read(file: File): ArchiveContents {
        val head = ByteArray(4)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) return ArchiveContents.NotAnArchive
            raf.readFully(head)
        }
        return when {
            isZipMagic(head) -> ArchiveContents.Zip(zipEntries(file))
            isGzipMagic(head) -> {
                val scan = try {
                    file.inputStream().buffered().use { stream ->
                        GZIPInputStream(stream).use { scanTar(it, wanted = null, maxBytes = 0) }
                    }
                } catch (_: Exception) { // corrupt / truncated gzip
                    null
                }
                if (scan?.isTar == true) {
                    ArchiveContents.Tar(scan.entries)
                } else {
                    ArchiveContents.NotAnArchive
                }
            }
            isTar(file) -> ArchiveContents.Tar(tarEntries(file))
            else -> ArchiveContents.NotAnArchive
        }
    }

    /**
     * Extracts a single entry's text content for preview (plan #30: "User
     * dapat memilih file di dalam archive untuk preview"). ZIP entries are
     * located through the central directory and read with random access —
     * only that entry's bytes are touched (raw DEFLATE via [Inflater] or
     * STORED as-is). TAR / TAR.GZ entries are found by a sequential scan.
     *
     * Returns null when the entry is missing, is a directory, needs zip64,
     * or is larger than [maxBytes]. Content is decoded as UTF-8.
     */
    fun readEntry(file: File, entryName: String, maxBytes: Long = 1_000_000): String? {
        val head = ByteArray(4)
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4) return null
            raf.readFully(head)
        }
        return when {
            isZipMagic(head) -> zipEntryContent(file, entryName, maxBytes)
            isGzipMagic(head) -> try {
                file.inputStream().buffered().use { stream ->
                    GZIPInputStream(stream).use { scanTar(it, entryName, maxBytes).content }
                }
            } catch (_: Exception) { // corrupt / truncated gzip
                null
            }
            isTar(file) -> file.inputStream().buffered().use { stream ->
                scanTar(stream, entryName, maxBytes).content
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ ZIP

    /** Central directory + EOCD listing; never decompresses member data. */
    private fun zipEntries(file: File): List<ArchiveEntry> {
        RandomAccessFile(file, "r").use { raf ->
            val eocd = findEocd(raf) ?: return emptyList()
            var count = le16(eocd, 10)
            val cdOffset = le32(eocd, 16).toLong() and 0xFFFFFFFFL
            if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) count = Int.MAX_VALUE // zip64 marker

            val entries = mutableListOf<ArchiveEntry>()
            val header = ByteArray(46)
            raf.seek(cdOffset)
            while (entries.size < maxEntries && entries.size < count) {
                if (raf.read(header) != 46) break
                if (le32(header, 0) != CENTRAL_DIR_SIGNATURE) break
                val flags = le16(header, 8)
                val size = le32(header, 24).toLong() and 0xFFFFFFFFL
                val compressedSize = le32(header, 20).toLong() and 0xFFFFFFFFL
                val localOffset = le32(header, 42).toLong() and 0xFFFFFFFFL
                if (size == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL ||
                    localOffset == 0xFFFFFFFFL
                ) {
                    break // zip64 — politely return what we have so far
                }
                val nameLen = le16(header, 28)
                val extraLen = le16(header, 30)
                val commentLen = le16(header, 32)
                val nameBytes = ByteArray(nameLen)
                if (raf.read(nameBytes) != nameLen) break
                raf.seek(raf.filePointer + extraLen + commentLen)
                val name = decodeName(nameBytes, flags and 0x800 != 0)
                entries += ArchiveEntry(
                    name = name,
                    isDirectory = name.endsWith("/") || (le32(header, 38) and 0x10) != 0,
                    size = size,
                    compressedSize = compressedSize,
                )
            }
            return entries
        }
    }

    /** Locates the End of Central Directory record by scanning backwards. */
    private fun findEocd(raf: RandomAccessFile): ByteArray? {
        val fileLen = raf.length()
        if (fileLen < 22) return null
        val windowLen = minOf(fileLen, MAX_EOCD_SCAN.toLong()).toInt()
        val window = ByteArray(windowLen)
        raf.seek(fileLen - windowLen)
        raf.readFully(window)
        // Scan back so the last (innermost) EOCD wins; the comment may
        // itself contain the signature. Tolerate trailing bytes after the
        // recorded comment.
        for (i in windowLen - 22 downTo 0) {
            if (le32(window, i) == EOCD_SIGNATURE) {
                val commentLen = le16(window, i + 20)
                if (i + 22 + commentLen <= windowLen) {
                    return window.copyOfRange(i, i + 22)
                }
            }
        }
        return null
    }

    /** Reads one entry's bytes via random access: no full-file decompression. */
    private fun zipEntryContent(file: File, entryName: String, maxBytes: Long): String? {
        RandomAccessFile(file, "r").use { raf ->
            val eocd = findEocd(raf) ?: return null
            var count = le16(eocd, 10)
            if (count == 0xFFFF || le32(eocd, 16).toLong() and 0xFFFFFFFFL == 0xFFFFFFFFL) {
                count = Int.MAX_VALUE
            }
            val header = ByteArray(46)
            raf.seek(le32(eocd, 16).toLong() and 0xFFFFFFFFL)
            repeat(count) {
                if (raf.read(header) != 46) return null
                if (le32(header, 0) != CENTRAL_DIR_SIGNATURE) return null
                val flags = le16(header, 8)
                val method = le16(header, 10)
                val size = le32(header, 24).toLong() and 0xFFFFFFFFL
                val compressedSize = le32(header, 20).toLong() and 0xFFFFFFFFL
                val localOffset = le32(header, 42).toLong() and 0xFFFFFFFFL
                if (size == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL ||
                    localOffset == 0xFFFFFFFFL
                ) {
                    return null // zip64 not supported for preview
                }
                val nameLen = le16(header, 28)
                val extraLen = le16(header, 30)
                val commentLen = le16(header, 32)
                val nameBytes = ByteArray(nameLen)
                if (raf.read(nameBytes) != nameLen) return null
                raf.seek(raf.filePointer + extraLen + commentLen)
                if (decodeName(nameBytes, flags and 0x800 != 0) != entryName) return@repeat

                if (entryName.endsWith("/") || (le32(header, 38) and 0x10) != 0) return null
                if (size > maxBytes) return null
                // Local header: skip its own (possibly different) name/extra.
                val local = ByteArray(30)
                raf.seek(localOffset)
                if (raf.read(local) != 30 || le32(local, 0) != LOCAL_HEADER_SIGNATURE) return null
                raf.seek(localOffset + 30 + le16(local, 26) + le16(local, 28))
                val data = ByteArray(compressedSize.toInt())
                raf.readFully(data)
                return when (method) {
                    METHOD_STORED -> inflateBytes(data, size, raw = false)
                    METHOD_DEFLATED -> inflateBytes(data, size, raw = true)
                    else -> null
                }?.let { bytes -> String(bytes, Charsets.UTF_8) }
            }
            return null
        }
    }

    /** STORED copies verbatim; DEFLATE uses a raw [Inflater] (no zlib header). */
    private fun inflateBytes(data: ByteArray, size: Long, raw: Boolean): ByteArray? = try {
        if (!raw) {
            data.copyOf(size.toInt())
        } else {
            val inflater = Inflater(true)
            inflater.setInput(data)
            val out = ByteArray(size.toInt())
            var outPos = 0
            while (outPos < out.size && !inflater.finished()) {
                val n = inflater.inflate(out, outPos, out.size - outPos)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                outPos += n
            }
            inflater.end()
            if (outPos == out.size) out else out.copyOf(outPos)
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ TAR

    /** Plain (uncompressed) tar: requires `ustar` magic at offset 257. */
    private fun tarEntries(file: File): List<ArchiveEntry> {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < TAR_BLOCK_SIZE) return emptyList()
            val probe = ByteArray(TAR_BLOCK_SIZE)
            raf.readFully(probe)
            if (isAllZero(probe)) return emptyList() // empty tar
            if (!hasUstarMagic(probe)) return emptyList()
        }
        return file.inputStream().buffered().use { scanTar(it, wanted = null, maxBytes = 0).entries }
    }

    private fun isTar(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < TAR_BLOCK_SIZE) return false
            val probe = ByteArray(TAR_BLOCK_SIZE)
            raf.readFully(probe)
            return isAllZero(probe) || hasUstarMagic(probe)
        }
    }

    private class TarScan(
        val entries: List<ArchiveEntry>,
        val content: String?,
        val isTar: Boolean,
    )

    /**
     * One pass over tar blocks that both lists entries (applying
     * [maxEntries]) and, when [wanted] is set, captures that entry's text
     * content (capped at [maxBytes]). Handles GNU long names ('L') by
     * applying the name to the following entry, and skips PAX ('x'/'g')
     * data blocks. Stops at the first all-zero block or EOF.
     */
    private fun scanTar(input: InputStream, wanted: String?, maxBytes: Long): TarScan {
        val entries = mutableListOf<ArchiveEntry>()
        val header = ByteArray(TAR_BLOCK_SIZE)
        var longName: String? = null
        var first = true
        var content: String? = null

        while (true) {
            if (!readFully(input, header)) {
                // A stream that cannot even deliver the first block (or only
                // part of it) is not a tar — even empty tars end with two
                // zero blocks.
                if (first) return TarScan(emptyList(), null, isTar = false)
                break // truncated archive: keep what was listed so far
            }
            if (first) {
                first = false
                // Only ustar counts (plain tars were already validated by
                // [isTar], so this is effectively a no-op for them).
                if (!hasUstarMagic(header)) return TarScan(emptyList(), null, isTar = false)
            }
            if (isAllZero(header)) break // two zero blocks end the archive
            val size = tarSize(header)
            val dataBlocks = ((size + TAR_BLOCK_SIZE - 1) / TAR_BLOCK_SIZE).toInt()
            when (header[156].toInt()) {
                TYPE_GNU_LONG_NAME -> {
                    val data = ByteArray(dataBlocks * TAR_BLOCK_SIZE)
                    if (!readFully(input, data)) break
                    longName = cString(data, 0, minOf(size, data.size.toLong()).toInt())
                }
                TYPE_PAX_HEADER, TYPE_PAX_GLOBAL -> skipFully(input, dataBlocks * TAR_BLOCK_SIZE.toLong())
                else -> {
                    val name = longName ?: tarName(header)
                    longName = null
                    val isDirectory = header[156].toInt() == TYPE_DIRECTORY || name.endsWith("/")
                    entries += ArchiveEntry(name, isDirectory, size, size)
                    if (wanted == name && !isDirectory) {
                        content = if (size > maxBytes) {
                            null
                        } else {
                            val data = ByteArray(size.toInt())
                            if (readFully(input, data)) String(data, Charsets.UTF_8) else null
                        }
                        break
                    }
                    skipFully(input, dataBlocks * TAR_BLOCK_SIZE.toLong())
                    if (entries.size >= maxEntries) break
                }
            }
        }
        return TarScan(entries, content, isTar = true)
    }

    // -------------------------------------------------------------- helpers

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(TAR_BLOCK_SIZE)
        while (remaining > 0) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n < 0) return
            remaining -= n
        }
    }

    /** name field + prefix field joined with '/' (POSIX ustar). */
    private fun tarName(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val prefix = cString(header, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** Octal size field at 124, with GNU base-256 as fallback. */
    private fun tarSize(header: ByteArray): Long {
        val field = header.copyOfRange(124, 136)
        if (field[0].toInt() and 0x80 != 0) { // GNU base-256 encoding
            var value = (field[0].toInt() and 0x7F).toLong()
            for (i in 1 until field.size) {
                value = (value shl 8) or (field[i].toLong() and 0xFF)
            }
            return value
        }
        val text = cString(field, 0, field.size).trim(' ')
        return if (text.isEmpty()) 0L else text.toLongOrNull(radix = 8) ?: 0L
    }

    private fun cString(bytes: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = minOf(off + len, bytes.size)
        while (end < max && bytes[end] != 0.toByte()) end++
        return String(bytes, off, end - off, Charsets.UTF_8)
    }

    /** Flag bit 11 means UTF-8; otherwise CP437 — approximated by ISO-8859-1. */
    private fun decodeName(bytes: ByteArray, utf8: Boolean): String {
        if (utf8 || bytes.isEmpty()) return String(bytes, Charsets.UTF_8)
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun isAllZero(bytes: ByteArray): Boolean = bytes.all { it == 0.toByte() }

    private fun hasUstarMagic(header: ByteArray): Boolean =
        header.size >= 262 && String(header, 257, 5, Charsets.US_ASCII) == "ustar"

    private fun isZipMagic(head: ByteArray): Boolean =
        head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && (
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte() ||
                head[2] == 0x05.toByte() && head[3] == 0x06.toByte() ||
                head[2] == 0x07.toByte() && head[3] == 0x08.toByte()
            )

    private fun isGzipMagic(head: ByteArray): Boolean =
        head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()

    private companion object {
        const val MAX_EOCD_SCAN = 66_553 + 22 // 64K comment + fixed EOCD
        const val TAR_BLOCK_SIZE = 512

        const val CENTRAL_DIR_SIGNATURE = 0x02014b50
        const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        const val EOCD_SIGNATURE = 0x06054b50

        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        const val TYPE_GNU_LONG_NAME = 'L'.code
        const val TYPE_PAX_HEADER = 'x'.code
        const val TYPE_PAX_GLOBAL = 'g'.code
        const val TYPE_DIRECTORY = '5'.code
    }
}

/** Little-endian unsigned 16-bit at [off]. */
private fun le16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

/** Little-endian unsigned 32-bit at [off]. */
private fun le32(b: ByteArray, off: Int): Int =
    le16(b, off) or (le16(b, off + 2) shl 16)

/**
 * Whether [name] looks like an archive this reader can open:
 * `.zip`, `.jar`, `.apk`, `.tar`, `.tar.gz`, `.tgz`, `.tar.bz2`.
 */
fun isArchiveName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") ||
        lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
        lower.endsWith(".tar.bz2")
}
