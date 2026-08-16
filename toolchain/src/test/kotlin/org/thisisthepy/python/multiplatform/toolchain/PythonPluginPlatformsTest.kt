package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformsExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Wires `python { platforms { ... } }` (`DSLPlatforms.kt`) into two real checks -- until now the
 * block was read by nothing at all (`docs/ecosystem.md`'s gap list, and this round's own prior
 * report, both name `platforms` as unwired). [validateDeclaredPlatforms] and
 * [findPlatformsWithoutEnabledKotlinTarget] are the pure functions factored out of
 * `PythonPlugin.apply`'s `afterEvaluate` for this, the same shape [resolveActiveBuildType] and
 * [collectInstallDependencies] already take.
 *
 * This intentionally stops at validation, not target selection: `BuildPythonArtifactTask` and
 * `AssemblePythonPackageTask` are each one task, not a per-variant graph, so there is nowhere yet to
 * route a validated triple to. What changes is that declaring a variant `pypackpack` cannot build
 * for is now a loud failure instead of silently compiling and doing nothing.
 */
class PythonPluginPlatformsTest {
    @Test
    fun `no declared platforms validates to an empty list`() {
        val platforms = PlatformsExtension()

        assertEquals(emptyList(), validateDeclaredPlatforms(platforms))
    }

    @Test
    fun `declared android and ios variants resolve to their canonical triples`() {
        val platforms = PlatformsExtension()
        platforms.android { variants(platforms.androidArm64(), platforms.androidX64()) }
        platforms.ios { variants(platforms.iosArm64(), platforms.iosSimulatorArm64()) }

        val resolved = validateDeclaredPlatforms(platforms)

        assertEquals(
            listOf(
                "aarch64-linux-android",
                "x86_64-linux-android",
                "arm64-apple-ios",
                "arm64-apple-ios-simulator",
            ),
            resolved,
        )
    }

    @Test
    fun `declaring an unsupported android variant fails the build loudly`() {
        val platforms = PlatformsExtension()
        platforms.android { variants(platforms.androidArm32()) }

        assertFailsWith<IllegalArgumentException> { validateDeclaredPlatforms(platforms) }
    }

    @Test
    fun `every declared variant with a matching enabled Kotlin target reports no mismatch`() {
        val platforms = PlatformsExtension()
        platforms.ios { variants(platforms.iosArm64(), platforms.iosSimulatorArm64()) }

        val mismatches =
            findPlatformsWithoutEnabledKotlinTarget(
                platforms,
                enabledKotlinTargetNames = setOf("iosArm64", "iosSimulatorArm64", "desktop"),
            )

        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `a declared variant with no enabled Kotlin target is reported by name`() {
        val platforms = PlatformsExtension()
        platforms.android { variants(platforms.androidArm64()) }
        platforms.ios { variants(platforms.iosArm64()) }

        val mismatches =
            findPlatformsWithoutEnabledKotlinTarget(
                platforms,
                enabledKotlinTargetNames = setOf("iosArm64"), // androidNativeArm64 not enabled
            )

        assertEquals(listOf("androidArm64"), mismatches)
    }
}
