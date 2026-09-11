# Third-Party Notices

This file lists third-party software vendored into or used by Arrow IDE,
along with its licenses and any local modifications.

## jackpal Android-Terminal-Emulator (AndroidTerm)

- **Project:** https://github.com/jackpal/Android-Terminal-Emulator
  (formerly hosted as `jackpal/AndroidTerm`)
- **Version:** master, commit
  `35188f8a8b57989a4a4ec9485e11187b46be26d9` (2020-10-20);
  the vendored `emulatorview` module identifies itself as versionName
  `1.0.42` (versionCode 43)
- **License:** Apache License 2.0
  (https://www.apache.org/licenses/LICENSE-2.0)

### What is vendored

The terminal emulator view and model from the `emulatorview` module, under:

```
app/src/main/java/jackpal/androidterm/emulatorview/
```

Files (all under the directory above, Apache-2.0 headers preserved where
present upstream):

```
BaseTextRenderer.java
ByteQueue.java
ColorScheme.java
EmulatorDebug.java
EmulatorView.java
GrowableIntArray.java
PaintRenderer.java
Screen.java
StyleRow.java
TerminalEmulator.java
TermKeyListener.java
TermSession.java
TextRenderer.java
TextStyle.java
TranscriptScreen.java
UnicodeTranscript.java
UpdateCallback.java
package.html
compat/AndroidCharacterCompat.java
compat/AndroidCompat.java
compat/ClipboardManagerCompat.java
compat/ClipboardManagerCompatFactory.java
compat/ClipboardManagerCompatV11.java
compat/ClipboardManagerCompatV1.java
compat/KeyCharacterMapCompat.java
compat/KeycodeConstants.java
compat/Patterns.java
```

Some of these files (e.g. `TextStyle.java`, `GrowableIntArray.java`, the
`compat/` helpers) carry no license header upstream; they are reproduced
as-is. The remainder retain their original Apache License 2.0 headers.

The upstream project's NOTICE file (term/NOTICE in the upstream
repository) reads, in whole:

    AndroidTerm
    Copyright (C) 2007-2008 The Android Open Source Project

### Local modifications

The following changes were made for Arrow IDE. Each is also marked with a
comment at the modification site in the source.

1. **`TermSession.java`** — three changes so the class can be constructed
   and driven on a plain JVM (for unit tests) and with streams connected
   later than construction:
   - The `Handler` field is now created through a `createMsgHandler()`
     helper that returns `null` when no Android `Looper` exists, instead
     of crashing.
   - The reader thread's `sendMessage` calls are guarded by a
     `mMsgHandler != null` check.
   - `initializeEmulator()` only starts the reader/writer threads when the
     corresponding streams (`mTermIn` / `mTermOut`) are set, allowing
     subclasses to connect pipes after construction.

2. **`EmulatorView.java`** — `updateText()` unconditionally uses
   `PaintRenderer`. The removed branch instantiated
   `Bitmap4x8FontRenderer`, whose source (and the `res/drawable`
   resources it references) was not vendored; the branch was unreachable
   in practice because it only ran with a text size of 0.

3. **`PaintRenderer.java`** — `FloatMath` calls replaced with
   `Math.ceil` on `double` (matching upstream's own newer fix);
   `android.util.FloatMath` is deprecated and removed from recent SDKs.
   The `FloatMath` import was removed accordingly.

4. **Not vendored:** `Bitmap4x8FontRenderer.java` and the
   `res/drawable` bitmaps it requires (Arrow IDE renders text with
   `PaintRenderer`), plus everything else in the upstream repository
   (the `term` app module, `samples`, native `exec` helper, tests,
   graphics, build files).

### Unit tests

The upstream project ships no pure-JVM unit tests for the emulator (its
test project is Android instrumentation-based). Arrow IDE's own tests at
`app/src/test/java/jackpal/androidterm/emulatorview/TerminalEmulatorTest.java`
were written for this project and are not derived from upstream code;
they are listed here only to distinguish them from vendored material.

## Sora Editor

- **Project:** https://github.com/Rosemoe/sora-editor
- **Version:** 0.23.6 (consumed as a binary dependency from Maven Central —
  not vendored, not modified)
- **License:** LGPL-2.1
  (https://github.com/Rosemoe/sora-editor/blob/stable/LICENSE)
- **Artifacts used:** `io.github.Rosemoe.sora-editor:editor`,
  `io.github.Rosemoe.sora-editor:language-textmate`
- **Notes:** consumed as an unmodified library dependency via Gradle. The
  library remains under LGPL-2.1; Arrow IDE's own code is MIT. Users may
  replace the library per LGPL terms.

## TextMate grammars & theme (app/src/main/assets/textmate/)

- **Sources:** sora-editor demo assets (Apache-2.0 / per-file upstream
  licenses) at tag 0.23.6, and microsoft/vscode grammar files (MIT,
  https://github.com/microsoft/vscode)
- **License:** MIT (vscode grammar files);
  sora-editor demo files under Apache-2.0
- **Per-file source URLs and any modifications:** see
  `app/src/main/assets/textmate/SOURCES.md`
- **Local modifications:** the four vscode language-configuration JSON
  files were stripped of JSONC comments/trailing commas (string-aware,
  zero semantic change) because the grammar loader requires strict JSON.


## XZ for Java

- **Project:** https://tukaani.org/xz/java.html
  (source: https://github.com/tukaani-project/xz-java)
- **Version:** 1.9 (consumed as a binary dependency from Maven Central —
  not vendored, not modified)
- **License:** Public Domain (0-Clause)
  (https://github.com/tukaani-project/xz-java/blob/master/LICENSE)
- **Artifact used:** `org.tukaani:xz` (via Apache commons-compress's XZ
  support for `.deb` payload decompression)
- **Notes:** consumed as an unmodified library dependency via Gradle.

## Apache Commons Compress

- **Project:** https://commons.apache.org/proper/commons-compress/
- **Version:** 1.26.2 (consumed as a binary dependency from Maven Central —
  not vendored, not modified)
- **License:** Apache License 2.0
  (https://www.apache.org/licenses/LICENSE-2.0)
- **Artifact used:** `org.apache.commons:commons-compress` (ar + tar
  handling for `.deb` package extraction, XZ compressor streams)
- **Notes:** consumed as an unmodified library dependency via Gradle. It
  pulls in `commons-io`, `commons-lang3` and `commons-codec` transitively,
  also unmodified.
