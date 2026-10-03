package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.ResolvedPythonSdk

/** The `gradle.properties` / `-P` name that overrides `packaging { embedLevel }`. */
const val EMBED_LEVEL_PROPERTY = "python.embedLevel"

/** Families whose apps can run an interpreter that lives outside the app: the desktop ones. */
private val EXTERNAL_INTERPRETER_FAMILIES = setOf("macos", "linux", "windows")

/**
 * Decides the `embedLevel` a packaging task carries. Pure, so a plain test drives it.
 *
 * - 0: no interpreter. 1: the app uses an external interpreter. 2: python-multiplatform embeds the
 *   interpreter (libpython linked into its binaries, its own stdlib). toolchain ships no interpreter
 *   at any level: the bundle is `python/` only (docs/SPEC.md §1.12, issue #42).
 * - [override] is the `python.embedLevel` property; blank or null means "not set", and it wins over
 *   [declared] when set.
 * - A platform that cannot honour the level raises it to 2 and says so in the returned warning.
 *   Android and iOS are sandboxed with no system Python, so neither 0 nor 1 can work there; the
 *   desktop families honour all three. An unknown family is treated like Android and iOS (the safe
 *   direction: more interpreter, not less).
 *
 * @throws IllegalArgumentException when the declared or overriding value is not an integer in 0..2.
 */
fun resolveEmbedLevel(declared: Int, override: String?, platformFamily: String): Pair<Int, String?> {
    val requested = if (override.isNullOrBlank()) {
        require(declared in 0..2) { "packaging { embedLevel = $declared } must be 0, 1 or 2." }
        declared
    } else {
        val parsed = override.trim().toIntOrNull()
        require(parsed != null && parsed in 0..2) {
            "$EMBED_LEVEL_PROPERTY = '$override' must be 0, 1 or 2 (0 no interpreter, 1 external, 2 embedded)."
        }
        parsed
    }
    if (requested == 2 || platformFamily in EXTERNAL_INTERPRETER_FAMILIES) return requested to null
    return 2 to "embedLevel = $requested cannot be honoured on $platformFamily (sandboxed, no external " +
        "interpreter): raised to 2, python-multiplatform embeds the interpreter."
}

/**
 * The interpreter version a variant at [embedLevel] expects: the `X.Y.Z` release of the resolved
 * `compileSdk` ([sdk]) at levels 1 and 2, `null` at level 0, when no compileSdk is declared, or when it
 * is rejected. At level 2 it is the version python-multiplatform is expected to embed
 * ([checkPythonVersionAgreement] compares the two); toolchain never ships it.
 */
fun expectedInterpreterVersion(embedLevel: Int, sdk: ResolvedPythonSdk?): String? =
    if (embedLevel == 0) null else sdk?.takeIf { it.rejection == null }?.version?.toReleaseString()

/**
 * The record written beside a packaged zip (`<archive>.embed.json`): the level, the family, the
 * warning and [interpreterVersion] ([expectedInterpreterVersion]). It records what the app expects;
 * the zip itself carries `python/` only.
 */
fun embedRecordJson(
    level: Int,
    platformFamily: String,
    warning: String?,
    interpreterVersion: String? = null,
): String {
    fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    return """
        {
          "embedLevel": $level,
          "platformFamily": ${quote(platformFamily)},
          "warning": ${warning?.let(::quote) ?: "null"},
          "interpreterVersion": ${interpreterVersion?.let(::quote) ?: "null"}
        }
    """.trimIndent() + "\n"
}
