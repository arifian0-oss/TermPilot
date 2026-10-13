# Changelog

## v0.1.1 — 2026-10-13

- Fixed: app crashed on launch — `AndroidManifest.xml` was missing
  `android:name=".TermPilotApp"`, causing a `ClassCastException` in
  `MainActivity`. Rebuilt and re-verified (apksigner + zipalign clean).

## v0.1.0 — 2026-10-13

- Initial build.
- Hosts CRUD: password, keyboard-interactive, PEM private-key import.
- TOFU host-key verification with SHA-256 fingerprint dialog.
- Full terminal via vendored + patched Termux `terminal-emulator` /
  `terminal-view` (external-I/O mode, no local PTY/JNI).
- LLM settings (OpenAI-compatible base URL, API key, model) with
  connection test.
- Agent mode: per-session toggle (default off), `run_command` tool loop,
  25-iteration cap, STOP button, full transcript, exec-channel isolation.
