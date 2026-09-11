package com.maragung.arrowide.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.maragung.arrowide.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.arrowDataStore by preferencesDataStore(name = "arrow_settings")

/**
 * App-wide settings persisted with Preferences DataStore.
 *
 * Exposes a single [settings] flow plus an [update] transform; nothing else
 * touches the DataStore, so writes are always serialized by DataStore itself.
 */
class SettingsStore(private val context: Context) {

    data class AppSettings(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val editorFontSize: Int = 14,
        val terminalFontSize: Int = 12,
        val scrollbackLines: Int = 5000,
        val maxTerminalSessions: Int = 5,
        val sessionLimitWarn: Boolean = true
    )

    val settings: Flow<AppSettings> = context.arrowDataStore.data
        .catch { e ->
            // Corrupted store: fall back to defaults rather than crashing.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { it.toAppSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.arrowDataStore.edit { preferences ->
            val updated = transform(preferences.toAppSettings())
            preferences[THEME_MODE] = updated.themeMode.name
            preferences[EDITOR_FONT_SIZE] = updated.editorFontSize
            preferences[TERMINAL_FONT_SIZE] = updated.terminalFontSize
            preferences[SCROLLBACK_LINES] = updated.scrollbackLines
            preferences[MAX_TERMINAL_SESSIONS] = updated.maxTerminalSessions
            preferences[SESSION_LIMIT_WARN] = updated.sessionLimitWarn
        }
    }

    private fun androidx.datastore.preferences.core.Preferences.toAppSettings(): AppSettings =
        AppSettings(
            themeMode = this[THEME_MODE]
                ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            editorFontSize = this[EDITOR_FONT_SIZE] ?: 14,
            terminalFontSize = this[TERMINAL_FONT_SIZE] ?: 12,
            scrollbackLines = this[SCROLLBACK_LINES] ?: 5000,
            maxTerminalSessions = this[MAX_TERMINAL_SESSIONS] ?: 5,
            sessionLimitWarn = this[SESSION_LIMIT_WARN] ?: true
        )

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val EDITOR_FONT_SIZE = intPreferencesKey("editor_font_size")
        val TERMINAL_FONT_SIZE = intPreferencesKey("terminal_font_size")
        val SCROLLBACK_LINES = intPreferencesKey("scrollback_lines")
        val MAX_TERMINAL_SESSIONS = intPreferencesKey("max_terminal_sessions")
        val SESSION_LIMIT_WARN = booleanPreferencesKey("session_limit_warn")
    }
}
