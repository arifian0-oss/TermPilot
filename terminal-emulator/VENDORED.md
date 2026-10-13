# Vendored Termux terminal libraries

`src/main/java/com/termux/terminal/*` (this module) and
`../terminal-view/src/main/java/com/termux/view/*` are copied from
[termux/termux-app](https://github.com/termux/termux-app) at tag **v0.118.3**.

## Why vendored instead of the JitPack AARs

The published AARs (`com.github.termux.termux-app:terminal-emulator/v0.118.3`,
`:terminal-view/v0.118.3`) cannot be driven by an SSH backend as-is:

1. `TerminalSession.write()` silently drops all keystrokes unless a local
   subprocess was spawned (`mShellPid > 0` gate).
2. `TerminalView.attachSession()` → `TerminalSession.updateSize()` calls the
   native `JNI.createSubprocess`/`setPtyWindowSize` (`libtermux.so`), which we
   neither ship nor want — it would spawn a *local* pty.
3. `attachSession(TerminalSession)` is type-locked and `TerminalSession` is
   `final`, so the behavior cannot be overridden by subclassing.

## License

The termux-app `LICENSE.md` states the repository is GPLv3 **with an explicit
exception**: the `terminal-emulator` and `terminal-view` modules are
**Apache License 2.0** (derived from jackpal's *Terminal Emulator for Android*),
so vendoring them here is permitted. Keep this notice and the original
`LICENSE` attribution if you redistribute.

## TermPilot patch (external-I/O mode)

`com.termux.terminal.TerminalSession` carries a minimal, clearly-marked patch
(search for "TermPilot" in the file):

- New constructor `TerminalSession(TerminalSessionClient)` — external-I/O mode,
  no local process, no JNI.
- `initializeEmulator(...)` skips `JNI.createSubprocess` and the pty threads in
  external mode; `updateSize(...)` skips `JNI.setPtyWindowSize`.
- `write(byte[], int, int)` forwards to an app-supplied `OutputSink` instead of
  the pty queue (bypasses the `mShellPid` gate).
- New `feedInput(byte[], int, int)` delivers remote output bytes to the emulator
  on the main thread (same threading as the stock pty-reader path).
- `finishIfRunning()` marks the session finished without `SIGKILL` in external
  mode; `handleMessage` null-guards the not-yet-sized emulator.

`terminal-view` is used **unmodified**.

## Note: comments stripped from TerminalEmulator.java

`com/termux/terminal/TerminalEmulator.java` is shipped **without comments**
(license header preserved in this file and in README attributions). The code
is byte-identical in behavior to termux-app v0.118.3 plus the TermPilot
patch — only `//` and `/* */` comments and blank-line runs were removed, and
the tree was rebuilt from the stripped source to verify. Reason: the file
exceeds single-file push limits on some Git hosting APIs.
