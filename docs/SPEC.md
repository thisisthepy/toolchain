# Specification

What `toolchain` does — the behavioural contract. Every item stays inside `docs/INTENT.md`; an item
that does not is listed at the end under **Outside intent — needs a decision**.

Each item carries a status:

- **implemented** — the behaviour exists in code *and* a test in this repository exercises it
  (the test file is cited).
- **partial** — some of the behaviour exists, or it exists but no test here exercises it. What is
  missing is said.
- **planned** — the example build file or an issue asks for it; the code does not do it (or only
  declares a DSL property that nothing reads).

Status was assigned on 2026-10-02 by reading `toolchain/src`, `tcl/src` and their tests. No status
here comes from a roadmap, a commit message or an issue checkbox.

Paths below are abbreviated: `plugin/` = `toolchain/src/main/kotlin/org/thisisthepy/python/multiplatform/toolchain/`,
`ptest/` = the matching `toolchain/src/test/kotlin/...` directory, `tcl/` = `tcl/src/{main,test}/kotlin/org/thisisthepy/python/multiplatform/tcl/`.

---

## 1. Plugin

### 1.1 Applying the plugin
Plugin id `org.thisisthepy.python.multiplatform`, implementation class `PythonPlugin`
(`toolchain/build.gradle.kts` `gradlePlugin { }`). Applying it creates the `python` extension
(`PythonExtension`) and registers the tasks of §1.10–§1.15 and §1.18, all in group `python`.
Consumers resolve it from `mavenLocal()` after `./gradlew :toolchain:publishToMavenLocal`; the plugin
itself depends on `org.thisisthepy.python.multiplatform:packpack:0.1.0` from `mavenLocal()`.

**Status: partial** — the wiring exists (`plugin/PythonPlugin.kt`), but no test in this repository
applies the plugin to a Gradle `Project`; it is exercised only by building `usage-example`.

### 1.2 `compileSdk` — the Python version
`compileSdk` accepts `X.Y`, `X.Y.Z`, `X.Y.Z-alpha[N]` or `X.Y.Z-rc[N]` and classifies it into
`PythonReleaseChannel { ALPHA, RC, NORMAL }`. `toReleaseString()` strips the channel to the `X.Y.Z`
form `pypackpack` accepts. A blank value is a silent skip; a malformed one fails configuration.

**Status: implemented** — `plugin/dsl/PythonVersion.kt`; `ptest/dsl/PythonVersionTest.kt`.

The parsed version is logged and handed to `buildPython` as `pythonVersion`, which only logs it: it
does **not** choose which interpreter is bundled. Named version constants such as
`PY3_11_9_ALPHA` (example build file) do not exist. → *Interpreter selection by `compileSdk` and
version constants: **planned**.* → *A string version the server lacks is built automatically; a
named constant is restricted to server versions (INTENT §4.1, decided): **planned**.*

### 1.3 `defaultConfig { versionCode, versionName, pip { … } }`
The DSL classes exist (`plugin/dsl/DSLCore.kt`) with different names from the example
(`autoUpdateImplicitDependencies` for `autoUpdate`; `pipCentral`/`pipLocal`/`pipJit` for
`central`/`local`/`jit`; no `url =` property and no `localRecipes`). Nothing reads any of it.

**Status: planned.**

### 1.4 Platforms
Declared directly inside `python { }`, as the example build file writes them:
`android("android") { androidSdk = 24 }`, `listOf(androidArm64(), androidX64())`, `ios { iosSdk = 14 }`,
`listOf(iosArm64(), …)`, `desktop()`, `listOf(macosX64(), …)`. Calling a variant function declares
that variant (the `listOf` only groups); a variant called without its platform block creates the
block with its defaults; a platform block may come before or after its variants; a variant
called twice is declared once. There is no `platforms { }` block.
Each variant maps to a `pypackpack` target triple and a Kotlin target name:

