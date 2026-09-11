package com.maragung.arrowide.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagesIndexParserTest {

    private val sampleIndex = """
        Package: nodejs
        Version: 22.1.0
        Filename: pool/main/n/nodejs/nodejs_22.1.0_aarch64.deb
        SHA256: 5b2c1a6f0a8e4b3f9d6c7a1e2f3b4a5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2
        Size: 12345678
        Depends: libc, libssl (>= 1.1), ca-certificates
        Description: Node.js runtime
         This is a continuation line
         spanning several lines.
        Installed-Size: 40960

        Package: libssl
        Version: 3.0.13
        Filename: pool/main/o/openssl/libssl_3.0.13_aarch64.deb
        SHA256: aaaa1a6f0a8e4b3f9d6c7a1e2f3b4a5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2
        Size: 200000
        Depends: zlib | zlib-ng
        Description: SSL library

        Package: broken-stanza
        Version: 1.0
        Depends: something
    """.trimIndent()

    @Test
    fun parsesBasicFields() {
        val packages = PackagesIndexParser.parse(sampleIndex)

        // broken-stanza has no Filename and is dropped.
        assertEquals(2, packages.size)
        val nodejs = packages.first { it.name == "nodejs" }
        assertEquals("22.1.0", nodejs.version)
        assertEquals("pool/main/n/nodejs/nodejs_22.1.0_aarch64.deb", nodejs.filename)
        assertEquals(
            "5b2c1a6f0a8e4b3f9d6c7a1e2f3b4a5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2",
            nodejs.sha256,
        )
        assertEquals(12345678L, nodejs.size)
    }

    @Test
    fun continuationLinesDoNotCorruptFields() {
        val nodejs = PackagesIndexParser.parse(sampleIndex).first { it.name == "nodejs" }

        // The Description continuation lines must not bleed into Depends
        // or any parsed field.
        assertEquals(listOf("libc", "libssl", "ca-certificates"), nodejs.dependencyNames)
    }

    @Test
    fun stanzaWithoutFilenameIsSkipped() {
        val packages = PackagesIndexParser.parse(sampleIndex)

        assertTrue(packages.none { it.name == "broken-stanza" })
    }

    @Test
    fun dependencyParsingHandlesAlternativesAndConstraints() {
        val libssl = PackagesIndexParser.parse(sampleIndex).first { it.name == "libssl" }

        // "zlib | zlib-ng" -> first alternative only.
        assertEquals(listOf("zlib"), libssl.dependencyNames)
    }

    @Test
    fun blankDependsYieldsEmptyList() {
        val none = PackagesIndexParser.parse("Package: x\nVersion: 1\nFilename: x.deb\n")
        assertEquals(1, none.size)
        assertTrue(none.single().dependencyNames.isEmpty())
    }

    @Test
    fun dependsContinuationLinesAreJoined() {
        val text = """
            Package: multi
            Version: 1
            Filename: m.deb
            Depends: aaa,
             bbb (>= 2),
             ccc
        """.trimIndent()

        val pkg = PackagesIndexParser.parse(text).single()

        assertEquals(listOf("aaa", "bbb", "ccc"), pkg.dependencyNames)
    }

    @Test
    fun debDepsParseHandlesEdgeCases() {
        // Alternatives with constraints on both sides.
        assertEquals(
            listOf("one"),
            DebDeps.parse("one (>= 1.0) | two (<< 3)"),
        )
        // Blank and whitespace-only groups are dropped.
        assertTrue(DebDeps.parse("  ").isEmpty())
        // Duplicate names collapse.
        assertEquals(listOf("x"), DebDeps.parse("x, x (>= 1), x"))
    }
}
