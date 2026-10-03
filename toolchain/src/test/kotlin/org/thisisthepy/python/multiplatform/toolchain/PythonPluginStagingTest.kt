package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingLayout
import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingPlatform
import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformsExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which staged directory each platform's packaging step is handed, and why there has to be a choice
 * at all.
 *
 * `f60bc3b` built one bundle per *variant* -- platform crossed with build type -- and stopped
 * exactly here: "nothing stages the bundle into Android assets, iOS resources or desktop
 * resources." Staging is not one-to-one with that graph, and this is the file that records why.
 *
 * ## A packaging step takes one directory, a variant graph offers several
 *
 * `desktopProcessResources` produces one resource root. An AGP `assembleDebug` merges one set of
 * asset source roots. Neither has a place to put "the androidArm64 payload and also the androidX64
 * payload" -- so something has to pick, and picking silently is how a consumer ends up shipping the
 * release payload in a debug build without a line of output saying so.
 *
 * The two dimensions are picked on different grounds, and both are grounds already used elsewhere
 * in this plugin rather than invented here:
 *
 * - **Build type** comes from `-Ppython.buildType` (defaulting to `debug`), which is what
 *   [resolveActiveBuildType] already resolves for the no-variant case. Gradle's own answer -- a
 *   build type per *consumer* task, so `assembleDebug` gets debug -- would need a variant-aware
 *   AGP/KMP wiring this plugin does not have; the project property is the honest approximation and
 *   it is logged.
 * - **Platform** is [PythonStagingPlatform], derived from the variant's canonical target through
 *   `pypackpack`'s own `Platforms.getPlatformFamily`, not from the DSL variant name. `macosArm64`,
 *   `linuxX64` and `mingwX64` are three families to `ppp` and one destination here (a JVM resource
 *   root), which is precisely the mapping that has to be written down somewhere.
 *
 * ## Why the ABI dimension collapses for Android and does not for desktop
 *
 * `ResourceBundler` overlays `src/main` with `src/<family>` and nothing else, so `androidArm64` and
 * `androidX64` produce byte-identical payloads -- the resource bundle carries no compiled artifacts
 * at build level `instant`. One android variant is therefore enough to stage, and which one is
 * arbitrary. Desktop is the opposite: `macos`, `linux` and `windows` are different families with
 * different overlays, so the host's own target is the one that a `:run` or a jar on this machine
 * should carry.
 */
class PythonPluginStagingTest {

    @Test
    fun `each declared platform family maps to the destination that platform actually packages from`() {
        assertEquals(PythonStagingPlatform.ANDROID, PythonStagingPlatform.forTarget("aarch64-linux-android"))
        assertEquals(PythonStagingPlatform.ANDROID, PythonStagingPlatform.forTarget("x86_64-linux-android"))
        assertEquals(PythonStagingPlatform.IOS, PythonStagingPlatform.forTarget("arm64-apple-ios"))
        assertEquals(PythonStagingPlatform.IOS, PythonStagingPlatform.forTarget("arm64-apple-ios-simulator"))
        // Three `ppp` families, one destination: a JVM resource root.
        assertEquals(PythonStagingPlatform.DESKTOP, PythonStagingPlatform.forTarget("aarch64-apple-darwin"))
        assertEquals(PythonStagingPlatform.DESKTOP, PythonStagingPlatform.forTarget("x86_64-unknown-linux-gnu"))
        assertEquals(PythonStagingPlatform.DESKTOP, PythonStagingPlatform.forTarget("x86_64-pc-windows-msvc"))
    }

