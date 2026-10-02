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
(`PythonExtension`) and registers the tasks of §1.10–§1.15, all in group `python`.
Consumers resolve it from `mavenLocal()` after `./gradlew :toolchain:publishToMavenLocal`; the plugin
itself depends on `org.thisisthepy.python.multiplatform:packpack:0.1.0` from `mavenLocal()`.

**Status: implemented** — `plugin/PythonPlugin.kt`; `ptest/PythonPluginApplyTest.kt` applies the
plugin id to a ProjectBuilder project and checks the extension, every task name and type
(`installPythonDependencies`, `buildPython`, `packagePython`, `stagePythonBundle` and its
`Android`/`Ios`/`Desktop` tasks; `hotReloadPython` and `codePushPython` after evaluation), the
`python` group, and the `packagePython → buildPython → installPythonDependencies` chain. Resolving
the plugin from `mavenLocal()` is still exercised only by building `usage-example`.

### 1.2 `compileSdk` — the Python version
`compileSdk` accepts `X.Y`, `X.Y.Z`, `X.Y.Z-alpha[N]` or `X.Y.Z-rc[N]` and classifies it into
`PythonReleaseChannel { ALPHA, RC, NORMAL }`. `toReleaseString()` strips the channel to the `X.Y.Z`
form `pypackpack` accepts. A blank value is a silent skip; a malformed one fails configuration.

**Status: implemented** — `plugin/dsl/PythonVersion.kt`; `ptest/dsl/PythonVersionTest.kt`.

`compileSdk` takes either form the example build file writes, both with `=` (Gradle's Kotlin
assignment overloading on `PythonSdk`): a string, `compileSdk = "3.14.7"`, or a named constant,
`compileSdk = PY3_14_7`. Constants exist only for versions python-multiplatform provides a runtime
for — its pinned `pythonVersion` (3.14.7) and the older 3.13.0 archive — so the example's
`PY3_11_9_ALPHA` does not compile (INTENT §4.1). A string resolves to a provided version (`X.Y`
takes the newest `X.Y.*`). A string naming a version that is not provided would be built
automatically (INTENT §4.1), which is not available yet: the reason fails `buildPython…` (only
when there is a package to bundle), not the configuration.

**Status: implemented** — `plugin/dsl/PythonSdk.kt`; `ptest/dsl/PythonSdkTest.kt`,
`ptest/bundle/BuildPythonSdkRejectionTest.kt`; `usage-example` uses `compileSdk = PY3_14_7`.

The resolved version is handed to `buildPython` as `pythonVersion`, which only logs it: it does
**not** choose which interpreter is bundled. → *Interpreter selection by `compileSdk`: **planned**
(#18).* → *Automatic build of a version python-multiplatform does not provide: **planned**, past
2026-11.*

### 1.3 `defaultConfig { versionCode, versionName, pip { … } }`
`pip { autoUpdate; repositories { central { setUrl(…) }; local { url = … }; jit { url; localRecipes { add(…) } } } }`,
spelled as the example build file spells it (INTENT §4.3), becomes options of the `uv add` that
`installPythonDependencies` runs (§1.10):

| DSL | `uv add` option |
|---|---|
| `central { setUrl(a, b, …) }` | `--default-index a`; the other URLs, minus repeats of `a`, as `--index` |
| `local { url = … }` | `--find-links <dir>`; a `file:` URI becomes a path |
| `autoUpdate = true` | `--upgrade` |
| `jit { … }` | **rejected**: pypackpack has no recipe build. The rejection fails `installPythonDependencies` only, and only when it has something to install. |

**Status: implemented** — `resolvePipArguments` / `resolvePipSettings` in
`plugin/dependency/lang/python/PipRepositories.kt`; `ptest/dependency/lang/python/PipRepositoriesTest.kt`
(including a real `uv add` sent to the declared index).
→ *`jit` / `localRecipes`: **planned**, needs a recipe build in pypackpack.*
→ *`versionCode`, `versionName`: **planned** — nothing reads them (#11).*

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

**Status: implemented** — `plugin/bundle/AssemblePythonPackageTask.kt`;
`ptest/bundle/AssemblePythonPackageTaskTest.kt` runs the real task's action: the archive name and
location for the aggregate and a variant task, the zip entries, and the failure when the bundle
directory is missing.
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
  is in the desktop jar. **implemented** — `ptest/PythonPluginAttachmentTest.kt` (Kotlin
  Multiplatform with `jvm("desktop")`: `stagePythonBundleDesktop` is a dependency of
  `desktopProcessResources` and the staged file is among its sources). Not tested: the fallback for
  a `<target>ProcessResources` that is not a `Copy` task (dependency plus warning), and the built jar
  itself.
- Android: the staged root is added to `android.sourceSets.main.assets` (reflectively), so the
  payload is in the APK's `assets/`. **partial** — `ptest/PythonPluginAttachmentTest.kt` applies the
  real `com.android.application` (AGP 8.5.2, test classpath only) and checks the staged root is a
  `main` asset source directory. Not tested: the `preBuild` / `merge*Assets` → `stagePythonBundleAndroid`
  dependency (AGP creates those tasks only when the project is evaluated against an Android SDK,
  which `:toolchain:test` does not require), and the built APK.
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
`projectFlavors { create("free"); create("paid") }`, AGP-style (decided 2026-10-03; the example
build file declares the block empty). Each flavor is crossed into the per-variant graph (§1.8)
between platform and build type: `buildPythonAndroidArm64FreeDebug`,
`build/pythonBundle/androidArm64-free-debug/`. A flavor name is lower-camel, unique and not a build
type's name; anything else fails configuration. Staging (§1.13) takes the variants of one flavor:
`-Ppython.flavor=<name>`, else the first declared; an undeclared name fails loudly. Flavors with
no platform variant could change nothing, so the host `buildPython` fails with that reason. A
flavor's own dependencies go in a `<flavor>Main` source set; like every source set's, they are
installed into the one package directory today (§1.10).

**Status: implemented** — `plugin/dsl/DSLFlavors.kt`, `resolveVariants`, `resolveActiveFlavor`,
`flavorsWithoutVariantsRejection` in `plugin/PythonPlugin.kt`; `ptest/PythonPluginFlavorsTest.kt`
(including task registration on an applied plugin).
→ *Per-flavor properties, and installing a `<flavor>Main` dependency only into that flavor's
variants: **planned** (with #16).*

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
