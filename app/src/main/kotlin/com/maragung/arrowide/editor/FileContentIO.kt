package com.maragung.arrowide.editor

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Text encodings [FileContentIO] can round-trip. */
enum class FileEncoding(val charsetName: String) {
    UTF_8("UTF-8"),
    UTF_16LE("UTF-16LE"),
    UTF_16BE("UTF-16BE")
}

/** Line ending flavors detected by [FileContentIO]. */
enum class LineEnding(val chars: String) {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r");

    companion object {
        /** Fallback when a document has no line endings at all. */
        val DEFAULT = LF
    }
}

/**
 * The full in-memory representation of an open file.
 *
 * @property text the decoded text; line endings are preserved verbatim (a
 *   mixed-endings document stays mixed — nothing is ever normalized).
 * @property lineEnding the dominant line ending, kept so writers can make
 *   informed decisions; [FileContentIO.write] never rewrites endings.
 * @property encoding the encoding the file was read with / should be written with.
 * @property hadBom whether a byte-order mark was present and should be re-emitted.
 */
data class FileContent(
    val text: String,
    val lineEnding: LineEnding,
    val encoding: FileEncoding,
    val hadBom: Boolean
)

/**
 * Careful text file IO: BOM-aware decoding and crash-safe atomic writes.
 * Pure Kotlin, JVM-testable.
 */
object FileContentIO {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * Reads [file] completely. Encoding is detected from the BOM only
     * (UTF-8 with/without BOM, UTF-16LE, UTF-16BE); files without a BOM are
     * read as UTF-8. The dominant line ending is recorded but the text keeps
     * whatever endings it contains.
     *
     * @throws IOException if the file cannot be read.
     */
    @Throws(IOException::class)
    fun read(file: File): FileContent {
        val bytes = file.readBytes()
        var encoding = FileEncoding.UTF_8
        var hadBom = false
        var offset = 0
        if (bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) {
            encoding = FileEncoding.UTF_8
            hadBom = true
            offset = 3
        } else if (bytes.size >= 2 && bytes[0] == UTF16LE_BOM[0] && bytes[1] == UTF16LE_BOM[1]) {
            encoding = FileEncoding.UTF_16LE
            hadBom = true
            offset = 2
        } else if (bytes.size >= 2 && bytes[0] == UTF16BE_BOM[0] && bytes[1] == UTF16BE_BOM[1]) {
            encoding = FileEncoding.UTF_16BE
            hadBom = true
            offset = 2
        }
        val text = String(bytes, offset, bytes.size - offset, charset(encoding.charsetName))
        return FileContent(
            text = text,
            lineEnding = detectDominantLineEnding(text),
            encoding = encoding,
            hadBom = hadBom
        )
    }

    /**
     * Atomically writes [content] to [file], re-encoding with the recorded
     * encoding and BOM state. The text is written exactly as given — line
     * endings are never normalized.
     *
     * Atomicity: bytes go to a sibling temp file which is fsync'ed and then
     * renamed over the target. A crash mid-write therefore never leaves a
     * truncated/corrupted target behind.
     *
     * @throws IOException if writing or the final rename fails; the target is
     *   left untouched in that case and the temp file is removed.
     */
    @Throws(IOException::class)
    fun write(file: File, content: FileContent) {
        val payload = content.text.toByteArray(charset(content.encoding.charsetName))
        val bom: ByteArray = when {
            !content.hadBom -> ByteArray(0)
            content.encoding == FileEncoding.UTF_8 -> UTF8_BOM
            content.encoding == FileEncoding.UTF_16LE -> UTF16LE_BOM
            content.encoding == FileEncoding.UTF_16BE -> UTF16BE_BOM
            else -> ByteArray(0)
        }
        val bytes = ByteArray(bom.size + payload.size)
        bom.copyInto(bytes)
        payload.copyInto(bytes, bom.size)
        writeAtomic(file, bytes)
    }

    /**
     * Writes [bytes] to [file] via temp-file + rename. Exposed for testing the
     * failure semantics (a failing write/rename must leave the target intact).
     */
    @Throws(IOException::class)
    fun writeAtomic(file: File, bytes: ByteArray) {
        val target = file.absoluteFile
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent ?: File("."), target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // Filesystem without atomic rename support (e.g. some FAT mounts).
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete()
            }
        }
    }

    /**
     * Returns the dominant line ending of [text]: the flavor with the most
     * occurrences. Ties (or no line endings at all) resolve to [LineEnding.LF].
     */
    fun detectDominantLineEnding(text: String): LineEnding {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') {
                    crlf++
                    i += 2
                    continue
                }
                cr++
            } else if (c == '\n') {
                lf++
            }
            i++
        }
        return when {
            crlf > lf && crlf > cr -> LineEnding.CRLF
            cr > lf && cr > crlf -> LineEnding.CR
            else -> LineEnding.LF
        }
    }
}
