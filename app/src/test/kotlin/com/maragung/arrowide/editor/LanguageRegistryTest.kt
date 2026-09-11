package com.maragung.arrowide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LanguageRegistryTest {

    @Test
    fun languageFor_mapsKnownExtensions() {
        fun specFor(name: String) = LanguageRegistry.languageFor(File(name))

        assertEquals("source.java", specFor("Main.java")?.scopeName)
        assertEquals("source.kotlin", specFor("Main.KT")?.scopeName) // case-insensitive
        assertEquals("source.kotlin", specFor("build.gradle.kts")?.scopeName)
        assertEquals("source.python", specFor("script.py")?.scopeName)
        assertEquals("source.python", specFor("types.pyi")?.scopeName)
        assertEquals("source.js", specFor("app.js")?.scopeName)
        assertEquals("source.js", specFor("app.mjs")?.scopeName)
        assertEquals("source.js", specFor("component.jsx")?.scopeName)
        assertEquals("source.ts", specFor("app.ts")?.scopeName)
        assertEquals("source.ts", specFor("app.tsx")?.scopeName)
        assertEquals("source.json", specFor("package.json")?.scopeName)
        assertEquals("source.yaml", specFor("ci.yaml")?.scopeName)
        assertEquals("source.yaml", specFor("ci.yml")?.scopeName)
        assertEquals("text.html.markdown", specFor("README.md")?.scopeName)
        assertEquals("source.c", specFor("main.c")?.scopeName)
        assertEquals("source.c", specFor("header.h")?.scopeName)
        assertEquals("source.cpp", specFor("main.cpp")?.scopeName)
        assertEquals("source.cpp", specFor("main.cc")?.scopeName)
        assertEquals("source.cpp", specFor("header.hpp")?.scopeName)
    }

    @Test
    fun languageFor_returnsNullForUnknownExtensions() {
        assertNull(LanguageRegistry.languageFor(File("archive.tar.gz")))
        assertNull(LanguageRegistry.languageFor(File("noext")))
        assertNull(LanguageRegistry.languageFor(File("")))
    }

    @Test
    fun languageFor_noExtensionShadowsAnother() {
        // ".h" maps to C, not C++.
        assertEquals("source.c", LanguageRegistry.languageFor(File("a.h"))?.scopeName)
        // ".hpp" maps to C++.
        assertEquals("source.cpp", LanguageRegistry.languageFor(File("a.hpp"))?.scopeName)
    }

    @Test
    fun everyLanguageReferencesGrammarAssetPath() {
        for (language in LanguageRegistry.languages) {
            assertTrue(
                language.grammarAssetPath.startsWith("textmate/"),
                "${language.id}: ${language.grammarAssetPath}"
            )
        }
    }
}
