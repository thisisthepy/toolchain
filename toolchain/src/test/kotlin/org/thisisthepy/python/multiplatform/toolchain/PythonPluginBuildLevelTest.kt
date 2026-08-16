package org.thisisthepy.python.multiplatform.toolchain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Reverses part of the judgement call `PythonPluginBuildTypeTest`'s kdoc recorded: `buildLevel` ==
 * `BuildType.compileLevel` was left completely unread by `PythonPlugin.apply`, because
 * `pypackpack`'s `ResourceBundler` (`bundle/resource/ResourceBundler.kt`) rejects every build level
 * except `"instant"` (`require(request.buildLevel == SUPPORTED_BUILD_LEVEL)`) and wiring
 * `compileLevel` straight through would have turned the newly-green `packagePython` chain red the
 * moment a consumer declared `buildTypes { getByName("release") { compileLevel = "bytecode" } }` --
 * which the reference file `(플러그인예시)build.gradle.kts` does, verbatim.
 *
 * [resolveBuildLevel] is the same explicit-rejection shape `PlatformTargetMapping` uses for
 * unsupported target variants, applied here instead of leaving the field unread: a blank
 * `compileLevel` (today's default for both `DebugBuildType` and `ReleaseBuildType`, and what
 * `usage-example` leaves it at) keeps resolving to `"instant"`, exactly `bundleWithPackpack`'s old
 * hard-coded value, so nothing that passes today stops passing. A `compileLevel` naming anything
 * `ppp` cannot bundle yet -- `"bytecode"`, `"native"`, `"mixed"`, all three of which the reference
 * file's `BuildTypeEnum` names -- now fails loudly with a message pointing at what *is* supported,
 * instead of compiling and being silently ignored the way it was before this file existed.
 */
class PythonPluginBuildLevelTest {
    @Test
    fun `a blank compileLevel resolves to instant, matching the old hard-coded default`() {
        assertEquals("instant", resolveBuildLevel(""))
    }

    @Test
    fun `an explicit instant compileLevel passes through unchanged`() {
        assertEquals("instant", resolveBuildLevel("instant"))
    }

    @Test
    fun `bytecode is rejected loudly because ppp's ResourceBundler cannot bundle it yet`() {
        val error = assertFailsWith<IllegalArgumentException> { resolveBuildLevel("bytecode") }
        assertEquals(
            "Python compileLevel 'bytecode' is not implemented by pypackpack's resource bundler " +
                "yet; only 'instant' is available today.",
            error.message,
        )
    }

    @Test
    fun `native and mixed are rejected the same way`() {
        assertFailsWith<IllegalArgumentException> { resolveBuildLevel("native") }
        assertFailsWith<IllegalArgumentException> { resolveBuildLevel("mixed") }
    }
}
