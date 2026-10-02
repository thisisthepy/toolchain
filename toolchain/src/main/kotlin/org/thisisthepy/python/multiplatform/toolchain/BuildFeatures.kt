package org.thisisthepy.python.multiplatform.toolchain

import java.io.File
import java.net.URI

/**
 * `python { buildFeatures { metaclass; compose } }` and release's `excludeMetaclass`
 * (docs/SPEC.md §1.16, §1.7), as Project-free decisions `PythonPlugin.apply` wires
 * (AGENTS.md §14). Tests: `BuildFeaturesTest` (decisions), `PythonPluginBuildFeaturesTest` (wiring).
 */

/** Gradle property naming where `pythonx-compose` comes from: a pip requirement or a wheel directory. */
const val COMPOSE_PYTHON_PROPERTY = "python.compose.pythonxCompose"

/** Gradle property naming the `python-multiplatform-compose` Maven coordinate, `group:artifact:version`. */
const val COMPOSE_KOTLIN_PROPERTY = "python.compose.kotlinModule"

/** The pip distribution `compose = true` installs. */
const val PYTHONX_COMPOSE = "pythonx-compose"

/**
 * The `metaDirs` one bundle task forwards to `pypackpack`'s `BundleRequest.metaDirs`.
 *
 * [metaDirs] (`resolveMetaDirs`) are kept only when `buildFeatures { metaclass }` is on (the default,
 * so declared `metaDirs` keep reaching the bundle as they did before the flag was read) **and** the
 * task's build type does not set `excludeMetaclass` (only `release` can; `debug`'s setter throws).
 * `excludeMetaclass` is per build type, so it is applied per bundle task, not project-wide.
 */
fun resolveBundledMetaDirs(
    metaDirs: List<File>,
    metaclassFeature: Boolean,
    excludeMetaclass: Boolean,
): List<File> = if (metaclassFeature && !excludeMetaclass) metaDirs else emptyList()

/**
 * What `compose = true` adds to `installPythonDependencies`: [requirements] go into its dependency
 * list, [findLinks] (a wheel directory) into its `uv add --find-links`. A [rejection] is carried to
 * the task and thrown from its action (AGENTS.md §14), so a missing property fails the one task that
 * needs it, not configuration.
 */
data class ComposePythonInstall(
    val requirements: List<String>,
    val findLinks: String?,
    val rejection: String?,
)

/**
 * Decides the Python half of `compose = true` from the [COMPOSE_PYTHON_PROPERTY] value
 * [pythonxCompose]. `pythonx-compose` is not on a package index yet, so there is no default.
 *
 * - An existing directory (absolute, relative to [projectDir], or a `file:` URI): install
 *   `pythonx-compose` and search that directory (`--find-links`).
 * - Otherwise a PEP 508 requirement whose name is `pythonx-compose` (any case, `-`/`_`/`.`
 *   equivalent): installed as written, e.g. `pythonx-compose==0.1.0`.
 * - Anything else, or no value: a rejection naming [COMPOSE_PYTHON_PROPERTY].
 */
fun resolveComposePythonInstall(
    compose: Boolean,
    pythonxCompose: String?,
    projectDir: File,
): ComposePythonInstall {
    if (!compose) return ComposePythonInstall(emptyList(), null, null)

    val value = pythonxCompose?.trim().orEmpty()
    if (value.isEmpty()) {
        return rejected(
            "python { buildFeatures { compose = true } } needs the Gradle property " +
                "'$COMPOSE_PYTHON_PROPERTY': the $PYTHONX_COMPOSE pip requirement (e.g. " +
                "$PYTHONX_COMPOSE==0.1.0) or a local directory of its wheels, which " +
                "installPythonDependencies installs. $PYTHONX_COMPOSE is not published to a package " +
                "index yet, so there is no default. Set it in gradle.properties or pass " +
                "-P$COMPOSE_PYTHON_PROPERTY=<requirement or directory>.",
        )
    }

    asDirectory(value, projectDir)?.let { dir ->
        return ComposePythonInstall(listOf(PYTHONX_COMPOSE), dir.absolutePath, null)
    }
    if (requirementName(value) == PYTHONX_COMPOSE) {
        return ComposePythonInstall(listOf(value), null, null)
    }
    return rejected(
        "'$COMPOSE_PYTHON_PROPERTY' is '$value', which is neither an existing directory of " +
            "$PYTHONX_COMPOSE wheels nor a pip requirement for $PYTHONX_COMPOSE " +
            "(e.g. $PYTHONX_COMPOSE==0.1.0).",
    )
}

private fun rejected(message: String) = ComposePythonInstall(emptyList(), null, message)

