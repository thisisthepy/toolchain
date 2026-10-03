package org.thisisthepy.python.multiplatform.toolchain.dsl


/**
 * The platforms and variants declared directly inside `python { }`, the way
 * `(플러그인예시)build.gradle.kts` writes them:
 *
 * ```kotlin
 * android("android") { androidSdk = 24 }
 * listOf(androidArm64(), androidX64())
 * ios { iosSdk = 14 }
 * listOf(iosArm64(), iosX64(), iosSimulatorArm64())
 * desktop()
 * listOf(macosX64(), macosArm64(), linuxX64(), linuxArm64(), mingwX64())
 * ```
 *
 * Calling a variant function declares that variant, as `iosArm64()` declares a target in
 * `kotlin { }`; the surrounding `listOf(...)` only groups the calls. A variant called before (or
 * without) its platform block creates that block with its defaults, and a platform block called
 * again re-applies to the same block, so declaration order does not matter. A variant is declared
 * once however often it is called.
 *
 * [PythonExtension] exposes each function by delegating here, so the plugin and its tests read
 * one holder.
 */
open class PlatformsExtension {
    var android: AndroidPlatformExtension? = null
    var ios: IosPlatformExtension? = null
    var desktop: DesktopPlatformExtension? = null

    fun android(
        name: String = "android",
        action: AndroidPlatformExtension.() -> Unit = {},
    ): AndroidPlatformExtension = androidPlatform().apply { this.name = name }.apply(action)

    fun ios(action: IosPlatformExtension.() -> Unit = {}): IosPlatformExtension = iosPlatform().apply(action)

    fun desktop(action: DesktopPlatformExtension.() -> Unit = {}): DesktopPlatformExtension =
        desktopPlatform().apply(action)

    fun androidArm64(): AndroidVariant = androidPlatform().declare(AndroidVariant("androidArm64").apply { arch = "arm64" })
    fun androidX64(): AndroidVariant = androidPlatform().declare(AndroidVariant("androidX64").apply { arch = "x64" })

    @Deprecated(UNSUPPORTED_ANDROID_VARIANT, level = DeprecationLevel.ERROR)
    fun androidArm32(): AndroidVariant = throw UnsupportedOperationException("androidArm32: $UNSUPPORTED_ANDROID_VARIANT")

    @Deprecated(UNSUPPORTED_ANDROID_VARIANT, level = DeprecationLevel.ERROR)
    fun androidX86(): AndroidVariant = throw UnsupportedOperationException("androidX86: $UNSUPPORTED_ANDROID_VARIANT")

    fun iosArm64(): IosVariant = iosPlatform().declare(IosVariant("iosArm64").apply { arch = "arm64" })
    fun iosX64(): IosVariant = iosPlatform().declare(IosVariant("iosX64").apply { arch = "x64" })
    fun iosSimulatorArm64(): IosVariant =
        iosPlatform().declare(IosVariant("iosSimulatorArm64").apply { arch = "simulator-arm64" })

    fun macosX64(): DesktopVariant = desktopPlatform().declare(DesktopVariant("macosX64").apply { os = "macosX64" })
    fun macosArm64(): DesktopVariant = desktopPlatform().declare(DesktopVariant("macosArm64").apply { os = "macosArm64" })
    fun linuxX64(): DesktopVariant = desktopPlatform().declare(DesktopVariant("linuxX64").apply { os = "linuxX64" })
    fun linuxArm64(): DesktopVariant = desktopPlatform().declare(DesktopVariant("linuxArm64").apply { os = "linuxArm64" })
    fun mingwX64(): DesktopVariant = desktopPlatform().declare(DesktopVariant("mingwX64").apply { os = "mingwX64" })

    private fun androidPlatform() = android ?: AndroidPlatformExtension().also { android = it }
    private fun iosPlatform() = ios ?: IosPlatformExtension().also { ios = it }
    private fun desktopPlatform() = desktop ?: DesktopPlatformExtension().also { desktop = it }

    companion object {
        /**
         * Why `androidArm32()` and `androidX86()` do not compile. The example build file lists them, but
         * it predates the decision to follow CPython's official Android support (PEP 738), which covers
         * exactly the two triples below; `pypackpack`'s `Platforms.SUPPORTED_TARGETS` agrees.
         */
        const val UNSUPPORTED_ANDROID_VARIANT: String =
            "CPython supports only aarch64-linux-android (androidArm64) and x86_64-linux-android " +
                "(androidX64) on Android (PEP 738); 32-bit and x86 Android are not built."
    }
}

open class AndroidPlatformExtension(var name: String = "android") {
    var androidSdk: Int = 0
    val variants: MutableList<AndroidVariant> = mutableListOf()

    internal fun declare(variant: AndroidVariant): AndroidVariant =
        variants.firstOrNull { it.name == variant.name } ?: variant.also { variants.add(it) }
}

open class AndroidVariant(val name: String) {
    var arch: String = ""
    var binaries = BinariesExtension()
}

open class IosPlatformExtension {
    var iosSdk: Int = 0
    val variants: MutableList<IosVariant> = mutableListOf()

    internal fun declare(variant: IosVariant): IosVariant =
        variants.firstOrNull { it.name == variant.name } ?: variant.also { variants.add(it) }
}

open class IosVariant(val name: String) {
    var arch: String = ""
    var binaries = BinariesExtension()
}

open class DesktopPlatformExtension {
    val variants: MutableList<DesktopVariant> = mutableListOf()

    internal fun declare(variant: DesktopVariant): DesktopVariant =
        variants.firstOrNull { it.name == variant.name } ?: variant.also { variants.add(it) }
}

open class DesktopVariant(val name: String) {
    var os: String = ""
    var binaries = BinariesExtension()
}

/**
 * Maps a `python { }` platform variant name (e.g. `"androidArm64"`, produced by
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
            "Python platform variant '$variantName' is not built: " +
                PlatformsExtension.UNSUPPORTED_ANDROID_VARIANT
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