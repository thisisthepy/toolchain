package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Wires the `python { buildTypes { ... } }` DSL block (`DSLBuild.kt`'s `BuildTypesContainer`) all
 * the way to [BuildPythonArtifactTask.buildType] -- until now that property stayed at its
 * hard-coded default `"debug"` no matter what a consumer declared in `buildTypes { }`, because
 * nothing in `PythonPlugin.apply` ever read the container. [resolveActiveBuildType] is the selection
 * logic factored out of `PythonPlugin.apply`'s `afterEvaluate` so it can be tested without spinning
 * up a Gradle [org.gradle.api.Project], the same way `bundleWithPackpack` and `installWithPackpack`
 * were factored out of their tasks.
 *
 * `buildLevel` (`BuildType.compileLevel`) is wired separately, in `PythonPluginBuildLevelTest` /
 * [resolveBuildLevel] -- not unconditionally, since `pypackpack`'s `ResourceBundler` still rejects
 * every build level except `"instant"`, but as an explicit-rejection map: blank resolves to
 * `"instant"` (preserving what was previously hard-coded), and anything else fails loudly instead of
 * being silently ignored the way it was before that file existed.
 */
class PythonPluginBuildTypeTest {
    @Test
    fun `no declared build types passes the requested name through unchanged`() {
        val container = BuildTypesContainer()

        assertEquals("debug", resolveActiveBuildType(container, "debug"))
        assertEquals("release", resolveActiveBuildType(container, "release"))
    }

    @Test
    fun `a declared build type matching the request resolves to it`() {
        val container = BuildTypesContainer()
        container.getByName("release") { useCodeMinifier = true }

        assertEquals("release", resolveActiveBuildType(container, "release"))
    }

    @Test
    fun `requesting an undeclared build type fails loudly instead of silently falling back`() {
        val container = BuildTypesContainer()
        container.getByName("release")

        val error =
            assertFailsWith<IllegalArgumentException> {
                resolveActiveBuildType(container, "staging")
            }
        assertEquals(
            "Unknown Python build type 'staging'. Declared build types: release",
            error.message,
        )
    }
}
