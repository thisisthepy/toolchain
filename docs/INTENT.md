# Intent

This file says what `toolchain` is for, and what it deliberately is not. It is the boundary:
`docs/SPEC.md` may not promise anything this file does not cover. A request that falls outside it
is a conversation with the maintainer, not an implementation task.

## Where this comes from

The intent is derived from what the maintainer wrote by hand, in this order of authority:

| Source | What it says |
|---|---|
| `(플러그인예시)build.gradle.kts` (repository root, untracked, user-authored) | The target DSL: the whole `python { }` block a Kotlin Multiplatform app is meant to write. |
| GitHub issue [`toolchain#2`](https://github.com/thisisthepy/toolchain/issues/2), "Kotlin Gradle Plugin and Build Tools" | The plugin checklist: Python version setup, target platforms, hot reload / code push, source sets, compile levels, build automation. |
| GitHub issue [`toolchain#1`](https://github.com/thisisthepy/toolchain/issues/1), "Toolchain-lite for python-only users" | A simplified CLI for Python users: `tcl install pythonx-compose`. |
| `pyproject.toml` / the original `README.md` | "Python Multiplatform Build Plugin/Tool with Kotlin Multiplatform Mobile". |
| `usage-example/` | The executable subset of the target DSL that the plugin reads today. |

Anything below that is an inference rather than a statement from those sources is marked
`> Inferred: confirm with the maintainer.`

## 1. What toolchain is for

**A Kotlin Multiplatform developer declares the Python half of their app in Gradle, in one
`python { }` block, and the build takes care of the rest.**

That block, as `(플러그인예시)build.gradle.kts` writes it, covers:

1. **The Python version** the app is built against (`compileSdk = "3.11.9-alpha"`), with release
   channels (alpha, rc, normal; issue #2: "Version Enum (alpha, rc, normal)").
2. **The target platforms**: Android, iOS and desktop, each with its architectures and a minimum
   SDK (`androidSdk = 24`, `iosSdk = 14`), checked against the targets the Kotlin side has enabled
   (issue #2: "Check Kotlin-side enabled build target").
3. **How the interpreter and code are packaged** (`packaging { embedLevel, fileName }`): no
   interpreter, an external one, or one embedded in the app.
4. **Build types** with a compile level each: debug as `bytecode` (`.py` + `.pyc`) or `instant`
   (`.py` only), release as `native` or `mixed`, plus release-only minification, metaclass
   exclusion and code push.
5. **Developer loop features**: hot reload for debug builds, code push for release builds (issue #2:
   "Expose direct run button for hot reload server").
6. **Source sets and dependencies** mirroring Kotlin's: `srcDirs`, `metaDirs`, `libDirs` and
   per-source-set `implementation("pkg")` (pure Python) and `integration("pkg")` (a Python package
   that depends on Kotlin, recognised by a `KLIBDEPENS` file in its wheel).
7. **Build features**: `metaclass` and `compose` (the Compose wrapper).
8. **Automation**: "Automate the build process for Python and integrate it with the Kotlin
   Multiplatform project" (issue #2): a Gradle build produces an app that carries its Python.

And, for people with no Gradle project at all:

9. **`tcl`, toolchain-lite**: "automated/simplified cli for python users" (issue #1), whose one
   specified command is `tcl install <package>`.

## 2. Where toolchain stops

`toolchain` is one of several repositories meant to compose into one product:

| Repository | Owns |
|---|---|
| `pypackpack` (`ppp`) | The work: acquiring Python, resolving dependencies, cross-compiling, bundling. |
| **`toolchain`** | **The Gradle (and `tcl`) vocabulary for that work.** |
| `python-multiplatform` | The runtime: CPython embedded in Kotlin Multiplatform, and everything at the language boundary. |
| `pythonx-compose` | Compose bound into Python. |

> Inferred: confirm with the maintainer. The split "ppp owns the work, toolchain owns the Gradle
> vocabulary" is recorded in `python-multiplatform`'s `docs/ecosystem.md` §1 and is what the code
> does (`BundlerInterface.create(BundleType.RESOURCE)`, `BackendInterface.create(BackendType.UV)`),
> but no maintainer-authored file in this repository states it in those words. The example build
> file is consistent with it: nothing in it describes *how* a dependency is resolved, only *what* is
> declared.

## 3. What toolchain deliberately is not

- **Not a package manager or bundler of its own.** A feature that acquires a Python distribution,
  resolves a dependency or builds a bundle belongs in `pypackpack`, even when a Gradle task is what
  triggers it. *(Inferred from §2: confirm.)*
- **Not the Python runtime.** Loading the bundle, putting it on `sys.path`, and reacting to a hot
  reload signal happen in `python-multiplatform`. *(Inferred from §2: confirm.)*
- **Not a code-push server.** The example build file points `codePush.serverHost` at a host the
  user runs; nothing in it asks toolchain to provide that server. *(Inferred: confirm.)*
- **Not an automatic builder of brand-new CPython releases.** Issue #2 says verbatim: "do not
  support automatic build for new python release".

## 4. Open questions for the maintainer

1. ~~**Automatic build of an unknown `compileSdk` version.**~~ *Decided 2026-10-03:* a version
   given as a string (`"3.11.9-alpha"`) is built automatically when the server does not have it; a
   version given as a named constant (`PY3_11_9_ALPHA`) is restricted to versions the server has.
2. ~~**The DSL shape for platforms.**~~ *Decided 2026-10-03:* the example's shape; platform blocks
   and variant calls directly inside `python { }`. Android variants follow CPython's official
   Android support (PEP 738: arm64 and x86_64); the example's `androidArm32()` / `androidX86()`
   predate that decision and are compile errors.
3. ~~**`pip { repositories { central / local / jit } }`.**~~ *Decided 2026-10-03:* the example's
   spelling (`central`, `local`, `jit` with `localRecipes`, and `autoUpdate`).
4. **Is `tcl` meant to grow beyond `install`?** Issue #1 shows only `install`.