private fun asDirectory(value: String, projectDir: File): File? {
    val file = when {
        value.startsWith("file:") -> runCatching { File(URI(value)) }.getOrNull() ?: return null
        File(value).isAbsolute -> File(value)
        else -> File(projectDir, value)
    }
    return file.takeIf { it.isDirectory }
}

/** The PEP 503-normalized project name a PEP 508 requirement starts with. */
private fun requirementName(requirement: String): String =
    Regex("^[A-Za-z0-9][A-Za-z0-9._-]*").find(requirement)?.value.orEmpty()
        .lowercase().replace(Regex("[-_.]+"), "-")

/**
 * Adds the compose wheel directory to the `uv add` options `resolvePipSettings` produced.
 *
 * `pip { repositories { local { url } } }` already maps to `find-links`, and those options are one
 * value per key, so a second location is appended with a comma: uv splits `--find-links` on commas
 * (checked against uv 0.12.3 -- `--find-links "a,b"` reads both directories, while a space is taken
 * as part of one path). The local repository stays first; a repeat of it is not appended.
 */
fun mergeComposeFindLinks(
    pipArguments: Map<String, String>,
    findLinks: String?,
): Map<String, String> {
    if (findLinks == null) return pipArguments
    val existing = pipArguments["find-links"]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    if (findLinks in existing) return pipArguments
    return pipArguments + ("find-links" to (existing + findLinks).joinToString(","))
}

/** The Kotlin half of `compose = true`. */
sealed class ComposeKotlinDependency {
    /** `compose = false`: nothing to add. */
    object None : ComposeKotlinDependency()

    /** Add [coordinate] to Kotlin Multiplatform `commonMain`'s `implementation`. */
    data class Add(val coordinate: String) : ComposeKotlinDependency()

    /** No Kotlin Multiplatform plugin, so there is no `commonMain` to add to; [reason] is logged. */
    data class Skipped(val reason: String) : ComposeKotlinDependency()
}

/**
 * Decides the Kotlin half of `compose = true`: the `python-multiplatform-compose` host entry point,
 * at the [COMPOSE_KOTLIN_PROPERTY] coordinate [kotlinModule], added to `commonMain`'s
 * `implementation`.
 *
 * **A missing or malformed coordinate throws, and `PythonPlugin.apply` calls this in
 * `afterEvaluate`, so it fails configuration.** That is the narrowest place available. A dependency
 * is declared on a configuration, not run by a task, so there is no task action to carry the
 * rejection to; a lazily-added provider that throws would fail wherever the dependency set is first
 * iterated -- IDE sync, the Kotlin plugin's own source-set analysis, any compilation of any target --
 * which is no narrower, only less predictable. And no Kotlin compilation is valid without it: every
 * target compiles `commonMain`, which is where the dependency is promised.
 *
 * **Without Kotlin Multiplatform ([kotlinMultiplatformApplied] false) the Kotlin half is skipped,
 * not failed, and no coordinate is demanded.** `python-multiplatform-compose` is a Kotlin
 * Multiplatform module added to `commonMain`; a project with no `commonMain` has no Kotlin code that
 * could call it, so there is nothing for the dependency to serve and nothing broken by its absence.
 * The Python half (`pythonx-compose`) still applies, so `compose = true` is read, not ignored, and
 * the skip is logged as a warning naming the plugin that would enable the rest.
 */
fun resolveComposeKotlinDependency(
    compose: Boolean,
    kotlinMultiplatformApplied: Boolean,
    kotlinModule: String?,
): ComposeKotlinDependency {
    if (!compose) return ComposeKotlinDependency.None
    if (!kotlinMultiplatformApplied) {
        return ComposeKotlinDependency.Skipped(
            "python { buildFeatures { compose = true } }: org.jetbrains.kotlin.multiplatform is not " +
                "applied, so python-multiplatform-compose was not added to any Kotlin source set; only " +
                "$PYTHONX_COMPOSE is installed.",
        )
    }

    val value = kotlinModule?.trim().orEmpty()
    require(value.isNotEmpty()) {
        "python { buildFeatures { compose = true } } needs the Gradle property " +
            "'$COMPOSE_KOTLIN_PROPERTY': the Maven coordinate (group:artifact:version) of " +
            "python-multiplatform-compose, the Kotlin host entry point added to Kotlin Multiplatform " +
            "commonMain's implementation dependencies. It is not published to a remote repository " +
            "yet, so there is no default. Set it in gradle.properties or pass " +
            "-P$COMPOSE_KOTLIN_PROPERTY=<group:artifact:version>."
    }
    val parts = value.split(':')
    require(parts.size == 3 && parts.all { it.isNotBlank() }) {
        "'$COMPOSE_KOTLIN_PROPERTY' is '$value'; it must be a Maven coordinate group:artifact:version " +
            "of python-multiplatform-compose."
    }
    return ComposeKotlinDependency.Add(value)
}
