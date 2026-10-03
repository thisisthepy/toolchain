package org.thisisthepy.python.multiplatform.toolchain.dsl

import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.resolveVariants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The platform part of `(플러그인예시)build.gradle.kts`, written the way that file writes it: platform
 * blocks and variant calls directly inside `python { }`, with `listOf(...)` only grouping the
 * calls. Calling a variant function is what declares it, as `iosArm64()` does in `kotlin { }`.
 *
 * The example also lists `androidArm32()` and `androidX86()`. Android variants now follow
 * CPython's official Android support (PEP 738: arm64 and x86_64 only), so those two are compile
 * errors, and the build script below leaves them out.
 */
class PythonExtensionPlatformDslTest {
    private fun newExtension(): PythonExtension =
        ProjectBuilder.builder().build().objects.newInstance(PythonExtension::class.java)

    @Test
    fun `the example build file's platform declarations resolve to one variant per call`() {
        val python = newExtension()
        python.apply {
            android("android") { androidSdk = 24 }
            listOf(androidArm64(), androidX64())

            ios { iosSdk = 14 }
            listOf(iosArm64(), iosX64(), iosSimulatorArm64())

            desktop()
            listOf(macosX64(), macosArm64(), linuxX64(), linuxArm64(), mingwX64())
        }

        val variants = resolveVariants(python.platforms, python.buildTypes)

        assertEquals(
            listOf(
                "androidArm64", "androidX64",
                "iosArm64", "iosX64", "iosSimulatorArm64",
                "macosX64", "macosArm64", "linuxX64", "linuxArm64", "mingwX64",
            ),
            variants.map { it.platformVariantName },
        )
        assertEquals(24, variants.first { it.platformVariantName == "androidX64" }.minSdk)
        assertEquals(14, variants.first { it.platformVariantName == "iosSimulatorArm64" }.minSdk)
    }

    @Test
    fun `android arm32 and x86 are refused with CPython's supported Android targets named`() {
        val python = newExtension()

        listOf<() -> Unit>(
            { @Suppress("DEPRECATION_ERROR") python.androidArm32() },
            { @Suppress("DEPRECATION_ERROR") python.androidX86() },
        ).forEach { declare ->
            val error = assertFailsWith<UnsupportedOperationException> { declare() }
            assertTrue(
                error.message.orEmpty().contains("aarch64-linux-android") &&
                    error.message.orEmpty().contains("x86_64-linux-android"),
                "refusal should name the supported targets, was: ${error.message}",
            )
        }
        assertEquals(null, python.platforms.android)
    }
}
