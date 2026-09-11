package com.maragung.arrowide.ui.editor

import android.content.Context
import android.util.Log
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * One-time process-wide TextMate initialization, following the sora-editor
 * demo app exactly:
 *
 * 1. `FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(...))`
 *    so grammars/themes can be resolved from `assets/textmate/...`.
 * 2. `ThemeRegistry.getInstance().loadTheme(ThemeModel(IThemeSource.fromInputStream(...), "darcula"))`
 *    for the dark theme.
 * 3. `GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")`
 *    to register every grammar listed there.
 *
 * Initialization happens once, on the main thread (the same thread the
 * sora-editor demo uses); repeat calls are no-ops.
 */
object TextMateSetup {

    private const val TAG = "TextMateSetup"

    private const val THEME_PATH = "textmate/darcula.json"
    private const val THEME_NAME = "darcula"

    @Volatile
    private var initialized = false

    @Volatile
    private var attempted = false

    /** True once grammars and the theme have been successfully loaded. */
    val isInitialized: Boolean
        get() = initialized

    /**
     * Performs initialization if it has not been done yet. Safe to call from
     * any thread and repeatedly; failures are logged once and not retried
     * (the editor then runs without syntax highlighting rather than crashing).
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized || attempted) return
        attempted = true
        try {
            // 1. Make files under assets/textmate/ resolvable by the registries.
            FileProviderRegistry.getInstance().addFileProvider(
                AssetsFileResolver(context.applicationContext.assets)
            )

            // 2. Dark theme.
            val themeStream = FileProviderRegistry.getInstance().tryGetInputStream(THEME_PATH)
            if (themeStream == null) {
                Log.e(TAG, "Theme asset not found: $THEME_PATH")
                return
            }
            val themeRegistry = ThemeRegistry.getInstance()
            val themeModel = ThemeModel(
                IThemeSource.fromInputStream(themeStream, THEME_PATH, null),
                THEME_NAME
            ).apply { isDark = true }
            themeRegistry.loadTheme(themeModel)

            // 3. All grammars from our registry file.
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

            initialized = true
        } catch (t: Throwable) {
            Log.e(TAG, "TextMate initialization failed; highlighting disabled", t)
        }
    }

    /**
     * A color scheme for the editor: a [TextMateColorScheme] following the
     * loaded theme (preferred, required before using a TextMate language),
     * falling back to the plain [EditorColorScheme] when TextMate failed to
     * initialize.
     */
    fun createColorScheme(): EditorColorScheme {
        if (initialized) {
            try {
                return TextMateColorScheme.create(ThemeRegistry.getInstance())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create TextMateColorScheme", e)
            }
        }
        return EditorColorScheme()
    }
}
