package com.maragung.arrowide.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileContentIOTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- reading ----------------------------------------------------------

    @Test
    fun read_defaultsToUtf8WithoutBom() {
        val file = tmp.newFile("plain.txt")
        file.writeText("héllo wörld", Charsets.UTF_8)

        val content = FileContentIO.read(file)

        assertEquals("héllo wörld", content.text)
        assertEquals(FileEncoding.UTF_8, content.encoding)
        assertFalse(content.hadBom)
    }

    @Test
    fun read_detectsUtf8Bom() {
        val file = tmp.newFile("utf8bom.txt")
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray())

        val content = FileContentIO.read(file)

        assertEquals("abc", content.text)
        assertEquals(FileEncoding.UTF_8, content.encoding)
        assertTrue(content.hadBom)
    }

    @Test
    fun read_detectsUtf16LeBom() {
        val file = tmp.newFile("utf16le.txt")
        val payload = "héllo".toByteArray(Charsets.UTF_16LE)
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + payload)

        val content = FileContentIO.read(file)

        assertEquals("héllo", content.text)
        assertEquals(FileEncoding.UTF_16LE, content.encoding)
        assertTrue(content.hadBom)
    }

    @Test
    fun read_detectsUtf16BeBom() {
        val file = tmp.newFile("utf16be.txt")
        val payload = "héllo".toByteArray(Charsets.UTF_16BE)
        file.writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + payload)

        val content = FileContentIO.read(file)

        assertEquals("héllo", content.text)
        assertEquals(FileEncoding.UTF_16BE, content.encoding)
        assertTrue(content.hadBom)
    }

    @Test
    fun read_detectsDominantLineEnding() {
        fun readOf(text: String): FileContent {
            val file = tmp.newFile()
            file.writeText(text, Charsets.UTF_8)
            return FileContentIO.read(file)
        }

        assertEquals(LineEnding.CRLF, readOf("a\r\nb\r\nc").lineEnding)
        assertEquals(LineEnding.LF, readOf("a\nb\nc").lineEnding)
        assertEquals(LineEnding.CR, readOf("a\rb\rc").lineEnding)
        // Mixed: dominant wins (2x CRLF vs 1x LF).
        assertEquals(LineEnding.CRLF, readOf("a\r\nb\nc\r\nd").lineEnding)
        // No endings at all: default LF.
        assertEquals(LineEnding.LF, readOf("abc").lineEnding)
    }

    // ---- round-trips -------------------------------------------------------

    @Test
    fun roundTrip_preservesLfText() {
        val file = tmp.newFile("lf.txt")
        val original = FileContent("line1\nline2\nline3", LineEnding.LF, FileEncoding.UTF_8, hadBom = false)
        FileContentIO.write(file, original)

        assertEquals(original, FileContentIO.read(file))
    }

    @Test
    fun roundTrip_preservesCrlfText() {
        val file = tmp.newFile("crlf.txt")
        val original = FileContent("line1\r\nline2\r\n", LineEnding.CRLF, FileEncoding.UTF_8, hadBom = false)
        FileContentIO.write(file, original)

        assertEquals(original, FileContentIO.read(file))
    }

    @Test
    fun roundTrip_preservesUtf8Bom() {
        val file = tmp.newFile("bom.txt")
        FileContentIO.write(file, FileContent("ça va", LineEnding.LF, FileEncoding.UTF_8, hadBom = true))

        val bytes = file.readBytes()
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.copyOfRange(0, 3))
        val read = FileContentIO.read(file)
        assertTrue(read.hadBom)
        assertEquals("ça va", read.text)
    }

    @Test
    fun roundTrip_preservesUtf16LeWithBom() {
        val file = tmp.newFile("le.txt")
        val original = FileContent("söra", LineEnding.LF, FileEncoding.UTF_16LE, hadBom = true)
        FileContentIO.write(file, original)

        assertEquals(original, FileContentIO.read(file))
    }

    @Test
    fun roundTrip_preservesUtf16BeWithBom() {
        val file = tmp.newFile("be.txt")
        val original = FileContent("söra", LineEnding.LF, FileEncoding.UTF_16BE, hadBom = true)
        FileContentIO.write(file, original)

        assertEquals(original, FileContentIO.read(file))
    }

    @Test
    fun roundTrip_doesNotNormalizeMixedEndings() {
        val file = tmp.newFile("mixed.txt")
        val mixed = "a\r\nb\nc\r\nd\re"
        FileContentIO.write(file, FileContent(mixed, LineEnding.CRLF, FileEncoding.UTF_8, hadBom = false))

        assertEquals(mixed, FileContentIO.read(file).text)
    }

    @Test
    fun write_createsMissingParentDirectories() {
        val file = File(tmp.root, "deeply/nested/dir/file.txt")
        FileContentIO.write(file, FileContent("x", LineEnding.LF, FileEncoding.UTF_8, hadBom = false))

        assertEquals("x", FileContentIO.read(file).text)
    }

    // ---- atomicity ---------------------------------------------------------

    @Test
    fun write_failingTempWriteLeavesOriginalIntact() {
        val file = tmp.newFile("target.txt")
        file.writeText("original")

        // Block the temp-file write by pre-creating a directory exactly where
        // the temp file would go; opening it for output must fail.
        val tmpPath = File(tmp.root, "target.txt.tmp")
        assertTrue(tmpPath.mkdir())

        try {
            FileContentIO.write(file, FileContent("new", LineEnding.LF, FileEncoding.UTF_8, hadBom = false))
            fail("expected IOException")
        } catch (expected: IOException) {
            // expected
        }

        assertEquals("original", file.readText())
    }

    @Test
    fun write_failingRenameLeavesOriginalIntactAndCleansTemp() {
        // Target is a non-empty directory: temp write succeeds but the final
        // rename over it must fail.
        val target = File(tmp.root, "target-dir")
        assertTrue(target.mkdir())
        val child = File(target, "keep.txt")
        child.writeText("keep")

        try {
            FileContentIO.write(target, FileContent("new", LineEnding.LF, FileEncoding.UTF_8, hadBom = false))
            fail("expected IOException")
        } catch (expected: IOException) {
            // expected (DirectoryNotEmptyException / AccessDeniedException)
        }

        // The "original" is untouched...
        assertTrue(target.isDirectory)
        assertEquals("keep", child.readText())
        // ...and no temp file is left behind.
        assertFalse(File(tmp.root, "target-dir.tmp").exists())
    }

    @Test
    fun write_leavesNoTempFileOnSuccess() {
        val file = tmp.newFile("ok.txt")
        FileContentIO.write(file, FileContent("content", LineEnding.LF, FileEncoding.UTF_8, hadBom = false))

        assertEquals("content", file.readText())
        assertFalse(File(tmp.root, "ok.txt.tmp").exists())
        assertEquals(1, tmp.root.listFiles()!!.count { it.name == "ok.txt" })
    }

    // ---- dominant line ending detection ------------------------------------

    @Test
    fun detectDominantLineEnding_cases() {
        assertEquals(LineEnding.CRLF, FileContentIO.detectDominantLineEnding("a\r\nb\r\nc\nd"))
        assertEquals(LineEnding.LF, FileContentIO.detectDominantLineEnding("a\nb\nc\r\nd"))
        assertEquals(LineEnding.CR, FileContentIO.detectDominantLineEnding("a\rb\rc\nd"))
        assertEquals(LineEnding.LF, FileContentIO.detectDominantLineEnding("no endings"))
        assertEquals(LineEnding.LF, FileContentIO.detectDominantLineEnding(""))
        // Tie between CRLF and LF -> LF.
        assertEquals(LineEnding.LF, FileContentIO.detectDominantLineEnding("a\r\nb\nc"))
    }

    @Test
    fun read_missingFile_throws() {
        val missing = File(tmp.root, "missing.txt")
        try {
            FileContentIO.read(missing)
            fail("expected IOException")
        } catch (expected: IOException) {
            // expected: file does not exist
        }
    }
}
