package com.maragung.arrowide.ui.terminal

import jackpal.androidterm.emulatorview.ColorScheme

/**
 * Default terminal appearance and limits.
 *
 * These are plain defaults; every composable in this package accepts the
 * relevant values as constructor parameters so the app shell can wire them
 * to a settings store later.
 */
object TerminalDefaults {

    /** Font size in dp (the vendored view scales by density internally). */
    const val FONT_SIZE_DP: Int = 12

    /**
     * Scrollback lines kept per session. The vendored TermSession uses a
     * fixed internal transcript size of 10000 rows; this value documents
     * that contract.
     */
    const val SCROLLBACK_LINES: Int = 10000

    /** Initial terminal geometry used until the view reports its real size. */
    const val INITIAL_ROWS: Int = 24
    const val INITIAL_COLS: Int = 80

    /** Default concurrent-session limit passed to TerminalSessionManager. */
    const val MAX_SESSIONS: Int = 8

    /** Default foreground (light grey) on a near-black background. */
    const val FOREGROUND_COLOR: Int = 0xFFCCCCCC.toInt()
    const val BACKGROUND_COLOR: Int = 0xFF101010.toInt()

    /**
     * Color scheme for the vendored renderer. The 16-color ANSI palette
     * itself is the classic xterm palette built into the vendored
     * BaseTextRenderer; this only fixes the default fg/bg/cursor colors.
     */
    fun defaultColorScheme(): ColorScheme = ColorScheme(FOREGROUND_COLOR, BACKGROUND_COLOR)
}
