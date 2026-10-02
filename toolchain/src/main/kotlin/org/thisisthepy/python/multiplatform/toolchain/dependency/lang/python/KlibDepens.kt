package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import java.io.File

/**
 * The marker file an `integration(...)` package must carry: `KLIBDEPENS`, inside the installed
 * distribution's `<name>-<version>.dist-info/` directory.
 *
 * ASSUMPTION: nothing in the ecosystem defines this file (its format, or even its location). The only
 * source is the DSL comment in `(플러그인예시)build.gradle.kts` -- "requires KLIBDEPENS file in whl
 * dist directory". "dist directory" is read as the wheel's `*.dist-info/` directory. Only the file's
 * presence is checked, never its content. See docs/SPEC.md §1.10.
 */
const val KLIBDEPENS_FILE_NAME = "KLIBDEPENS"

/** PEP 503 name normalization; wheel's `_` escaping (PEP 427) maps to the same form. */
fun normalizeDistributionName(name: String): String =
    name.trim().lowercase().replace(Regex("[-_.]+"), "-")

/**
 * The distribution name of a dependency spec such as `pycomposeui`, `Foo_Bar>=1.0`,
 * `pkg[extra]==2; python_version>'3'` or `pkg @ https://...`. `null` when the spec has no leading name
 * (a path or URL on its own).
 */
fun distributionNameOf(spec: String): String? =
    Regex("^\\s*([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)").find(spec)?.groupValues?.get(1)

/**
 * The `site-packages` directory of a virtual environment at [venvDir] (what `uv add` creates as
 * `<package dir>/.venv`): `Lib/site-packages` on Windows, `lib/python3.X/site-packages` elsewhere
 * (`lib64` is tried too). `null` when the directory does not exist.
 */
fun findSitePackages(venvDir: File): File? {
    val windows = File(venvDir, "Lib/site-packages")
    if (windows.isDirectory) return windows
    for (lib in listOf("lib", "lib64")) {
        val pythons = File(venvDir, lib).listFiles { f -> f.isDirectory && f.name.startsWith("python") }
            ?: continue
        pythons.sortedBy { it.name }
            .map { File(it, "site-packages") }
            .firstOrNull { it.isDirectory }
            ?.let { return it }
    }
    return null
}

/**
 * Of the declared [integrations] (dependency specs), the ones whose installed distribution in
 * [sitePackages] has no `KLIBDEPENS` file in its `*.dist-info/` directory, in declaration order and
 * as the user wrote them. A package with no dist-info at all (not installed there) is reported too:
 * it cannot be shown to carry the marker. A spec with no recognisable name is reported.
 */
fun findIntegrationsWithoutKlibDepens(sitePackages: File, integrations: List<String>): List<String> {
    val distInfos = sitePackages.listFiles { f -> f.isDirectory && f.name.endsWith(".dist-info") }.orEmpty()
    // `<name>-<version>.dist-info`: the name never contains `-` (wheels escape it as `_`).
    val byName = distInfos.groupBy { normalizeDistributionName(it.name.removeSuffix(".dist-info").substringBefore('-')) }
    return integrations.filter { spec ->
        val name = distributionNameOf(spec) ?: return@filter true
        val dirs = byName[normalizeDistributionName(name)].orEmpty()
        dirs.none { File(it, KLIBDEPENS_FILE_NAME).isFile }
    }
}

/** The warning for one package; not a failure. */
fun klibDepensWarning(spec: String): String =
    "integration(\"$spec\"): no $KLIBDEPENS_FILE_NAME file in the installed wheel's dist-info, so it is " +
        "not a Kotlin-dependent Python package. Declare it with implementation(\"$spec\") instead."
