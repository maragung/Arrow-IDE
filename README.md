# Arrow IDE

A full-featured mobile **code editor + local development environment + Git/GitHub workspace** for Android — inspired by VS Code, optimized for smartphones and tablets, with an integrated AI coding agent powered by [OpenCode](https://opencode.ai).

> **Status:** early development — Milestone 1 (foundation: editor, explorer, real local terminal, workspace manager). See [`plan.md`](plan.md) for the full vision.

## Goals

- Code Editor + Project Explorer + **real** local Terminal (PTY, no root)
- Toolchain Manager (Node.js, Python, Git, Clang/CMake — downloaded & verified, not bundled)
- Git + GitHub integration (PAT-based, tokens stored in Android Keystore-backed storage)
- GitHub Actions / CI-CD helper
- AI Coding Agent via the OpenCode engine, with a strict permission/security layer

Everything runs in the standard Android app sandbox — **no root, no privilege escalation, no fake features**.

## Downloads

Every push to `main` is built by GitHub Actions. Grab the debug APK from the
[Actions artifacts](https://github.com/maragung/Arrow-IDE/actions) of the latest successful run.

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK platform 34. The app targets `targetSdk 28` on purpose
(Termux-style distribution) so downloaded toolchain binaries can be executed from app storage.

## License

[MIT](LICENSE) — Arrow IDE.
Third-party components retain their own licenses (OpenCode: MIT, etc.).