    @Test
    fun `staging tasks are named per destination, not per variant, because a packaging step takes one`() {
        // Deliberately *not* `stagePythonBundle<Variant><BuildType>` the way `buildPython` and
        // `packagePython` are. Those produce one output each and a consumer may reasonably ask for
        // one of them; staging has three possible destinations no matter how many variants exist,
        // and a per-variant staging task would either write into a destination it has to share with
        // its siblings or produce a tree nothing reads. The dimension that survives is the
        // destination, so that is what the name carries.
        assertEquals(
            listOf("stagePythonBundleAndroid", "stagePythonBundleIos", "stagePythonBundleDesktop"),
            PythonStagingPlatform.values().map { PythonPlugin.STAGE_TASK + it.taskSuffix },
        )
    }

    @Test
    fun `only variants of the active build type are eligible to be staged`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        val buildTypes = BuildTypesContainer()
        buildTypes.getByName("debug")
        buildTypes.getByName("release")
        val variants = resolveVariants(platforms, buildTypes)

        val selected = selectStagingVariants(variants, activeBuildType = "release",
            hostTarget = "aarch64-apple-darwin")

        assertEquals("release", selected.getValue(PythonStagingPlatform.ANDROID).buildTypeName)
    }

    @Test
    fun `desktop stages the host's own target when several desktop variants are declared`() {
        val platforms = PlatformsExtension()
        platforms.desktop(); platforms.linuxX64(); platforms.macosArm64(); platforms.mingwX64()
        val variants = resolveVariants(platforms, BuildTypesContainer())

        val selected = selectStagingVariants(variants, activeBuildType = "debug",
            hostTarget = "aarch64-apple-darwin")

        assertEquals("macosArm64", selected.getValue(PythonStagingPlatform.DESKTOP).platformVariantName)
    }

    @Test
    fun `desktop falls back to the first declared variant when the host is not among them`() {
        // Cross-staging: a macOS machine building only `linuxX64` still has to put *something* in
        // the resource root, or `desktopJar` silently ships no Python at all.
        val platforms = PlatformsExtension()
        platforms.desktop(); platforms.linuxX64(); platforms.mingwX64()
        val variants = resolveVariants(platforms, BuildTypesContainer())

        val selected = selectStagingVariants(variants, activeBuildType = "debug",
            hostTarget = "aarch64-apple-darwin")

        assertEquals("linuxX64", selected.getValue(PythonStagingPlatform.DESKTOP).platformVariantName)
    }

    @Test
    fun `android collapses its ABIs to one staged payload, because the resource bundle has none`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64(); platforms.androidX64()
        val variants = resolveVariants(platforms, BuildTypesContainer())

        val selected = selectStagingVariants(variants, activeBuildType = "debug",
            hostTarget = "aarch64-apple-darwin")

        assertEquals(1, selected.size)
        assertEquals("androidArm64", selected.getValue(PythonStagingPlatform.ANDROID).platformVariantName)
    }

    @Test
    fun `a platform with no declared variant gets no staging destination rather than a guessed one`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        val variants = resolveVariants(platforms, BuildTypesContainer())

        val selected = selectStagingVariants(variants, activeBuildType = "debug",
            hostTarget = "aarch64-apple-darwin")

        assertNull(selected[PythonStagingPlatform.DESKTOP])
        assertNull(selected[PythonStagingPlatform.IOS])
    }

    @Test
    fun `no variant of the active build type leaves that platform unstaged`() {
        val platforms = PlatformsExtension()
        platforms.android(); platforms.androidArm64()
        val buildTypes = BuildTypesContainer()
        buildTypes.getByName("debug")
        val variants = resolveVariants(platforms, buildTypes)

        assertTrue(
            selectStagingVariants(variants, activeBuildType = "release",
                hostTarget = "aarch64-apple-darwin").isEmpty(),
        )
    }

    @Test
    fun `the payload root is the one packpack's manifest declares, not a name chosen here`() {
        // `ResourceBundler.PYTHON_ROOT` is `"python"` and its manifest records it as `pythonRoot`.
        // Staging under any other name would mean the manifest describes a layout that no longer
        // exists once staged.
        assertEquals("python", PythonStagingLayout.PAYLOAD_ROOT)
    }
}
