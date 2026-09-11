package com.maragung.arrowide.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** User-selectable theme mode persisted in [com.maragung.arrowide.data.SettingsStore]. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Dark-first color scheme with a code-editor feel: deep blue-black surfaces,
 * a bright blue primary and muted text tones that keep long editing sessions easy
 * on the eyes.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7AA2F7),
    onPrimary = Color(0xFF0B1120),
    primaryContainer = Color(0xFF23355C),
    onPrimaryContainer = Color(0xFFD6E2FF),
    secondary = Color(0xFF9D7CD8),
    onSecondary = Color(0xFF171025),
    secondaryContainer = Color(0xFF33254D),
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFF73DACA),
    onTertiary = Color(0xFF0C211D),
    tertiaryContainer = Color(0xFF1F4C44),
    onTertiaryContainer = Color(0xFFC4F5EB),
    error = Color(0xFFF7768E),
    onError = Color(0xFF2A0A12),
    errorContainer = Color(0xFF5C1B28),
    onErrorContainer = Color(0xFFFFD9DE),
    background = Color(0xFF0F131C),
    onBackground = Color(0xFFD3DAE8),
    surface = Color(0xFF151A26),
    onSurface = Color(0xFFD3DAE8),
    surfaceVariant = Color(0xFF232A3A),
    onSurfaceVariant = Color(0xFF9AA5BC),
    outline = Color(0xFF566076)
)

/** Light counterpart: same blue accent family on near-white surfaces. */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF33509F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF5B4A8A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7DEFF),
    onSecondaryContainer = Color(0xFF190052),
    tertiary = Color(0xFF1F6B5D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA5F2E2),
    onTertiaryContainer = Color(0xFF00201A),
    error = Color(0xFFA21933),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DE),
    onErrorContainer = Color(0xFF40000C),
    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF757780)
)

/** Typography with sensible defaults plus slightly stronger titles for app-shell chrome. */
private val ArrowTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)

/**
 * Root theme of the app.
 *
 * When [themeMode] is [ThemeMode.SYSTEM], follows the OS setting via
 * [isSystemInDarkTheme], which reads `LocalConfiguration.current.uiMode` under
 * the hood and updates on configuration changes.
 */
@Composable
fun ArrowTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkColorScheme else LightColorScheme,
        typography = ArrowTypography,
        content = content
    )
}
