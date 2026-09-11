package com.maragung.arrowide.editor

import java.io.File
import java.util.Locale

/**
 * One TextMate-supported language known to the editor.
 *
 * @property id short language id ("python").
 * @property scopeName TextMate scope name ("source.python") — this is what
 *   `TextMateLanguage.create(...)` expects and what [EditorTab.languageId]
 *   stores.
 * @property grammarAssetPath path of the grammar under `assets/`.
 * @property languageConfigurationAssetPath optional path of the language
 *   configuration under `assets/`.
 * @property extensions file extensions (lowercase, no dot) mapped to this language.
 */
data class LanguageSpec(
    val id: String,
    val scopeName: String,
    val grammarAssetPath: String,
    val languageConfigurationAssetPath: String?,
    val extensions: Set<String>
)

/**
 * Maps file extensions to TextMate languages. Pure Kotlin.
 *
 * This registry must stay in sync with `assets/textmate/languages.json`
 * (see `app/src/main/assets/textmate/SOURCES.md`).
 */
object LanguageRegistry {

    val languages: List<LanguageSpec> = listOf(
        LanguageSpec(
            id = "java", scopeName = "source.java",
            grammarAssetPath = "textmate/java/syntaxes/java.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/java/language-configuration.json",
            extensions = setOf("java")
        ),
        LanguageSpec(
            id = "kotlin", scopeName = "source.kotlin",
            grammarAssetPath = "textmate/kotlin/syntaxes/Kotlin.tmLanguage",
            languageConfigurationAssetPath = "textmate/kotlin/language-configuration.json",
            extensions = setOf("kt", "kts")
        ),
        LanguageSpec(
            id = "python", scopeName = "source.python",
            grammarAssetPath = "textmate/python/syntaxes/python.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/python/language-configuration.json",
            extensions = setOf("py", "pyi")
        ),
        LanguageSpec(
            id = "javascript", scopeName = "source.js",
            grammarAssetPath = "textmate/javascript/syntaxes/JavaScript.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/javascript/language-configuration.json",
            extensions = setOf("js", "mjs", "cjs", "jsx")
        ),
        LanguageSpec(
            id = "typescript", scopeName = "source.ts",
            grammarAssetPath = "textmate/typescript/syntaxes/TypeScript.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/typescript/language-configuration.json",
            extensions = setOf("ts", "mts", "cts", "tsx")
        ),
        LanguageSpec(
            id = "json", scopeName = "source.json",
            grammarAssetPath = "textmate/json/syntaxes/JSON.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/json/language-configuration.json",
            extensions = setOf("json")
        ),
        LanguageSpec(
            id = "yaml", scopeName = "source.yaml",
            grammarAssetPath = "textmate/yaml/syntaxes/yaml.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/yaml/language-configuration.json",
            extensions = setOf("yaml", "yml")
        ),
        LanguageSpec(
            id = "markdown", scopeName = "text.html.markdown",
            grammarAssetPath = "textmate/markdown/syntaxes/markdown.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/markdown/language-configuration.json",
            extensions = setOf("md", "markdown")
        ),
        LanguageSpec(
            id = "c", scopeName = "source.c",
            grammarAssetPath = "textmate/c/syntaxes/c.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/c/language-configuration.json",
            extensions = setOf("c", "h")
        ),
        LanguageSpec(
            id = "cpp", scopeName = "source.cpp",
            grammarAssetPath = "textmate/cpp/syntaxes/cpp.tmLanguage.json",
            languageConfigurationAssetPath = "textmate/cpp/language-configuration.json",
            extensions = setOf("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ino", "tpp", "ipp")
        )
    )

    private val extensionIndex: Map<String, LanguageSpec> =
        buildMap {
            for (language in languages) {
                for (extension in language.extensions) {
                    put(extension, language)
                }
            }
        }

    /** The language for [file] based on its extension, or null if unsupported. */
    fun languageFor(file: File): LanguageSpec? =
        extensionIndex[file.extension.lowercase(Locale.ROOT)]
}
