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
)

fun resolvePipSettings(pip: PipExtension): PipSettings =
    try {
        PipSettings(arguments = resolvePipArguments(pip), rejection = null)
    } catch (error: IllegalArgumentException) {
        PipSettings(arguments = null, rejection = error.message)
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
 * - `jit { … }`: throws. It names a repository that builds packages from recipes on demand, and
 *   pypackpack has no recipe build to hand that to.
 */
fun resolvePipArguments(pip: PipExtension): Map<String, String> {
    val repositories = pip.repositories
    repositories.jit?.let { jit ->
        throw IllegalArgumentException(
            "pip { repositories { jit { … } } } is not supported yet: building packages from recipes " +
                "(url ${jit.url ?: "<none>"}, localRecipes ${jit.localRecipes.paths}) needs a recipe " +
                "build in pypackpack, which does not exist. Use central or local instead.",
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
