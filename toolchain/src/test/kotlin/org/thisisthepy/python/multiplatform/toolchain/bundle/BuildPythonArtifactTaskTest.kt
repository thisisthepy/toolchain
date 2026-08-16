package org.thisisthepy.python.multiplatform.toolchain.bundle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives one DSL-to-`pypackpack` delegation all the way through: [bundleWithPackpack] (the
 * function [BuildPythonArtifactTask] calls from its `@TaskAction`) must actually invoke
 * `pypackpack`'s `resource` bundler (`BaseInterface.create(BundleType.RESOURCE)` ->
 * `ResourceBundler`) and produce its real on-disk output -- not just compile against the
 * dependency.
 *
 * Before this task's implementation exists, this test fails to compile (no `bundleWithPackpack`
 * function, no `packpack` dependency on the classpath) rather than failing an assertion -- that is
 * the pre-implementation failure this file is meant to record, per this repository's TDD rule.
 */
class BuildPythonArtifactTaskTest {
    private fun fixturePackageDir(): File {
        val root = kotlin.io.path.createTempDirectory("packpack-fixture").toFile()
        File(root, "pyproject.toml").writeText(
            """
            [project]
            name = "fixture-package"
            version = "0.0.1"
            """.trimIndent(),
        )
        val pkgDir = File(root, "src/main/fixture_package")
        pkgDir.mkdirs()
        File(pkgDir, "__init__.py").writeText("VALUE = 1\n")
        return root
    }

    @Test
    fun `bundleWithPackpack delegates to packpack's resource bundler and produces real output`() {
        val packageDir = fixturePackageDir()
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out").toFile()

        val result = bundleWithPackpack(
            packageDir = packageDir,
            target = "macos",
            buildType = "debug",
            outputDir = outputDir,
        )

        assertEquals(1, result.fileCount)
        assertTrue(result.outputDir.resolve("python/fixture_package/__init__.py").isFile)
        assertTrue(result.manifestFile.isFile)
        assertTrue(
            result.manifestFile.readText().contains("\"packageName\": \"fixture-package\""),
            "manifest should be the real packpack ResourceBundler manifest, not a toolchain stand-in",
        )
    }

    /**
     * `bundleWithPackpack` reads `BuildPythonArtifactTask.minSdk` but, before this test, had no
     * parameter to carry it into the `BundleRequest` it builds -- see that task's kdoc on `minSdk`
     * ("It is logged, not forwarded"). `pypackpack`'s `6e36d3d` added `BundleRequest.minSdk` and
     * `ResourceBundler` records a declared value in its manifest
     * (`ResourceBundlerTest.bundle_manifestRecordsDeclaredMinSdkForAnAndroidTarget`). This test
     * proves the wiring all the way through: a `minSdk` passed to `bundleWithPackpack` for an
     * android-family target must show up in the real manifest `ResourceBundler` writes.
     *
     * Before `bundleWithPackpack` gains a `minSdk` parameter, this fails to compile -- the
     * pre-implementation failure this test is meant to record, not a regression.
     */
    @Test
    fun `bundleWithPackpack forwards minSdk to packpack's BundleRequest for an android target`() {
        val packageDir = fixturePackageDir()
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out-minsdk").toFile()

        val result = bundleWithPackpack(
            packageDir = packageDir,
            target = "aarch64-linux-android",
            buildType = "debug",
            outputDir = outputDir,
            minSdk = 24,
        )

        assertTrue(
            result.manifestFile.readText().contains("\"minSdk\": 24"),
            "declared minSdk should reach packpack's BundleRequest and be recorded in its manifest",
        )
    }
}