| Variant | Target triple | Kotlin target |
|---|---|---|
| `androidArm64` | `aarch64-linux-android` | `androidNativeArm64` |
| `androidX64` | `x86_64-linux-android` | `androidNativeX64` |
| `iosArm64` | `arm64-apple-ios` | `iosArm64` |
| `iosX64` | `x86_64-apple-ios-simulator` | `iosX64` |
| `iosSimulatorArm64` | `arm64-apple-ios-simulator` | `iosSimulatorArm64` |
| `macosX64` / `macosArm64` | `x86_64-apple-darwin` / `aarch64-apple-darwin` | same name |
| `linuxX64` / `linuxArm64` | `x86_64-unknown-linux-gnu` / `aarch64-unknown-linux-gnu` | same name |
| `mingwX64` | `x86_64-pc-windows-msvc` | `mingwX64` |

Android follows CPython's official Android support (PEP 738): `androidArm32()` and `androidX86()`
are **compile errors** (`@Deprecated(level = ERROR)`) whose message names the two supported
triples. An unknown name reaching the mapping is rejected with the supported list. A
declared variant whose Kotlin target is not enabled in the consumer's `kotlin { }` block produces a
**warning**, not a failure. `androidSdk`/`iosSdk` of `0` mean "undeclared"; negative values are
rejected; a declared value is forwarded to `pypackpack` as `BundleRequest.minSdk`.

