package org.thisisthepy.python.multiplatform.toolchain.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [PlatformTargetMapping] implements Issue #2's "Build target platform setup" checklist item: a
 * `python { platforms { ... } }` variant name (`DSLPlatforms.kt`'s `androidArm64()`, `iosX64()`,
 * etc.) needs a canonical target triple before it can reach `pypackpack`'s
 * `BundleRequest.target`/`Platforms.normalizeTarget`, and a Kotlin Multiplatform target name before
 * it can be cross-checked against what the consumer's own `kotlin { }` block actually enables.
 *
 * The two variants this pins as explicitly *unsupported* -- `androidArm32`, `androidX86` -- are not
 * a guess: `pypackpack/packpack/.../utils/Platforms.kt`'s `SUPPORTED_TARGETS` lists only
 * `aarch64-linux-android` and `x86_64-linux-android` for Android (no 32-bit, no x86), and
 * `PythonMultiplatform/python-multiplatform/build.gradle.kts` (the one place in this ecosystem that
 * actually registers Android Kotlin/Native targets) declares only `androidNativeArm64()` and
 * `androidNativeX64()`, never `androidNativeArm32()` or `androidNativeX86()`. A prior round of this
 * work stopped here for exactly this reason ("declared Android variants ... have no entry in
 * Platforms.SUPPORTED_TARGETS") -- this test file, and the mapping it pins, is what closes that gap
 * without inventing triples nothing downstream can act on.
 */
class PlatformTargetMappingTest {
    @Test
    fun `android arm64 maps to the canonical androidNativeArm64 target and triple`() {
        assertEquals("aarch64-linux-android", PlatformTargetMapping.canonicalTarget("androidArm64"))
        assertEquals("androidNativeArm64", PlatformTargetMapping.kotlinTargetName("androidArm64"))
    }

    @Test
    fun `android x64 maps to the canonical androidNativeX64 target and triple`() {
        assertEquals("x86_64-linux-android", PlatformTargetMapping.canonicalTarget("androidX64"))
        assertEquals("androidNativeX64", PlatformTargetMapping.kotlinTargetName("androidX64"))
    }

    @Test
    fun `ios x64 maps to the x86_64 simulator triple, not a nonexistent device triple`() {
        // KMP's iosX64 target has only ever meant the Intel simulator -- there has never been a
        // physical x86_64 iOS device -- so it maps to Platforms.SUPPORTED_TARGETS'
        // "x86_64-apple-ios-simulator", not to a "x86_64-apple-ios" device triple that does not
        // exist anywhere in pypackpack.
        assertEquals("x86_64-apple-ios-simulator", PlatformTargetMapping.canonicalTarget("iosX64"))
        assertEquals("iosX64", PlatformTargetMapping.kotlinTargetName("iosX64"))
    }

    @Test
    fun `ios arm64 and ios simulator arm64 map to their distinct triples`() {
        assertEquals("arm64-apple-ios", PlatformTargetMapping.canonicalTarget("iosArm64"))
        assertEquals("arm64-apple-ios-simulator", PlatformTargetMapping.canonicalTarget("iosSimulatorArm64"))
    }

    @Test
    fun `desktop variants map to their host triples`() {
        assertEquals("x86_64-apple-darwin", PlatformTargetMapping.canonicalTarget("macosX64"))
        assertEquals("aarch64-apple-darwin", PlatformTargetMapping.canonicalTarget("macosArm64"))
        assertEquals("x86_64-unknown-linux-gnu", PlatformTargetMapping.canonicalTarget("linuxX64"))
        assertEquals("aarch64-unknown-linux-gnu", PlatformTargetMapping.canonicalTarget("linuxArm64"))
        assertEquals("x86_64-pc-windows-msvc", PlatformTargetMapping.canonicalTarget("mingwX64"))
    }

    @Test
    fun `android arm32 is explicitly rejected, not silently mapped`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PlatformTargetMapping.canonicalTarget("androidArm32")
        }
        assertEquals(true, error.message?.contains("androidArm32"))
    }

    @Test
    fun `android x86 is explicitly rejected, not silently mapped`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PlatformTargetMapping.canonicalTarget("androidX86")
        }
        assertEquals(true, error.message?.contains("androidX86"))
    }

    @Test
    fun `a totally unknown variant name is rejected with the supported list`() {
        val error = assertFailsWith<IllegalArgumentException> {
            PlatformTargetMapping.canonicalTarget("nonsense")
        }
        assertEquals(true, error.message?.contains("Unknown Python platform variant 'nonsense'"))
    }
}
