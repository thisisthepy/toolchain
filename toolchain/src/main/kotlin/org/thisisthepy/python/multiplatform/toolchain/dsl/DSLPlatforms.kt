package org.thisisthepy.python.multiplatform.toolchain.dsl


open class PlatformsExtension {
    var android: AndroidPlatformExtension? = null
    var ios: IosPlatformExtension? = null
    var desktop: DesktopPlatformExtension? = null

    fun android(name: String = "android", action: AndroidPlatformExtension.() -> Unit) {
        android = AndroidPlatformExtension(name).apply(action)
    }
    fun ios(action: IosPlatformExtension.() -> Unit) {
        ios = IosPlatformExtension().apply(action)
    }
    fun desktop(action: DesktopPlatformExtension.() -> Unit = {}) {
        desktop = DesktopPlatformExtension().apply(action)
    }

    fun androidArm64() = AndroidVariant("androidArm64").apply { arch = "arm64" }
    fun androidArm32() = AndroidVariant("androidArm32").apply { arch = "arm32" }
    fun androidX64() = AndroidVariant("androidX64").apply { arch = "x64" }
    fun androidX86() = AndroidVariant("androidX86").apply { arch = "x86" }

    fun iosArm64() = IosVariant("iosArm64").apply { arch = "arm64" }
    fun iosX64() = IosVariant("iosX64").apply { arch = "x64" }
    fun iosSimulatorArm64() = IosVariant("iosSimulatorArm64").apply { arch = "simulator-arm64" }

    fun macosX64() = DesktopVariant("macosX64").apply { os = "macosX64" }
    fun macosArm64() = DesktopVariant("macosArm64").apply { os = "macosArm64" }
    fun linuxX64() = DesktopVariant("linuxX64").apply { os = "linuxX64" }
    fun linuxArm64() = DesktopVariant("linuxArm64").apply { os = "linuxArm64" }
    fun mingwX64() = DesktopVariant("mingwX64").apply { os = "mingwX64" }
}

open class AndroidPlatformExtension(val name: String = "android") {
    var androidSdk: Int = 0
    val variants: MutableList<AndroidVariant> = mutableListOf()

    fun variants(vararg v: AndroidVariant) {
        variants.addAll(v)
    }
}

open class AndroidVariant(val name: String) {
    var arch: String = ""
    var binaries = BinariesExtension()
}

open class IosPlatformExtension {
    var iosSdk: Int = 0
    val variants: MutableList<IosVariant> = mutableListOf()

    fun variants(vararg v: IosVariant) {
        variants.addAll(v)
    }
}

open class IosVariant(val name: String) {
    var arch: String = ""
    var binaries = BinariesExtension()
}

open class DesktopPlatformExtension {
    val variants: MutableList<DesktopVariant> = mutableListOf()

    fun variants(vararg v: DesktopVariant) {
        variants.addAll(v)
    }
}

open class DesktopVariant(val name: String) {
    var os: String = ""
    var binaries = BinariesExtension()
}

/**
 * Maps a `python { platforms { ... } }` variant name (e.g. `"androidArm64"`, produced by
 * [PlatformsExtension.androidArm64] et al.) to the canonical target triple `pypackpack`'s
 * `Platforms.SUPPORTED_TARGETS` (`packpack/src/main/kotlin/.../utils/Platforms.kt`) recognizes, and
 * to the Kotlin Multiplatform target name a consumer's own `kotlin { }` block would register for it.
 *
 * Built by reading, not guessing. Two sources were checked, not one:
 * - `pypackpack`'s `Platforms.SUPPORTED_TARGETS` lists exactly `aarch64-linux-android` and
 *   `x86_64-linux-android` for Android -- no 32-bit, no x86.
 * - `PythonMultiplatform/python-multiplatform/build.gradle.kts` (the one place in this ecosystem
 *   that actually declares Android Kotlin/Native targets) registers only `androidNativeArm64()` and
 *   `androidNativeX64()`, never `androidNativeArm32()`/`androidNativeX86()`.
 *
 * Both agree, so [androidArm32] and [androidX86] (from [PlatformsExtension]) are deliberately
 * absent from [VARIANT_MAPPINGS] rather than mapped to a triple nothing downstream can build --
 * a prior round of this work stopped at exactly this gap ("declared Android variants ... have no
 * entry in Platforms.SUPPORTED_TARGETS"); this is what closes it without inventing an answer.
 *
 * `iosX64` maps to `"x86_64-apple-ios-simulator"`, not a `"x86_64-apple-ios"` device triple -- KMP's
 * `iosX64` target has only ever meant the Intel simulator (there has never been a physical x86_64
 * iOS device), and no such device triple exists in `Platforms.SUPPORTED_TARGETS` either.
 */
