package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingPlatform
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetConfig
import java.io.File

/*
 * Per-target dependency installation (docs/SPEC.md §1.10, issue #16): which source sets' dependencies
 * a variant gets, where they are installed, and with which `uv pip install` options. Every decision
 * here is a top-level function with no `Project` (AGENTS.md §14) -- see `TargetDependenciesTest`.
 * `PythonPlugin.apply` registers the tasks; `InstallTargetDependenciesTask` runs them.
 */

/** `build/pythonDeps/<dependency set>/` holds one dependency set's installed wheels. */
const val TARGET_DEPENDENCIES_DIRECTORY = "pythonDeps"

/** The dependency set of the host chain (no platform variant declared): `build/pythonDeps/host/`. */
const val HOST_DEPENDENCY_SET = "host"

/**
 * The source sets whose dependencies a variant of [platform] and [flavorName] installs, in the order
 * their entries are listed: `commonMain`, then `<family>Main` (`androidMain`, `iosMain`,
 * `desktopMain`), then `<flavor>Main` when a flavor is set.
 *
 * The family names are `(플러그인예시)build.gradle.kts`'s own source sets. They are spelled from
 * [PythonStagingPlatform.directoryName], because that enum is already this plugin's one mapping from a
 * `pypackpack` triple to android/ios/desktop: macOS, Linux and Windows are three `pypackpack` families
 * and one `desktopMain`, as they are one staging destination. There is no per-triple source set
 * (`androidArm64Main`): the example file declares none, and a wheel that differs per ABI is already
 * chosen per triple by `uv` itself.
 */
fun dependencySourceSetNames(
    platform: PythonStagingPlatform,
    flavorName: String?,
): List<String> =
    listOfNotNull(
        "commonMain",
        platform.directoryName + "Main",
        flavorName?.let { it + "Main" },
    )

/**
 * The requirements one variant installs: the `implementation` and `integration` entries of exactly
 * the source sets [dependencySourceSetNames] names, in that order, duplicates removed (the first
 * occurrence wins its position). A source set that is not declared contributes nothing.
 *
 * `integration` installs like `implementation`, as in [collectInstallDependencies]; the `KLIBDEPENS`
 * check is still planned (SPEC §1.10).
 */
fun collectTargetDependencies(
    sourceSets: List<SourceSetConfig>,
    platform: PythonStagingPlatform,
    flavorName: String?,
): List<String> {
    val byName = sourceSets.associateBy { it.name }
    return dependencySourceSetNames(platform, flavorName)
        .mapNotNull { byName[it] }
        .flatMap { it.dependencies.implementations + it.dependencies.integrations }
        .distinct()
}

/**
 * Why some declared dependencies can reach no variant, or `null` when every one can.
 *
 * Before per-target installation every source set was flattened into one list, so any name worked.
 * Now a dependency in, say, `androidArm64Main` or `fooMain` (or `freeMain` without a `free` flavor)
 * would be `uv add`-ed into the venv and then reach no bundle at all -- a declaration that compiles and
 * does nothing (AGENTS.md §14). Only source sets that declare dependencies are checked: an empty one
 * changes nothing either way. Thrown from the install tasks' actions, not configuration, like every
 * other rejection carried to a task.
 */
fun unreachableSourceSetRejection(
    sourceSets: List<SourceSetConfig>,
    flavorNames: List<String>,
): String? {
    val reachable =
        setOf("commonMain") +
            PythonStagingPlatform.values().map { it.directoryName + "Main" } +
            flavorNames.map { it + "Main" }
    val unreachable =
        sourceSets.filter { sourceSet ->
            sourceSet.name !in reachable &&
                (sourceSet.dependencies.implementations.isNotEmpty() || sourceSet.dependencies.integrations.isNotEmpty())
        }
    if (unreachable.isEmpty()) return null
    return "python { sourceSets { } } declares dependencies in " +
        unreachable.joinToString(", ") { "'${it.name}'" } + ", which no variant installs. Dependencies " +
        "go in ${reachable.sorted().joinToString(", ")}" +
        (if (flavorNames.isEmpty()) " (a <flavor>Main needs that flavor in projectFlavors { })" else "") + "."
}

/**
 * The `uv pip install` options for a per-target install, keyed the way `pypackpack`'s
 * `UVBackend.installDependenciesToTarget` appends them (`--<key> <value>`):
 *
 * - [pipArguments] (`defaultConfig { pip { } }`, plus compose's `find-links`): the same index and
 *   find-links options `uv add` gets, so a target install searches the same repositories.
 * - `python-version` = [compileSdk]'s `major.minor`: wheel tags carry the CPython ABI (`cp314`), and
 *   uv must pick the runtime's, not whatever Python the build machine has. Omitted when `compileSdk`
 *   is undeclared; uv then uses the interpreter it finds, which SPEC §1.10 records as a limit.
 * - `only-binary` = `:all:`: without it uv builds an sdist with the *host* compiler and only then
 *   rejects the result for a cross target (`pypackpack`'s `UVBackendRealInstallTest` records this).
 *   The host chain uses it as well, so every bundle is made of published wheels only and a package
 *   with no wheel fails the same way on every target.
 *
 * The two fixed keys are put last so they win over a same-named pip argument.
 */
fun targetInstallArguments(
    compileSdk: PythonVersion?,
    pipArguments: Map<String, String>,
): Map<String, String> =
    buildMap {
        putAll(pipArguments)
        compileSdk?.let { put("python-version", "${it.major}.${it.minor}") }
        put("only-binary", ":all:")
    }

/** `<buildDir>/pythonDeps/<dependencySet>`, the `--target` of one install and a `libDirs` entry of its bundles. */
fun targetDependenciesDir(
    buildDir: File,
    dependencySet: String,
): File = File(File(buildDir, TARGET_DEPENDENCIES_DIRECTORY), dependencySet)
