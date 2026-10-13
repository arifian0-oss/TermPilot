# Changelog

## v0.1.2 — 2026-10-13

- Fixed: "Connection failed: no such algorithm: X25519 for provider BC".
  Android's platform BouncyCastle ("BC") shadowed the bundled bcprov jar, so
  sshj's JCE lookups hit the platform provider, which lacks X25519/Ed25519.
  New `ssh/CryptoProviders.kt` exposes the bundled BouncyCastle under the
  collision-free provider name "TermPilotBC" (algorithm-table copy into a
  thin `Provider` wrapper — `BouncyCastleProvider` is final) and points sshj
  at it via `SecurityUtils.setSecurityProvider`. Verified with a JVM harness:
  X25519 key agreement + Ed25519 sign/verify through "TermPilotBC".

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
