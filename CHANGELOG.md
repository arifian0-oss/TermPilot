# Changelog

## v0.1.3 — 2026-10-13

- Fixed: terminal input. The soft keyboard often never appeared (focus/IME
  timing during screen transitions). `showKeyboard()` is now hardened (focus
  check + one forced retry), re-invoked when the terminal connects, and a new
  **KBD** key in the extra-keys row toggles the keyboard deterministically.
- Fixed: agent "thinking" disconnects. SSH keepalive (window-change ping every
  45 s) keeps NAT/firewall mappings alive during long idle stretches; the LLM
  client now retries transient network failures (DNS/timeout/reset, HTTP
  429/5xx) with backoff; the screen stays awake while the agent runs so Doze
  can't kill a long run.
- Tweaked: LLM settings now persist on every keystroke (write-through) — the
  previous Save-button flow could leave "Test connection" and the agent using
  different values.

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
