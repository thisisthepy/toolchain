package org.thisisthepy.python.multiplatform.toolchain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `BuildType.compileLevel` -> `pypackpack`'s `BundleRequest.buildLevel` ([resolveBuildLevel]).
 *
 * `pypackpack`'s `ResourceBundler` (`bundle/resource/ResourceBundler.kt`, its `SUPPORTED_BUILD_LEVELS`)
 * implements `instant`, `bytecode` and `mixed` (Issues #15, #128), so all pass through. `native` needs
 * TypedPython's C and the CPython headers, so it is still refused -- per variant, inside that
 * variant's task action (AGENTS.md §14), with a message naming what it needs.
 *
 * Blank resolves to `"instant"`: both `DebugBuildType` and `ReleaseBuildType` default `compileLevel`
 * to `""`, and `usage-example` never sets it.
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
    fun `bytecode passes through, because pypackpack's ResourceBundler implements it`() {
        assertEquals("bytecode", resolveBuildLevel("bytecode"))
    }

    @Test
    fun `mixed passes through, because pypackpack's ResourceBundler implements it`() {
        assertEquals("mixed", resolveBuildLevel("mixed"))
    }

    @Test
    fun `native is rejected with a message naming the missing CPython headers and extension suffix`() {
        val error = assertFailsWith<IllegalArgumentException> { resolveBuildLevel("native") }
        val message = error.message.orEmpty()
        assertTrue(message.contains("'native'"), message)
        assertTrue(message.contains("TypedPython"), message)
        assertTrue(message.contains("headers") && message.contains("extension suffix"), message)
        assertTrue(message.contains("'mixed'"), message)
        assertTrue(!message.contains("pypackpack#19"), message)
    }

    @Test
    fun `only bytecode and mixed compile bytecode`() {
        assertTrue(compilesBytecode("bytecode"))
        assertTrue(compilesBytecode("mixed"))
        listOf("instant", "native", "").forEach { assertTrue(!compilesBytecode(it), it) }
    }

    @Test
    fun `an unknown compileLevel is rejected too, not passed to pypackpack`() {
        val error = assertFailsWith<IllegalArgumentException> { resolveBuildLevel("bytcode") }
        assertTrue(error.message.orEmpty().contains("'bytcode'"), error.message)
    }
}
