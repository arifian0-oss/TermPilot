# TermPilot — Security notes

TermPilot is security-sensitive software: it holds SSH credentials and can
run commands on remote machines autonomously. This document describes the
threat model and the protections in v1.

## Secrets at rest

| Secret | Storage | Protection |
|---|---|---|
| SSH passwords, private-key PEMs, key passphrases, LLM API key | `EncryptedSharedPreferences` (`termpilot_secrets`) | AES-256-GCM, key in Android Keystore (`MasterKey`) |
| Host list (labels, hostnames, usernames, ports) | DataStore | Not secret, app-private |
| TOFU host keys | `filesDir/known_hosts` (OpenSSH format) | App-private storage |

- Deleting a host wipes its secrets from encrypted storage.
- No secret is ever logged, including in the agent transcript or crash logs.
- The LLM API key is sent only to the user-configured base URL, over HTTPS
  (or plain HTTP only if the user explicitly configures a LAN address).

## Host authentication

- **TOFU with user verification.** First connect shows the server key's
  SHA-256 fingerprint (computed over the SSH wire encoding — matches
  `ssh-keygen -lf`). The user must explicitly Trust; the key is pinned
  afterwards and mismatches abort the connection.
- No silent trust-on-first-use, no `PromiscuousVerifier` in the app path.
- Auth methods: password, keyboard-interactive, private key (PEM, including
  encrypted OpenSSH-v1 keys — sshj decrypts them in-memory; passphrases are
  read from encrypted storage, never written to disk in plain text).

## Agent mode

The agent executes real shell commands as the SSH user. It is powerful by
design; the guardrails are:

1. **Opt-in per session, default OFF.** Enabling it is a deliberate action.
2. **Capability-limited.** The model gets exactly one tool: `run_command`.
   No file-transfer APIs, no port forwarding, no shell escapes outside it.
3. **Bounded.** 25 iterations per goal, per-command timeout (default 60 s,
   configurable 5–600 s), stdout/stderr truncated to 8 kB per stream.
4. **Transparent.** Every command is echoed into the terminal view and the
   transcript panel with its output and exit code, plus a final summary.
5. **Stoppable.** The STOP button cancels the agent coroutine scope; the
   exec channel is closed, unblocking in-flight reads promptly.
6. **Isolated.** Agent commands run on fresh SSH exec channels, never on the
   user's interactive shell channel.
7. **Prompted restraint.** The system prompt instructs: inspect before
   acting, prefer read-only commands first, avoid interactive programs,
   be careful with destructive commands, never `sudo` unless the goal
   requires it.

These are guardrails, not guarantees: a sufficiently determined model (or a
careless goal like "free up disk space") can still cause damage. Only enable
agent mode on hosts where you accept that risk, and prefer a restricted SSH
user for autonomous work.

## Network

- SSH: standard sshj transport (host key verified as above).
- LLM: HTTPS to the configured endpoint. Certificate validation is the
  platform default; there is no pinning and no cleartext fallback except a
  user-configured `http://` LAN address.

## Known gaps (v1)

- No biometric / device-credential gate before revealing secrets in UI.
- No per-host agent allowlist; agent mode is available on any connected host.
- `connect()` cancellation relies on socket timeouts (sshj blocking calls).
- Debug-signed APK: fine for sideloading, but verify the APK hash from a
  source you trust before installing updates.