**Status: implemented** — `plugin/dsl/DSLPlatforms.kt`, `plugin/dsl/DSLCore.kt`, `plugin/PythonPlugin.kt`;
`ptest/dsl/PythonExtensionPlatformDslTest.kt` (the example's declarations through `PythonExtension`),
`ptest/dsl/PlatformTargetMappingTest.kt`, `ptest/PythonPluginPlatformsTest.kt`,
`ptest/PythonPluginVariantGraphTest.kt`, `ptest/bundle/BuildPythonArtifactTaskTest.kt` (minSdk).

The shape is the example build file's (INTENT §4.2, decided). The example's 32-bit/x86 Android
variants predate the decision to follow CPython's Android support and are not built.

### 1.5 Build types
`buildTypes { getByName("debug") { … }; getByName("release") { … } }`. Only `debug` and `release`
exist; any other name throws. `debug` forbids setting `useCodeMinifier`, `excludeMetaclass` and
`enableCodePush` (setting them throws). Without a declared platform variant the active build type is chosen
by `-Ppython.buildType=<name>` (default `debug`); an undeclared name fails loudly.

**Status: implemented** — `plugin/dsl/DSLBuild.kt`, `resolveActiveBuildType` in `plugin/PythonPlugin.kt`;
`ptest/PythonPluginBuildTypeTest.kt`. (The debug-only setter rejections have no test: partial.)

### 1.6 `compileLevel`
Blank resolves to `instant`; `instant` passes. `bytecode`, `native` and `mixed` are rejected with
"not implemented by pypackpack's resource bundler yet" — **inside the variant's task action**, so
`--continue` still builds every other variant.

**Status: implemented** (the rejection) — `resolveBuildLevel`; `ptest/PythonPluginBuildLevelTest.kt`,
`ptest/PythonPluginVariantGraphTest.kt`.
→ *Building at `bytecode` / `native` / `mixed`: **planned**, blocked on `pypackpack`.*

### 1.7 `useCodeMinifier`, `excludeMetaclass`
Declared on `ReleaseBuildType`; nothing reads them. **Status: planned.**

### 1.8 Per-variant task graph
When at least one platform variant is declared, every variant is crossed with every declared build
type (or `debug` alone if none is declared). Each pair gets
`buildPython<Variant><BuildType>` and `packagePython<Variant><BuildType>`, writes to
`build/pythonBundle/<variant>-<buildType>/`, and zips to
`build/distributions/<fileName>-<variant>-<buildType>.zip`. `buildPython` and `packagePython` become
lifecycle tasks (their own action is skipped). Without a declared variant there is exactly one
host-target chain, as before.

**Status: implemented** (resolution and naming) — `resolveVariants`, `PythonVariant`;
`ptest/PythonPluginVariantGraphTest.kt`. The task registration itself is untested here.

### 1.9 Source sets
`sourceSets { val commonMain by getting { srcDirs(…); metaDirs(…); libDirs(…) } }`. The three
directory functions are accepted only on `commonMain` and throw elsewhere. The package directory is
`localLibraryPath` if set, else the **first** `commonMain.srcDirs` entry, else none (bundling and
installation are skipped). Every `metaDirs` and `libDirs` entry is forwarded to `pypackpack` as
`BundleRequest.metaDirs` / `libDirs`.

**Status: implemented** — `resolvePackageDir`, `resolveMetaDirs`, `resolveLibDirs`;
`ptest/PythonPluginSourceSetTest.kt`, `ptest/bundle/BuildPythonArtifactTaskTest.kt`.

### 1.10 Dependencies and `installPythonDependencies`
`dependencies { implementation("pkg"); integration("pkg") }` in any source set. All source sets'
`implementation` **and** `integration` entries are flattened into one list and installed into the
package directory through `pypackpack`:
`BackendInterface.create(BackendType.UV)` → `addDependencies(…, workingDir = packageDir)` (a real
`uv add`). An empty list or no package directory is a skip, not a failure.

**Status: implemented** — `plugin/dependency/lang/python/InstallDependenciesTask.kt`;
`ptest/PythonPluginDependencyTest.kt`, `ptest/dependency/lang/python/InstallDependenciesTaskTest.kt`
(needs `uv` and network).
→ *Per-source-set targeting (an `androidMain` dependency only for Android): **planned** — today
every dependency is installed for every target.*
→ *`integration()` checking the wheel for `KLIBDEPENS` and warning when absent: **planned**.*

### 1.11 Bundling — `buildPython`
Builds a `pypackpack` `BundleRequest` (package dir, target triple, build type, build level, output
dir, `overwrite = true`, minSdk, metaDirs, libDirs) and calls
`BundlerInterface.create(BundleType.RESOURCE).bundle(request)`. The result is `python/` plus a
`resource-manifest.json`. A failure becomes a `GradleException`. No package directory: the output
directory is created empty and `pypackpack` is not called.

**Status: implemented** — `plugin/bundle/BuildPythonArtifactTask.kt`;
`ptest/bundle/BuildPythonArtifactTaskTest.kt`.

### 1.12 Packaging — `packagePython`
Zips the bundle directory to `build/distributions/<fileName>.zip` (or `<fileName>-<variant>.zip`).
Fails if the bundle directory does not exist.

**Status: partial** — `plugin/bundle/AssemblePythonPackageTask.kt`; no test here.
→ *`embedLevel` (0 no interpreter / 1 external / 2 embedded, auto-raised with a warning where a
platform cannot honour it, overridable from `gradle.properties`): **planned** — it is only logged.*

### 1.13 Staging into the app — `stagePythonBundle{Android,Ios,Desktop}`
Copies the bundle's `python/` subtree (never the manifest) into
`build/pythonStaging/<android|ios|desktop>/python/`, deleting what a previous run staged first. The
bundle payload is a declared input, so a changed package re-stages. One variant is chosen per
destination: only variants of the active build type; desktop prefers the host's own triple, else the
first declared; Android and iOS take the first declared. A destination with no matching variant
stages nothing.

**Status: implemented** (copy rule and selection) — `plugin/bundle/StagePythonBundleTask.kt`,
`selectStagingVariants`; `ptest/bundle/StagePythonBundleTaskTest.kt`, `ptest/PythonPluginStagingTest.kt`.

Hand-off to the platform's packaging step:

- Desktop: the staged root is added to the JVM target's `<target>ProcessResources`, so the payload
  is in the desktop jar. **partial** — untested here.
- Android: the staged root is added to `android.sourceSets.main.assets` (reflectively), so the
  payload is in the APK's `assets/`. **partial** — untested here.
- iOS: staged but **not attached** to the Xcode project. **planned.**
- Putting the staged `python/` on `sys.path` at run time is `python-multiplatform`'s side.

### 1.14 Hot reload — `hotReloadPython`
`packaging { hotReload { serverHost; redirectErrorStream; cert { keyStore | autoGenerate } } }` is
validated when any build type sets `enableHotReload = true`: `serverHost` must be non-blank, and
`keyStore` and `autoGenerate` are mutually exclusive. The task collects every `.py` under
`localLibraryPath`, runs `adb push` for each to `/data/local/tmp/<fileName>/python/…`, then
`adb shell am broadcast -a org.thisisthepy.python.RELOAD_PYTHON`.

**Status: partial** — `plugin/hotreload/`; `ptest/PythonPluginHotReloadTest.kt`,
`ptest/hotreload/WatchAndPushTest.kt` test the validation and the command list; the task action
itself is untested. `serverHost` and `cert` are validated but not used for transport (adb only);
the source root ignores `commonMain.srcDirs`; Android only (a desktop copy helper,
`executeLocalCopy`, exists and is tested but no task calls it).
→ *A hot-reload server reachable at `serverHost`, and a "run" entry point for it: **planned**.*

### 1.15 Code push — `codePushPython`
`packaging { codePush { serverHost; cert; uploadConfig { forceUpload; login { id; password } } } }`
is validated the same way when any build type sets `enableCodePush = true`. The task prints that no
upload client exists.

**Status: partial** (validation) — `plugin/hotreload/HotReloadConfig.kt`, `CodePushPendingTask.kt`;
`ptest/PythonPluginCodePushTest.kt`. → *Upload: **planned**.*

### 1.16 `buildFeatures { metaclass, compose }`
Declared (`BuildFeaturesExtension`); nothing reads them. **Status: planned.**

### 1.17 `projectFlavors { }`
Present in the example build file; absent from the DSL. **Status: planned.**

### 1.18 TypedPython check — `typedpythonCheck`
Source: issue [`toolchain#23`](https://github.com/thisisthepy/toolchain/issues/23) (TypedPython step
1b; checking is on by default, maintainer decision 2026-10-02).

One `typedpythonCheck` task per project statically checks the project's own Python before it is
bundled. Every `buildPython<Variant><BuildType>` and the single host `buildPython` depend on it.

- **Sources.** Every `.py` file under the directory `resolvePackageDir` resolves to (§1.9) — the
  directory that gets bundled — excluding hidden directories (`.venv`, …), `__pycache__` and
  `build`. `metaDirs` and `libDirs` are bundled but **not checked**: `libDirs` holds third-party
  site-packages and `metaDirs` generated metadata, neither of which the user wrote or could fix (asserted
  by `typedpythonCheckedDirs`). `.py` and `.pyi` files there are `@InputFiles` with `@PathSensitive(RELATIVE)`: a changed
  `.py` re-runs the check, a change to any other file leaves it `UP-TO-DATE`. No package directory,
  or no `.py` file in it: the check is skipped with a log line and the gate is not installed.
- **Gate.** The `typedpython` wheel and the `pyrefly` it pins, both at pinned versions (`@Input`,
  defaults `0.1.0` and `1.3.2`), installed into `build/typedpython/venv` — never the project's `.venv`.
  The venv is created through `pypackpack`'s UV backend (`createVirtualEnvironment`, `--no-project
  --clear`, Python `>=3.13`; only when the venv does not exist). The install is
  `uv pip install --python <venv> --no-index --find-links <dir> typedpython==<v> pyrefly==<v>`
  (skipped when the same request is already installed): **both packages come from `<dir>` and
  nothing is ever fetched from an index** — `typedpython` is unclaimed on PyPI, so a bare-name install
  would run whatever someone publishes under that name on every consumer's build. `<dir>` is the
  project property `typedpython.wheelDir` and must hold the `typedpython` wheel and the `pyrefly` wheel
  for the platform. An install failure fails the task and names that requirement. The install calls
  `uv` directly: `pypackpack`'s backend has no operation that installs named requirements into an
  existing venv from a local wheel directory (`installDependenciesToTarget` reads `-r pyproject.toml`
  into `--target`; `addDependencies` edits `pyproject.toml`) — a `pypackpack` change, not made here.
- **No wheel directory.** The check is **skipped with a loud warning** (what was skipped, that the
  gate is not on PyPI yet, and `-Ptypedpython.wheelDir=<dir>` to turn it on); nothing is created or
  installed. With a wheel directory a failed check fails the build.
- **Run.** `<venv>/bin/typedpython check --mode <mode> [--search-path <dir>]... <file>...`, with
  explicit files (the 0.1.0 gate crashes on a directory argument). `mode` is an `@Input`, default
  `checked`; any value other than `checked`/`compiled` fails the task.
- **Import root.** The package's own import root — `<packageDir>/src/main` when it exists (the
  `pypackpack` layout), else the package directory — is passed as the first `--search-path`, so the
  package's modules can import one another (the 0.1.0 gate otherwise infers its temporary config
  directory as the import root).
- **Stubs.** The plugin creates a resolvable configuration `typedpythonStubs`; its files are
  `@InputFiles` and each is passed as `--search-path`, in order. With nothing in it, the check runs
  without stubs and logs that once.
- **Result.** Exit 0 passes and prints any `WARNING` lines. Exit 1 with diagnostics fails the build
  with them. Exit 2 — or exit 1 with no diagnostic line, which is how an uncaught exception in the
  gate exits — fails the build with the tool's own output. A passing run writes
  `build/typedpython/report.txt` (the task's output, so an unchanged rerun is `UP-TO-DATE`).

**Status: implemented** — `plugin/typedpython/TypedpythonCheckTask.kt`, wiring in `plugin/PythonPlugin.kt`;
`ptest/typedpython/TypedpythonCheckTaskTest.kt` (TestKit: an `Any` leak fails `buildPython` and the
fix passes; per-variant `buildPython*` depend on the check; only a `.py` change re-runs it; a
`typedpythonStubs` directory resolves an import that fails without it; the no-stubs line appears
once; the package's modules import one another; a `pyrefly` pin absent from the wheel directory fails
instead of being fetched; `metaDirs`/`libDirs` are not checked),
`ptest/typedpython/TypedpythonNoWheelTest.kt` (no wheel directory: skipped with the warning),
`ptest/typedpython/TypedpythonDecisionsTest.kt` (source selection, import root, `--no-index`
commands, skip warning, mode, exit-code interpretation). The TestKit tests in
`TypedpythonCheckTaskTest` need `-Ptypedpython.wheelDir=<dir>` (holding the `typedpython` and
`pyrefly` wheels) and `uv`; without it they are **skipped** with that reason, as on CI.
Not covered by a test here: exit 2 end to end (only `interpretTypedpythonResult`), and the install
in a *consumer's* classpath — `withPluginClasspath()` bypasses the Kotlin DSL's `kotlin-stdlib` pin;
that was verified by building `:usage-example:buildPython`.
→ *Until `typedpython` is on PyPI, a build without `-Ptypedpython.wheelDir` skips the check with a warning.*
→ *Platform overlays (`src/<family>`) are checked as files but only `src/main` is an import root:
**partial**.*

## 2. `tcl` — toolchain-lite

### 2.1 `tcl install <package>`
Finds the nearest `pyproject.toml` at or above the working directory. If there is none, runs
`uv init --bare` there first (through `pypackpack`'s `BackendInterface.initProject`), then
`addDependencies` for the package. Exit code 0 on success and the `uv` output on stdout; 1 with a
message on stderr for a missing argument, an unknown command or a failed install. No arguments
prints `Usage: tcl install <package>`.

**Status: implemented** — `tcl/Cli.kt`, `tcl/Installer.kt`; `tcl/CliArgsTest.kt`,
`tcl/InstallerTest.kt` (needs `uv` and network). Run with `./gradlew :tcl:run --args="install <pkg>"`.

---

## Outside intent — needs a decision

These exist in the code but are not asked for by the example build file or the issues.

1. **`python { localLibraryPath = "…" }`** — not in the example build file, which uses
   `sourceSets { commonMain { srcDirs(…) } }`. It overrides `srcDirs` and is the only source root
   hot reload reads. `usage-example` depends on it.
2. **`-Ppython.buildType=<name>`** — a project property choosing the active build type when no
   platform variant is declared. The example build file says nothing about selecting a build type.
3. **Hot reload over `adb push` + broadcast.** The example describes an HTTPS `serverHost` with a
   certificate; the implemented transport is Android-only `adb`.
4. **Unreferenced code**: `PythonMultiplatformPlugin.kt` (a second `Plugin` that is not registered
   in `gradlePlugin { }`), `dependency/reslover.kt`, `dependency/lang/kotlin/decompileKotlinMeta.kt`
   (a `main()` generating Python from Kotlin metadata — related to `buildFeatures.metaclass` but not
   wired), `bundle/PythonLocalLoader.kt`, `dependency/DependencyType.kt`, and
   `BinariesExtension` / `FrozenPackConfig` / `BuildTypeEnum` in `dsl/DSLPlatforms.kt`. Nothing calls
   any of them.
5. **`pyproject.toml`** declares a flit-built Python package named `toolchain` (Python 3.9–3.13)
   that does not exist in the repository — the root `toolchain/` directory is the Gradle module.
   Nothing builds or tests it.
