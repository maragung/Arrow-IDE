package com.maragung.arrowide.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DebArchiveTest {

    private fun tempDir(): File = Files.createTempDirectory("arrow-deb").toFile()

    @Test
    fun extractsFilesWithContentAndPermissions() {
        val deb = SyntheticDeb.build(
            File.createTempFile("arrow-deb", ".deb"),
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/node", "node-binary"),
                SyntheticDeb.file("./usr/lib/index.js", "module.exports = 1"),
            ),
        )
        val target = tempDir()

        DebArchive.extract(deb, target)

        val node = File(target, "usr/bin/node")
        assertTrue(node.isFile)
        assertEquals("node-binary", node.readText())
        assertTrue("executable bit must be preserved", node.canExecute())
        assertTrue("world-readable", node.canRead())

        val lib = File(target, "usr/lib/index.js")
        assertEquals("module.exports = 1", lib.readText())
        assertTrue("data files are not executable", !lib.canExecute())
    }

    @Test
    fun extractsSymlinks() {
        val deb = SyntheticDeb.build(
            File.createTempFile("arrow-deb", ".deb"),
            listOf(
                SyntheticDeb.dir("./usr/bin/"),
                SyntheticDeb.executable("./usr/bin/node", "node-binary"),
                SyntheticDeb.symlink("./usr/bin/npm", "node"),
            ),
        )
        val target = tempDir()

        DebArchive.extract(deb, target)

        val npm = File(target, "usr/bin/npm")
        assertTrue(Files.isSymbolicLink(npm.toPath()))
        assertEquals("node", Files.readSymbolicLink(npm.toPath()).toString())
    }

    @Test
    fun extractsNestedDirectories() {
        val deb = SyntheticDeb.build(
            File.createTempFile("arrow-deb", ".deb"),
            listOf(
                SyntheticDeb.dir("./usr/share/doc/pkg/"),
                SyntheticDeb.file("./usr/share/doc/pkg/copyright", "Apache-2.0"),
            ),
        )
        val target = tempDir()

        DebArchive.extract(deb, target)

        assertEquals("Apache-2.0", File(target, "usr/share/doc/pkg/copyright").readText())
    }

    @Test
    fun zstMemberIsRejected() {
        // A .deb whose payload member is data.tar.zst (raw bytes; the
        // extension is what matters for the rejection path).
        val deb = File.createTempFile("arrow-deb", ".deb")
        deb.outputStream().use { out ->
            out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
            val name = "data.tar.zst".padEnd(16, ' ')
            val header = name + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) +
                "100644".padEnd(8) + "1".padEnd(10) + "`\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(0x00)
        }

        try {
            DebArchive.extract(deb, tempDir())
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("zstd not supported"))
        }
    }

    @Test
    fun corruptArchiveIsRejected() {
        val deb = File.createTempFile("arrow-deb", ".deb")
        deb.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        try {
            DebArchive.extract(deb, tempDir())
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            // expected
        }
    }

    @Test
    fun pathTraversalEntryIsRejected() {
        // Build a tar entry that escapes via ".." by writing the tar by
        // hand (SyntheticDeb refuses to model hostile input).
        val deb = File.createTempFile("arrow-deb", ".deb")
        deb.outputStream().use { out ->
            org.apache.commons.compress.archivers.ar.ArArchiveOutputStream(out).use { ar ->
                val tarBytes = java.io.ByteArrayOutputStream().use { tarBuf ->
                    org.apache.commons.compress.compressors.xz.XZCompressorOutputStream(tarBuf).use { xz ->
                        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(xz).use { tar ->
                            val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                                "../../evil.sh",
                            )
                            entry.setSize(1)
                            tar.putArchiveEntry(entry)
                            tar.write(0x41)
                            tar.closeArchiveEntry()
                            tar.finish()
                        }
                    }
                    tarBuf.toByteArray()
                }
                ar.putArchiveEntry(
                    org.apache.commons.compress.archivers.ar.ArArchiveEntry("data.tar.xz", tarBytes.size.toLong())
                )
                ar.write(tarBytes)
                ar.closeArchiveEntry()
                ar.finish()
            }
        }

        try {
            DebArchive.extract(deb, tempDir())
            fail("expected ToolchainException")
        } catch (e: ToolchainException) {
            assertTrue(e.message!!.contains("Unsafe path"))
        }
    }
}
