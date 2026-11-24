package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import org.thisisthepy.python.multiplatform.toolchain.dsl.PipExtension
import java.io.File
import java.net.URI

/**
 * The `uv add` options for `defaultConfig { pip { … } }`, or why they cannot be produced.
 *
 * Exactly one of the two is set. A rejection is carried to `installPythonDependencies` instead of
 * thrown at configuration (AGENTS.md §14: fail as narrowly as possible), so tasks that install
 * nothing still run.
 */
data class PipSettings(
    val arguments: Map<String, String>?,
    val rejection: String?,
    /**
     * `jit { localRecipes { … } }`'s directories, each resolved against the project directory, or `null` when no
     * `jit` repository is declared. Non-null (possibly empty) means a target dependency without a wheel is built in
     * pypackpack's cross build environment, with its recipe from these directories when one exists (#152).
     */
    val jitRecipeDirs: List<File>? = null,
)

fun resolvePipSettings(
    pip: PipExtension,
    projectDir: File,
): PipSettings {
    val recipeDirs = pip.repositories.jit?.localRecipes?.paths?.map { path ->
        File(path).let { if (it.isAbsolute) it else File(projectDir, path) }
    }
    return try {
        PipSettings(arguments = resolvePipArguments(pip), rejection = null, jitRecipeDirs = recipeDirs)
    } catch (error: IllegalArgumentException) {
        PipSettings(arguments = null, rejection = error.message, jitRecipeDirs = recipeDirs)
    }
}

/**
 * Maps the pip DSL onto `uv add` options, keyed the way pypackpack's uv backend expects (`--<key>
 * <value>`, an empty value meaning a bare flag):
 *
 * - `central { setUrl(a, b, …) }`: `a` replaces PyPI as `--default-index`; the remaining URLs, minus
 *   repeats of `a`, become `--index`, space-separated, which uv splits into separate indexes.
 * - `local { url = … }`: `--find-links`, a directory of wheels. A `file:` URI (the example writes
 *   `Paths.get(…).toUri().toString()`) becomes a plain path.
 * - `autoUpdate = true`: `--upgrade`.
 * - `jit { localRecipes { … } }`: no uv option. It makes each target install build the dependencies that have no
 *   wheel for the target, in pypackpack's cross build environment, with the recipes in those directories
 *   ([PipSettings.jitRecipeDirs], #152).
 * - `jit { url = … }`: throws. It names a recipe server, and none exists (Decision 16, 2026-11-25).
 */
fun resolvePipArguments(pip: PipExtension): Map<String, String> {
    val repositories = pip.repositories
    repositories.jit?.url?.let { url ->
        throw IllegalArgumentException(
            "pip { repositories { jit { url = \"$url\" } } } names a recipe server, and there is none to build " +
                "from. Remove url and keep the recipes on disk with jit { localRecipes { add(\"<directory>\") } }.",
        )
    }

    return buildMap {
        repositories.central?.urls?.takeIf { it.isNotEmpty() }?.let { urls ->
            val default = urls.first()
            put("default-index", default)
            val extra = urls.drop(1).filter { it != default }.distinct()
            if (extra.isNotEmpty()) put("index", extra.joinToString(" "))
        }
        repositories.local?.url?.let { url -> put("find-links", localPath(url)) }
        if (pip.autoUpdate) put("upgrade", "")
    }
}

private fun localPath(url: String): String =
    if (url.startsWith("file:")) File(URI(url)).absolutePath else url
