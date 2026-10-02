package org.thisisthepy.python.multiplatform.toolchain

/** The `gradle.properties` / `-P` name that overrides `packaging { embedLevel }`. */
const val EMBED_LEVEL_PROPERTY = "python.embedLevel"

/** Families whose apps can run an interpreter that lives outside the app: the desktop ones. */
private val EXTERNAL_INTERPRETER_FAMILIES = setOf("macos", "linux", "windows")

/**
 * Decides the `embedLevel` a packaging task carries. Pure, so a plain test drives it.
 *
 * - 0: no interpreter bundled. 1: the app uses an external interpreter. 2: the interpreter is
 *   bundled inside the app.
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
        "interpreter): raised to 2, the interpreter is embedded."
}

/**
 * The record written beside a packaged zip (`<archive>.embed.json`). It states the level and, plainly,
 * that the interpreter is not part of the payload yet: acquiring it is #18 and pypackpack#21.
 */
fun embedRecordJson(level: Int, platformFamily: String, warning: String?): String {
    fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    return """
        {
          "embedLevel": $level,
          "platformFamily": ${quote(platformFamily)},
          "warning": ${warning?.let(::quote) ?: "null"},
          "interpreterBundled": false
        }
    """.trimIndent() + "\n"
}