object PlatformTargetMapping {
    private data class Mapping(val canonicalTarget: String, val kotlinTargetName: String)

    private val VARIANT_MAPPINGS: Map<String, Mapping> =
        mapOf(
            "androidArm64" to Mapping("aarch64-linux-android", "androidNativeArm64"),
            "androidX64" to Mapping("x86_64-linux-android", "androidNativeX64"),
            "iosArm64" to Mapping("arm64-apple-ios", "iosArm64"),
            "iosX64" to Mapping("x86_64-apple-ios-simulator", "iosX64"),
            "iosSimulatorArm64" to Mapping("arm64-apple-ios-simulator", "iosSimulatorArm64"),
            "macosX64" to Mapping("x86_64-apple-darwin", "macosX64"),
            "macosArm64" to Mapping("aarch64-apple-darwin", "macosArm64"),
            "linuxX64" to Mapping("x86_64-unknown-linux-gnu", "linuxX64"),
            "linuxArm64" to Mapping("aarch64-unknown-linux-gnu", "linuxArm64"),
            "mingwX64" to Mapping("x86_64-pc-windows-msvc", "mingwX64"),
        )

    /** Named unsupported explicitly (rather than merely absent), so the rejection message can say why. */
    private val KNOWN_UNSUPPORTED: Set<String> = setOf("androidArm32", "androidX86")

    fun canonicalTarget(variantName: String): String = mapping(variantName).canonicalTarget

    fun kotlinTargetName(variantName: String): String = mapping(variantName).kotlinTargetName

    private fun mapping(variantName: String): Mapping =
        VARIANT_MAPPINGS[variantName] ?: throw IllegalArgumentException(unsupportedMessage(variantName))

    private fun unsupportedMessage(variantName: String): String =
        if (variantName in KNOWN_UNSUPPORTED) {
            "Python platform variant '$variantName' has no target triple in pypackpack's " +
                "Platforms.SUPPORTED_TARGETS (no 32-bit or x86 Android target is defined there, and " +
                "no androidNativeArm32()/androidNativeX86() Kotlin target exists anywhere in this " +
                "ecosystem either) -- it is rejected rather than silently mapped to the wrong triple."
        } else {
            "Unknown Python platform variant '$variantName'. Supported: " +
                VARIANT_MAPPINGS.keys.sorted().joinToString(", ")
        }
}

open class BinariesExtension {
    var frozenPackConfig: FrozenPackConfig? = null

    fun frozenPack(action: FrozenPackConfig.() -> Unit) {
        frozenPackConfig = FrozenPackConfig().apply(action)
    }
}

sealed class BuildTypeEnum {
    enum class DEBUG {
        INSTANT, BYTECODE
    }
    enum class RELEASE {
        BYTECODE, NATIVE, MIXED
    }
}

open class FrozenPackConfig {
    var test = BuildTypeEnum.DEBUG.INSTANT
    fun a() {
        test = BuildTypeEnum.DEBUG.BYTECODE
        // test = BuildTypeEnum.RELEASE.BYTECODE
    }
}