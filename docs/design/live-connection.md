# Live connection: a notebook attached to the running app, and hot reload on every platform

Design investigation, 2026-10-17 (the lead's instruction; the ecosystem survey's third gap). No code
changes. Everything below "Facts" was checked in the repositories named; the rest is a proposal.

## 1. What the user's specification expects

The specification is `pythonx-compose/UI.ipynb` (the maintainer's 2024 lecture notebook) and
`python-multiplatform/docs/design/ecosystem.md` §3.

**The notebook's kernel is the running app's own interpreter.** Its cells do not describe a UI that is
later built; they act on the app that is on screen:

| Cell | What it does to the running app |
|---|---|
| `import main` then `main.App` | reaches the live root object of the app's UI |
| `main.App.messages.getValue()` / `.setValue("...")` | reads and writes UI state; the screen follows |
| `@Composable def NewUI(): ...` then `main.App.update(NewUI)` | replaces what the app draws, without a restart |
| `TextField(text_state=main.App.messages)`, then `main.App.messages.getValue()` | text typed on the device is read back in the notebook |
| `for chunk in token_streamer(...): print_messages(chunk)` | the app's on-device model streams tokens into the UI, driven from a cell |

So the experience is **cell runs, the running app changes, at once, both ways**: the notebook sees state
the user changed on the device, and the device shows what the cell set. It is not "edit a file, rebuild,
redeploy".

**How 2024 did it** (PyREPL, `app/src/androidMain/python/main.py` and `repl/kernel/kernel_android.py`,
Chaquopy): the Android app bundled `jupyterlab`, `jupyter`, `pyzmq` and `ipykernel`; a button started a
JupyterLab server inside the app and opened the device browser on it (IP, port and password shown on the
app's screen); `UIThreadKernelManager` launched `IPKernelApp` inside the app's own process ("UI thread
sync mode", one kernel at a time). Cells therefore executed in the app's interpreter, which is why
`import main` reached the live `App`.

**What exists now**:

- pythonx-compose has the UI half, rebuilt on python-multiplatform: `pythonx.compose.runtime.app(fn)`
  writes the root into a Compose `MutableState` (`app_root`) that the host draws, so redeclaring the root
  in a cell replaces the screen with no `update` call; `remember_saveable` returns state with
  `getValue`/`setValue`. python-multiplatform's notebook E2E (#26, CI job `notebook-e2e`) runs the
  notebook's scenarios **in-process in a desktop test host**: it proves the UI semantics, not a live
  connection from a notebook to a separately running app.
- toolchain's hot reload (SPEC §1.14) is Android only: `adb push` of every `.py` to `/data/local/tmp/...`
  and an `org.thisisthepy.python.RELOAD_PYTHON` broadcast. Its `serverHost` and `cert` settings are
  validated but carry nothing. python-multiplatform has no handler for that broadcast (no `RELOAD_PYTHON` in its runtime, plugin or compose sources), so today's hot reload pushes files nothing reads.
- toolchain's code push (SPEC §1.15) validates its DSL and uploads nothing.
- python-multiplatform's development-mode import gate (SPEC N-7b, #183) already defines what happens when a
  notebook cell or a hot reload imports a project module: it is type-checked first.

So the gap is exactly the **channel**: nothing connects a notebook (or a file save) on the laptop to the
interpreter inside a running desktop, Android or iOS app.

## 2. Getting changed Python into the running app, per platform

Two things travel over the same channel, so it is worth having one:

1. **Code to execute** (a notebook cell): run in the app's interpreter, in a namespace that persists across
   cells, with stdout, stderr, results and errors sent back.
2. **Files that changed** (hot reload on save): written into the app's payload directory, then the
   affected modules re-imported.

### Transport

| Platform | How the laptop reaches the app | Notes |
|---|---|---|
| Desktop | `127.0.0.1:<port>` | same machine |
| Android emulator / device | the app listens on its loopback; `adb forward tcp:<port> tcp:<port>` | needs `INTERNET` permission, in the debug manifest only; no network exposure |
| iOS simulator | `127.0.0.1:<port>` | the simulator shares the Mac's network stack |
| iOS device | `iproxy` / usbmuxd port forwarding over USB, or the local network with Bonjour discovery | the only case without a loopback path; local network needs `NSLocalNetworkUsageDescription` |

A socket for both, not file synchronisation: file sync (today's `adb push`) cannot run code, has no
equivalent on iOS, and cannot carry results back. Files go over the socket as content and are written by
the app itself into its payload directory, which is where python-multiplatform already loads from.

### Re-import

- A cell's code is `exec`'d into one persistent namespace (`__main__`, so `import main` and module state
  behave as in 2024).
- A changed file replaces its payload copy, then `importlib.reload` runs on the modules whose files changed,
  children first. Functions and objects already held by the UI keep their old code (plain CPython
  semantics), so the UI is refreshed by re-declaring the root: after a reload, the runtime calls the root
  module's root function again through pythonx-compose's `app(...)` if the root's module was among them.
- The N-7b import gate runs on every re-import (it is already defined for this case): a type error stops
  the reload and is sent back to the notebook or the build output, and the app keeps running the old code.
- Execution thread: by default on the channel's own thread (Compose snapshot state may be written from any
  thread; the interpreter is free-threaded 3.15t). A cell that must touch main-thread-only Kotlin API can ask
  for the main thread (PyREPL's "UI thread sync mode"); python-multiplatform dispatches it to the platform's
  main dispatcher.

## 3. Attaching a Jupyter kernel to the app's interpreter

| | A. `ipykernel` inside the app (2024's approach) | B. Proxy kernel on the laptop + a small agent in the app (proposed) |
|---|---|---|
| In the app | ipykernel, jupyter_client, pyzmq + libzmq, tornado, traitlets... | a stdlib-only agent: one TCP connection, JSON messages |
| Native dependencies in the app | pyzmq/libzmq. **Checked 2026-10-17: pyzmq 27.2.0 has `cp315t` wheels for desktop only; Android has `cp315` (GIL) only, iOS and WASM none.** pypackpack would have to build pyzmq and libzmq for every mobile target | none |
| Ports to forward | five ZMQ sockets (shell, iopub, stdin, control, heartbeat) per app | one |
| Jupyter fidelity | complete: completion, inspection, comms, ipywidgets, interrupts as far as ipykernel manages them in a non-main thread | what the proxy implements: execute, stdout/stderr, results and errors, completion and inspection forwarded; comms and widgets later or never |
| Free-threaded 3.15t, in-process | ipykernel's threading and signal assumptions on a non-main thread, untested on 3.15t | the agent owns its threading |
| App size and attack surface | large, debug builds only | small, debug builds only |
| Works for hot reload too | no (a kernel, not a file channel) | yes, same connection |

**Proposed: B.** The laptop runs an ordinary Jupyter kernel (a subclass of `ipykernel.kernelbase.Kernel`,
installed as a kernelspec such as "usage-example (desktop debug)") that forwards `execute_request`,
`complete_request` and `inspect_request` over one socket to an agent inside the app, and relays the agent's
streams, results and errors back as Jupyter messages. The agent speaks a small subset of Jupyter's own
message shapes (`execute_request` / `stream` / `execute_result` / `error` / `complete_reply`), so moving to A
later stays possible. Jupyter itself (JupyterLab, VS Code, PyCharm) needs nothing special: it talks to a
normal kernel on the laptop.

Also considered: **JupyterLab served from inside the app** (the 2024 setup in full): heavier still, and
the notebook ends up in the device's browser. **debugpy**: a debugger, not a REPL; complementary
(breakpoints), not a replacement, and also a C extension.

Security, for any option: the agent exists in debug builds only (toolchain already gates hot reload on
`enableHotReload` per build type); it listens on loopback only (iOS device: USB or explicit opt-in to the
local network); every connection presents a token generated at build or launch and given to the laptop
side (adb, the simulator's container, or the desktop launcher), never typed by hand. The `serverHost` and
`cert` settings toolchain validates today map onto the iOS-device and remote cases.

## 4. Which repository owns what

| Repository | Owns |
|---|---|
| **python-multiplatform** (runtime) | The in-app agent: started in debug builds, its socket, the token, `exec` into a persistent `__main__`, stream capture, main-thread dispatch, writing pushed files into the payload directory and re-importing them (with N-7b's gate), and the iOS local-network string for the device case. toolchain's `AGENTS.md` §13 already says reacting to the hot-reload broadcast belongs here. |
| **toolchain** (developer side) | Finding and reaching the running app per platform (`adb forward`, the simulator's loopback, the desktop launcher's port, `iproxy` for a device); the `hotReloadPython` task rebuilt on the channel (replacing `adb push` and the broadcast), with a watch mode; the Android debug manifest's `INTERNET` permission, added by the toolchain plugin to debug variants only (Android refuses even the agent's loopback socket without it, `[Errno 1] Operation not permitted`, found on an arm64 emulator running python-multiplatform #544 with toolchain #154; leader decision, 2026-11-27, team report); installing the kernelspec for a variant; the proxy kernel itself, shipped in `toolchain_lite` (the PyPI package `tcl` already lives in) as `tcl kernel install` / `tcl connect`, so a notebook user needs only `uv tool install toolchain-lite`. |
| **pythonx-compose** (UI semantics) | What "replace the screen" and "UI state" mean: `app(...)`, `remember_saveable`, and re-declaring the root after a module reload. Already implemented for the notebook's semantics; the reload hook is the only addition. |
| **pypackpack** | Nothing on B's critical path. Under A it would build pyzmq and libzmq for Android and iOS 3.15t. |

## 5. First milestone

**M1, desktop, end to end**: `usage-example` (desktop, debug) runs; its agent listens on `127.0.0.1` with a
token; `tcl kernel install` registers the "usage-example (desktop debug)" kernelspec; a notebook running the
`UI.ipynb` cells (`import main`, state `getValue`/`setValue`, re-declaring the root) changes the open window
and reads back what was typed in it. Tests: the agent's protocol (execute, streams, errors, persistent
namespace, a reload under the import gate) without a UI; and a CI job that starts the packaged desktop app
and keeps it running (under Xvfb: today's `desktop-e2e` runs the app headless only for a `--python-smoke`
check that exits before any UI), connects the proxy kernel, runs the scenarios through Jupyter's client API
and fails if any regresses. Disabling the agent must make it fail.

Then:

- **M2, Android**: `adb forward`; `hotReloadPython` moves onto the channel (the `adb push` + broadcast path is
  removed, as it has no receiver); the `android-e2e` job runs the same scenarios on the emulator.
- **M3, iOS simulator**: loopback; `ios-e2e` runs them.
- **M4, iOS device**: `iproxy` or Bonjour; manual check only (no device in CI).
- **M5, code push** (SPEC §1.15) reuses the file half of the channel against a server instead of a laptop;
  separate from FastTrack's deploy (pypackpack), which waits for the server-ownership decision.

On-device notebook mode (a notebook with no laptop): the owner answered (2026-11-22 05:45, relayed by the team
lead in the toolchain report, "Owner answers"): "목표 맞는데 주피터는 아니고 다크피닉스 커널로 바뀌었어". It is a goal,
reached through the darkpyonix kernel instead of JupyterLab, so A is not brought back for it. M1 to M3 go on as planned.

The darkpyonix team lead's answer (2026-11-22, team message to toolchain):
- Location: repository DarkPyonix/darkpyonix, folder `darkpyonix/kernel/`, the Python package `darkpyonix`,
  started with `python -m darkpyonix <notebook file>` (one kernel per source file per machine). A Rust
  manager (`darkpyonix/manager/`) is an optional HTTP front.
- Protocol: its own, not Jupyter messaging: `docs/PROTOCOL.md` (discovery datagrams plus JSON control frames
  over a local socket), notebook format `docs/FORMAT.md`.
- Standard library only, no native dependencies, CPython 3.8+ from source, so in-process use in the app is
  plausible, but untested on free-threaded 3.15t and on Android and iOS. darkpyonix's INTENT D20 puts mobile
  notebook execution in a fixed in-app process owned by the Ember mobile app.
- Agent and kernel: the in-app agent (L-12) could be replaced by the darkpyonix kernel speaking PROTOCOL.md,
  or front it. Not decided by either team. Next step (agent proposal, not confirmed): read PROTOCOL.md and
  compare it with L-12's ops; darkpyonix offered a compatibility check by one of its core teammates.

DKP/1 (darkpyonix `docs/PROTOCOL.md`, read 2026-11-22) against L-12 (agent proposal, not confirmed):

| | L-12 dev agent | DKP/1 darkpyonix kernel |
|---|---|---|
| Discovery | the connection file the build or launcher writes (`build/pythonDevAgent/<task>.json`) | UDP multicast on 127.0.0.1 (`239.255.68.80:46880`, TTL 0), announce every 5 s |
| Transport | TCP on 127.0.0.1, one port | a Unix domain socket (POSIX) or a named pipe (Windows); no TCP port |
| Authentication | a 43-character token in `hello` | the OS peer account must be the kernel's own (`SO_PEERCRED` / `LOCAL_PEERCRED` / pipe SID); no secret |
| Framing | one JSON object per line | 4-byte big-endian length plus a JSON object |
| Unit of execution | `execute {code}`: any code into `__main__` | `run {mode: all/cells, cells or cell_ids, source?}` against the kernel's notebook file; outputs as nbformat events |
| Completion, hot reload | `complete`, `push` (overlay files, re-import) | none (namespace listing, runs history, collaborative document instead) |

What follows:
- DKP/1 cannot be the laptop-to-app channel (M1 to M3) as it stands. Its account check rejects any client that is not the app's own OS user. Through `adb forward` the peer is `adbd`, and on Android a second app (Ember) has its own uid. It also has no TCP transport, no completion and no file push.
- For the on-device mode, it fits when the darkpyonix kernel and its client share one process, or one app's uid. That is darkpyonix's own D20: notebook execution in a fixed in-app process owned by the Ember mobile app.
- So for apps built with toolchain, the choices are:
  - (a) Embed the darkpyonix kernel in the app beside the L-12 agent, with an on-device client inside the same app.
  - (b) An L-12 to DKP bridge. A DKP `run` maps to L-12 `execute` cell by cell, and the outputs map back.
  - (c) DKP/1 grows a token-authenticated TCP transport, so it can be reached from another uid.

The darkpyonix lead's answers (2026-11-22, team message, checked against darkpyonix-core develop 001cde4):
- (c) is ruled out: darkpyonix's D5 has no TCP port and the same account only, and "loopback TCP control
  channel" is in its rejected table. Its remote path is client, then a manager that checks the kernel's tokens
  (PROTOCOL §6), then a hand-off to the kernel (§3.7); on mobile that manager would live inside the app. Changing
  D5 is darkpyonix's owner's decision.
- Correction: the peer-account check is between processes of one uid, not one process. D20 runs kernels in
  predeclared processes of the same app (`:kernel0` to `:kernel9`, the PyREPL model), and puts the mobile path in
  Ember, status planned.
- In-process as a thread is not supported today. User code runs on the kernel's main thread, `run()` installs
  signal handlers, interrupts are SIGINT, and a hard restart is `os.execv`. A notebook file on disk is required
  (the kernel takes its id and lock from the path). Changing that is a darkpyonix SPEC item.
- There is no completion method, and none is planned. `run` with `source` replaces the file's text (an unsaved
  buffer parsed as a notebook), not a snippet eval. Either would be a new darkpyonix SPEC or PROTOCOL item.

So, for apps built with toolchain (agent proposal, not confirmed), what fits darkpyonix's rules today is:
- (a) The darkpyonix kernel in its own same-uid process of the app (an Android `:kernelN` process), beside the
  L-12 agent in the app process, with a notebook file in the app's storage.
- (b) A manager-style bridge inside the app.

Either is a design for the on-device mode, not part of M1 to M3. It is the owner's to choose, with the darkpyonix
team for anything that touches their D20 or SPEC.
