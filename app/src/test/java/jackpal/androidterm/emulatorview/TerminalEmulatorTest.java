/*
 * Unit tests for the vendored jackpal AndroidTerm terminal emulator
 * (package jackpal.androidterm.emulatorview).
 *
 * Upstream ships no pure-JVM tests for the emulator (their test project is
 * an Android instrumentation project), so these tests were written for
 * Arrow IDE to validate ANSI/emulator behavior in CI. They live in the
 * same package so they can drive the package-private TerminalEmulator
 * through the TermSession API.
 *
 * The tests stick to printable ASCII: non-ASCII code points would consult
 * AndroidCharacterCompat/Build.VERSION, which is not available in
 * pure-JVM unit tests.
 */

package jackpal.androidterm.emulatorview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class TerminalEmulatorTest {

    private static final String ESC = "\u001b";

    /**
     * Drives the emulator without any Android runtime: the reader/writer
     * threads of TermSession are not started (no streams connected, no
     * Looper) and output written back by the emulator is captured.
     */
    static class TestSession extends TermSession {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        TestSession() {
            super(false);
        }

        void start(int columns, int rows) {
            updateSize(columns, rows);
        }

        void feed(String data) {
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            appendToEmulator(bytes, 0, bytes.length);
        }

        TerminalEmulator emulator() {
            return getEmulator();
        }

        TranscriptScreen screen() {
            return getTranscriptScreen();
        }

        String row(int y) {
            return emulator().getSelectedText(0, y, 79, y);
        }

        String output() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public void write(byte[] data, int offset, int count) {
            output.write(data, offset, count);
        }

        @Override
        public void setTitle(String title) {
            super.setTitle(title);
        }
    }

    private static TestSession startedSession() {
        TestSession session = new TestSession();
        session.start(80, 24);
        return session;
    }

    @Test
    public void printableCharactersArePlacedOnTheScreen() {
        TestSession session = startedSession();
        session.feed("hello");
        assertEquals(0, session.emulator().getCursorRow());
        assertEquals(5, session.emulator().getCursorCol());
        assertEquals("hello", session.row(0));
    }

    @Test
    public void cursorPositionControlMovesTheCursor() {
        TestSession session = startedSession();
        session.feed(ESC + "[10;20H");
        assertEquals(9, session.emulator().getCursorRow());
        assertEquals(19, session.emulator().getCursorCol());
    }

    @Test
    public void carriageReturnAndLineFeed() {
        TestSession session = startedSession();
        session.feed("abc\r\ndef");
        assertEquals("abc", session.row(0));
        assertEquals("def", session.row(1));
    }

    @Test
    public void backspaceMovesTheCursorBack() {
        TestSession session = startedSession();
        session.feed("abc\b");
        assertEquals(2, session.emulator().getCursorCol());
        session.feed("X");
        assertEquals("abX", session.row(0));
    }

    @Test
    public void tabAdvancesToTheNextTabStop() {
        TestSession session = startedSession();
        session.feed("a\tb");
        assertEquals("a       b", session.row(0));
    }

    @Test
    public void longLinesWrapAtTheRightMargin() {
        TestSession session = startedSession();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            line.append('a');
        }
        session.feed(line.toString());
        assertEquals(80, session.row(0).length());
        assertEquals("a".repeat(80), session.row(0));
        session.feed("b");
        assertEquals("b", session.row(1));
    }

    @Test
    public void eraseInLineClearsFromTheCursor() {
        TestSession session = startedSession();
        session.feed("hello");
        session.feed(ESC + "[1D"); // cursor back onto the second 'o'
        session.feed(ESC + "[K");
        assertEquals("hell", session.row(0));
    }

    @Test
    public void eraseInDisplayClearsTheScreen() {
        TestSession session = startedSession();
        session.feed("first\r\nsecond\r\nthird");
        session.feed(ESC + "[2J");
        assertEquals("", session.row(0));
        assertEquals("", session.row(1));
        // Cursor does not move.
        assertEquals(2, session.emulator().getCursorRow());
        assertEquals(5, session.emulator().getCursorCol());
    }

    @Test
    public void sgrForegroundAndBackgroundColorsAreRecorded() {
        TestSession session = startedSession();
        session.feed(ESC + "[31;43mxy");
        GrowableIntArray colors = new GrowableIntArray(16);
        String text = session.screen().getTranscriptText(colors);
        // The transcript text ends with one newline per (blank) screen row.
        assertEquals("xy", text.trim());
        assertTrue(colors.length() >= 2);
        int style = colors.at(0);
        assertEquals(1, TextStyle.decodeForeColor(style));  // red
        assertEquals(3, TextStyle.decodeBackColor(style));  // yellow
    }

    @Test
    public void sgrBoldIsRecordedAsAnEffect() {
        TestSession session = startedSession();
        session.feed(ESC + "[1mA");
        GrowableIntArray colors = new GrowableIntArray(16);
        session.screen().getTranscriptText(colors);
        assertTrue((TextStyle.decodeEffect(colors.at(0)) & TextStyle.fxBold) != 0);
    }

    @Test
    public void sgrResetReturnsToDefaultColors() {
        TestSession session = startedSession();
        session.feed(ESC + "[31mred" + ESC + "[0mplain");
        GrowableIntArray colors = new GrowableIntArray(16);
        String text = session.screen().getTranscriptText(colors);
        assertEquals("redplain", text.trim());
        int plainStyle = colors.at(3);
        assertEquals(TextStyle.ciForeground, TextStyle.decodeForeColor(plainStyle));
        assertEquals(TextStyle.ciBackground, TextStyle.decodeBackColor(plainStyle));
    }

    @Test
    public void oscSequenceSetsTheSessionTitle() {
        TestSession session = startedSession();
        session.feed(ESC + "]0;my title\u0007");
        assertEquals("my title", session.getTitle());
    }

    @Test
    public void deviceStatusReportIsAnswered() {
        TestSession session = startedSession();
        session.feed(ESC + "[5n");
        assertEquals(ESC + "[0n", session.output());
    }

    @Test
    public void cursorPositionReportIsAnswered() {
        TestSession session = startedSession();
        session.feed("AB");
        session.feed(ESC + "[6n");
        assertEquals(ESC + "[1;3R", session.output());
    }

    @Test
    public void alternateScreenBufferIsSwitchedAndRestored() {
        TestSession session = startedSession();
        session.feed("main");
        session.feed(ESC + "[?1049h"); // switch to the alternate screen
        session.feed("\ralt");
        assertEquals("alt", session.row(0));
        session.feed(ESC + "[?1049l"); // back to the main screen
        assertEquals("main", session.row(0));
    }

    @Test
    public void savedCursorIsRestored() {
        TestSession session = startedSession();
        session.feed("ab");
        session.feed(ESC + "7");  // DECSC
        session.feed("\r\nxyz");
        session.feed(ESC + "8");  // DECRC
        assertEquals(0, session.emulator().getCursorRow());
        assertEquals(2, session.emulator().getCursorCol());
        session.feed("c");
        assertEquals("abc", session.row(0));
    }

    @Test
    public void scrolledLinesMoveIntoTheTranscript() {
        TestSession session = startedSession();
        StringBuilder data = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            data.append("L").append(i).append("\r\n");
        }
        session.feed(data.toString());
        // 24 rows fill the screen; each of the 7 remaining newlines scrolls
        // one line into the transcript and keeps the cursor on the last row.
        assertEquals(23, session.emulator().getCursorRow());
        assertEquals(7, session.screen().getActiveTranscriptRows());
        String transcript = session.getTranscriptText();
        assertTrue(transcript.contains("L1"));
        assertTrue(transcript.contains("L30"));
        assertEquals("L8", session.row(0));
    }

    @Test
    public void resetClearsScreenAndState() {
        TestSession session = startedSession();
        session.feed(ESC + "[31mcolored text");
        session.reset();
        assertEquals(0, session.emulator().getCursorRow());
        assertEquals(0, session.emulator().getCursorCol());
        assertEquals("", session.row(0));
    }
}
