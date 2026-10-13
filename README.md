# TermPilot

A native Android SSH client (in the spirit of PuTTY / ConnectBot) with an
**autonomous LLM agent mode**: describe a goal in plain language and the
agent runs shell commands on the remote host by itself, with a full
transcript and a prominent STOP button.

- **Language/UI:** Kotlin, Jetpack Compose, coroutines
- **SSH:** [sshj](https://github.com/hierynomus/sshj) 0.40.0
- **Terminal:** Termux `terminal-emulator` + `terminal-view` (vendored, patched — see below)
- **LLM:** any OpenAI-compatible `/chat/completions` endpoint (OpenAI, OpenRouter, llama.cpp, vLLM…)
- **Secrets:** Android Keystore via `EncryptedSharedPreferences`; never in plain text
- `minSdk 26`, `targetSdk 34`, package `com.termpilot.app`

## Quick start

1. Install `TermPilot-debug.apk` (sideload; debug-signed).
2. **Hosts** → **+** → enter label, hostname, port, username; choose auth:
   password, keyboard-interactive, or import a PEM private key.
3. Tap the host → **Connect**. First connection shows the server's SHA-256
   host-key fingerprint — compare it with `ssh-keygen -lf` on the server,
   then **Trust** to save it (TOFU). It is verified on every later connect.
4. You get a full terminal (VT100, colors, scrollback, extra-keys row).
5. **Agent mode:** open **Settings**, set LLM base URL + API key + model,
   tap **Test connection**. Back in the terminal, enable **Agent mode**,
   type a goal ("check disk usage and clean /tmp"), and watch it work.
   The red **STOP** button halts it instantly.

## LLM providers

Any endpoint serving OpenAI-compatible `/chat/completions` with function
calling (`tools`) works:

| Provider | Base URL | Notes |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` (default) | Model e.g. `gpt-4o-mini` |
| OpenRouter | `https://openrouter.ai/api/v1` | Model e.g. `openai/gpt-4o-mini`; needs `tools` support |
| Local (llama.cpp) | `http://<lan-ip>:8080/v1` | Start with `--jinja` + a function-calling-capable model |
| Local (vLLM) | `http://<lan-ip>:8000/v1` | `--enable-auto-tool-choice --tool-call-parser` |

The API key is stored encrypted (Android Keystore) and is only ever sent to
the base URL you configured.

## FAQ

**Does the agent need the cloud?** Only to reach your LLM endpoint — point
it at a LAN server and everything stays local except SSH itself.

**Can the agent destroy my server?** It runs real commands as your SSH user,
so treat goals carefully. Guardrails: per-session opt-in (default off),
25-step cap, per-command timeout, full transcript, STOP button, and the
system prompt tells it to inspect before acting and avoid `sudo`. See
[docs/SECURITY.md](docs/SECURITY.md).

**Why is agent output separate from my shell?** Agent commands run on fresh
SSH exec channels, multiplexed over your connection but isolated from the
interactive shell — it never fights your typing.

**Where do my keys/passwords live?** EncryptedSharedPreferences
(AES-256-GCM, Keystore MasterKey). Host list metadata is in DataStore;
TOFU host keys in `filesDir/known_hosts`. See [docs/SECURITY.md](docs/SECURITY.md).

## Project layout

```
app/                          # the app
  src/main/java/com/termpilot/app/
    MainActivity.kt            # entry point + Compose navigation
    TermPilotApp.kt            # Application + manual DI container
    data/
      Host.kt                  # host model (label/host/port/user/auth type)
      HostRepository.kt        # host list in DataStore (metadata only)
      SecureStore.kt           # EncryptedSharedPreferences wrapper (ALL secrets)
      KnownHostsStore.kt       # TOFU known_hosts in app-private storage
      SettingsRepository.kt    # LLM base URL / model / timeouts (DataStore)
    ssh/
      SshConnectionManager.kt  # sshj wiring: connect, TOFU, auth, sessions
                               # SshSession: shell streams + agent exec channels
    llm/
      LlmClient.kt             # OpenAI-compatible chat client + run_command tool
      AgentLoop.kt             # autonomous loop: goal -> tool calls -> results
    ui/
      HostsScreen.kt           # host list + add/edit/delete (+ PEM import)
      SettingsScreen.kt        # LLM settings + "Test connection"
      TerminalScreen.kt        # terminal + extra keys + agent panel + dialogs
      TerminalViewModel.kt     # connection state machine, prompts, agent state
terminal-emulator/            # VENDORED Termux terminal-emulator (patched)
terminal-view/                # VENDORED Termux terminal-view (unmodified)
tools/
  fetch_deps.py                # curl-based dependency mirror -> local-repo/
local-repo/                   # file:// Maven repo (generated, not committed)
```

## Key integration finding: the Termux libraries are vendored, not the JitPack AARs

We started out planning to use
`com.github.termux.termux-app:terminal-emulator:v0.118.3` /
`:terminal-view:v0.118.3` from JitPack (both resolve fine; v0.118.3 is the
newest stable). Source review (tag `v0.118.3`) showed the stock AARs **cannot**
drive an SSH backend:

1. `TerminalSession.write()` silently drops every keystroke unless a local
   subprocess was spawned (`mShellPid > 0` gate).
2. `TerminalView.attachSession()` → `TerminalSession.updateSize()` calls native
   `JNI.createSubprocess` / `JNI.setPtyWindowSize` (`libtermux.so`) — it would
   spawn a *local* pty and crash with `UnsatisfiedLinkError` without the .so.
3. `attachSession(TerminalSession)` is type-locked and `TerminalSession` is
   `final`, so the behavior can't be changed by subclassing.

The termux-app `LICENSE.md` carries an explicit exception: the
`terminal-emulator` and `terminal-view` modules are **Apache 2.0** (derived
from jackpal's Terminal Emulator for Android), so vendoring is permitted with
attribution (see `terminal-emulator/VENDORED.md`).

The fork adds a minimal, clearly-marked **external-I/O mode** to
`TerminalSession` (~40 lines, search for "TermPilot"):

- `TerminalSession(TerminalSessionClient)` constructor — no local process, no JNI.
- `initializeEmulator()` skips `createSubprocess` + pty threads; `updateSize()`
  skips `setPtyWindowSize`.
- `write(byte[], int, int)` forwards to an app-supplied `OutputSink`
  (bypasses the `mShellPid` gate) — this is the single interception point for
  *all* keystroke paths (hardware keys, `KeyHandler`, IME `commitText`,
  extra-keys row, mouse reports).
- `feedInput(byte[], int, int)` delivers SSH channel output to the emulator on
  the main thread — same threading contract as the stock pty-reader path
  (`append()` is not synchronized; always feed via the handler).
- `TerminalView` is used **100% unmodified** (remember: `setTextSize()` must be
  called before `attachSession()`, otherwise `updateSize()` NPEs on the null
  renderer).

No native code is needed: rendering, UTF-8 decoding and input handling in
these two modules are pure Java (the only `System.loadLibrary` is `JNI`'s,
which the external-I/O path never touches).

## sshj notes (0.40.0, verified against sources)

- `HostKeyVerifier` has **two** methods: `verify(hostname, port, key)` and
  `findExistingAlgorithms(hostname, port)` (return null — safe).
- `verify()` runs on sshj's transport reader thread: the TOFU dialog is
  bridged with `runBlocking`, safe because the dialog lives on the main thread.
- Fingerprint for display: `Buffer.PlainBuffer().putPublicKey(key).compactData`
  → SHA-256 → base64 — this hashes the SSH **wire** encoding and matches
  `ssh-keygen -lf` exactly (`SecurityUtils.getFingerprint` is MD5-only).
- Auth: `authPassword`, `authPublickey(username, loadKeys(pem, null, finder))`
  (sshj decrypts OpenSSH-v1 keys incl. bcrypt KDF without BouncyCastle),
  keyboard-interactive via `auth(username, AuthKeyboardInteractive(provider))`
  — there is **no** `authKeyboardInteractive()` helper; `getResponse` returns
  `char[]`.
- Interactive shell: `startSession()` → `allocatePTY("xterm-256color", cols,
  rows, 0, 0, emptyMap())` → `startShell()`; resize via
  `shell.changeWindowDimensions(cols, rows, 0, 0)`.
- **One sshj `Session` = one channel.** Agent commands each open a fresh
  `client.startSession()` + `exec()`, multiplexed over the single connection
  and isolated from the interactive shell.
- BouncyCastle ships as an `implementation` dep and is registered at runtime
  under the name **"TermPilotBC"** (see `ssh/CryptoProviders.kt`). Why: Android
  ships its own stripped-down BouncyCastle as provider "BC", which shadows the
  bundled jar — sshj's auto-registration then resolves every
  `getInstance(alg, "BC")` to the *platform* BC, which lacks X25519/Ed25519
  ("no such algorithm: X25519 for provider BC"). `BouncyCastleProvider` is
  final, so instead of subclassing we copy its algorithm table into a thin
  `Provider` wrapper under the collision-free name and point sshj at it via
  `SecurityUtils.setSecurityProvider`. The wrapper is appended (not inserted
  first), so unrelated default JCE lookups (TLS, AndroidX Security) are
  unaffected. (SpongyCastle was considered and rejected: 1.58 predates X25519.)
- chacha20-poly1305 is self-contained in sshj.

## Secrets

| Secret | Storage |
|---|---|
| SSH passwords, private-key PEM, key passphrases, LLM API key | `EncryptedSharedPreferences` (`termpilot_secrets`, AES256-GCM via Keystore `MasterKey`) |
| Host list (labels, hostnames, usernames) | DataStore (not secret) |
| TOFU host keys | `filesDir/known_hosts` (OpenSSH format, app-private) |

Deleting a host wipes its secrets. Nothing secret is logged.

## Agent mode

Per-session toggle, **default OFF**. When enabled, a panel accepts a goal
("find large log files and compress them"). The loop POSTs to
`{baseUrl}/chat/completions` with
`tools=[{"type":"function","function":{"name":"run_command",…}}]`; each call
executes on a new SSH exec channel with captured stdout/stderr/exit code,
fed back to the model. Caps: **25 iterations/goal**, per-command timeout
(default 60 s, configurable 5–600 s), outputs truncated to 8 kB per stream.

Guardrails:

- The agent can only run commands — no separate tools, no file APIs.
- Every executed command is echoed into the terminal view (`$ <cmd>`) and
  into the transcript panel, along with outputs and the final summary.
- The red **STOP** button cancels the agent coroutine scope immediately;
  closing the exec channel unblocks in-flight reads, so STOP takes effect
  promptly even mid-command.
- System prompt instructs the model to inspect before acting, avoid
  interactive programs, be careful with destructive commands, and never use
  `sudo` unless the goal requires it.
- Agent mode never touches the interactive shell channel.

## Building

Prerequisites: JDK 17 (`JAVA_HOME`), Android SDK with `platforms;android-34`
(`ANDROID_HOME` / `ANDROID_SDK_ROOT`).

This repo was developed in a sandbox whose egress proxy breaks Java HTTP, so
dependencies are mirrored with curl (which works):

```bash
./setup-gradle-proxy.sh        # regenerates Gradle proxy config from live env
                               # (proxy credentials rotate; rerun as needed)
python3 tools/fetch_deps.py    # mirrors all deps into local-repo/
export JAVA_HOME=~/jdk17 ANDROID_HOME=~/android-sdk
./gradlew assembleDebug        # (or ~/gradle-8.7/bin/gradle assembleDebug)
```

Notes:

- `local-repo/` is a generated file:// Maven repo and takes precedence over
  `google()`/`mavenCentral()` in `settings.gradle.kts`. Re-run
  `fetch_deps.py` after changing dependencies.
- Sandbox quirk found while setting up: the Gradle client connects to its
  daemon via IPv4-mapped IPv6 (`::ffff:127.0.0.1`), which the daemon's
  IPv4-only listener rejects ("Could not receive a message from the daemon").
  `setup-gradle-proxy.sh` also sets `java.net.preferIPv4Stack=true` to fix it.
- The egress proxy MITMs TLS; its CA (`/usr/local/share/ca-certificates/
  hatch-egress-ca.crt`) must be in the JDK's `cacerts` for Gradle downloads.

Verify the APK:

```bash
aapt dump badging app/build/outputs/apk/debug/app-debug.apk
zipalign -c -p 4 app/build/outputs/apk/debug/app-debug.apk
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

**Build verified 2026-10-13:** `assembleDebug` succeeds; APK is 21MB,
package `com.termpilot.app`, minSdk 26, targetSdk 34, zipalign clean,
signed (debug key). See the report for sandbox-specific fixes
(android-34 ext12 platform, offline range pins, KMP relocations).

## Status / known limitations

- **v0.1.1 (2026-10-13):** fixed launch crash (missing `android:name` in the
  manifest). APK verified: badging, zipalign, debug signature all clean.
- **On-device testing still needed** — no device in this environment. Please
  verify: real SSH handshake (incl. Ed25519 host keys), keyboard-interactive
  against a real server, IME behavior, and the agent loop against a live LLM.
- `connect()` cancellation: sshj's blocking calls don't respond to coroutine
  cancellation; leaving the screen mid-connect relies on socket timeouts.
- Private-key passphrase: used from encrypted storage; if a key needs a
  passphrase that isn't stored, auth fails with an error (no interactive
  retry prompt yet in v1).
- Terminal font size is fixed (30 px); no configurable themes yet.
