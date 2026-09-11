package com.maragung.arrowide.ui.terminal

import android.content.Context
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.maragung.arrowide.terminal.TerminalSession
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.emulatorview.TermSession

/**
 * The vendored [EmulatorView], adapted for Arrow IDE:
 *  - tapping the view also brings up the soft keyboard,
 *  - long-press starts touch-selection (drag to select; releasing copies
 *    the selection to the clipboard) instead of the legacy context menu,
 *    which requires a hosting Activity's context-menu plumbing.
 */
class ArrowTerminalView(
    context: Context,
    session: TermSession,
    metrics: DisplayMetrics,
) : EmulatorView(context, session, metrics) {

    override fun onSingleTapUp(e: MotionEvent?): Boolean {
        val handled = super.onSingleTapUp(e)
        requestFocus()
        showSoftInput()
        return handled
    }

    override fun onLongPress(e: MotionEvent?) {
        toggleSelectingText()
    }

    /** Brings up the soft keyboard for this view. */
    fun showSoftInput() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.showSoftInput(this, 0)
    }

    /** Hides the soft keyboard. */
    fun hideSoftInput() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }
}

/**
 * Embeds a live [TerminalSession] in Compose via the vendored terminal view.
 *
 * The composable renders exactly one view per session (keyed by the session
 * identity at the call site); the vendored view cannot re-attach to a
 * different session's emulator once initialized.
 *
 * @param session       the session to display
 * @param fontSizeDp    monospace font size in dp
 * @param onViewHolder  invoked once with the created view, so callers can
 *                      drive it (keyboard toggling, app-mode queries for
 *                      special keys, ...)
 */
@Composable
fun TerminalViewCompat(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSizeDp: Int = TerminalDefaults.FONT_SIZE_DP,
    onViewHolder: ((ArrowTerminalView) -> Unit)? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ArrowTerminalView(ctx, session, ctx.resources.displayMetrics).apply {
                setTextSize(fontSizeDp)
                setColorScheme(TerminalDefaults.defaultColorScheme())
                setUseCookedIME(true)
                onViewHolder?.invoke(this)
            }
        },
        update = { view ->
            view.setTextSize(fontSizeDp)
            view.setColorScheme(TerminalDefaults.defaultColorScheme())
        },
    )
}
