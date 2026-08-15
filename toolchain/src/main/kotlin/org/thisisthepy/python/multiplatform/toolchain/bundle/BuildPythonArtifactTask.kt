package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.packpack.bundle.BaseInterface
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File

/**
 * Delegates "build a Python bundle" to `pypackpack`'s `resource` bundler
 * (`org.thisisthepy.python.multiplatform.packpack.bundle.resource.ResourceBundler`, reached
 * through `BaseInterface.create(BundleType.RESOURCE)`) instead of hand-copying files.
 *
 * `pypackpack` owns Python distribution acquisition, dependency resolution and bundling
 * (`docs/ecosystem.md` §1, §5: "ppp owns the work, toolchain owns the Gradle vocabulary"); this
 * task's only job is translating this project's Gradle-side inputs (`python.localLibraryPath`, a
 * target triple, the Gradle build type) into a [BundleRequest] and surfacing the result through
 * Gradle's task machinery. The previous implementation copied whatever
 * `python.localLibraryPath` held into `build/pythonBundle/libs` verbatim; it did not resolve
 * dependencies, apply platform overlays, or produce a manifest -- all of which the delegated
 * `ResourceBundler` now does for real.
 */
open class BuildPythonArtifactTask : DefaultTask() {
    // `@Internal` on every property below, not `@Input`/`@InputDirectory`: Gradle 8's task property
    // validation (run at execution time, not compile time -- this is why the naive implementation
    // never surfaced it, since nothing had ever actually run this task before `usage-example`
    // started applying the plugin) rejects any public task property with no annotation at all.
    // `@Internal` is the honest answer for now -- none of these participate in up-to-date checking
    // yet, so marking them `@Input`/`@InputDirectory` would be a false promise of incremental
    // build correctness this task does not implement. Wiring real incrementality (so a
    // `ResourceBundler` re-run is skipped when `packageDir`'s contents have not changed) is left
    // for follow-up.
    @get:Internal
    var pythonVersion: String = "default"

    /**
     * The `pypackpack` package directory: must contain `pyproject.toml` and a Python source root
     * (`src/main`, `src`, or the package root itself -- see `ResourceBundler`'s kdoc for the exact
     * fallback order). `null` means no package is configured yet, which is the case for any
     * consumer that has not set `python.localLibraryPath` to a real ppp package -- packaging is
     * skipped rather than failed, since not every project bundles Python resources.
     */
    @get:Internal
    var packageDir: File? = null

    /** A target string accepted by `Platforms.normalizeTarget` (alias or canonical triple). */
    @get:Internal
    var target: String = Platforms.detectHostTarget()

    @get:Internal
    var buildType: String = "debug"

    @TaskAction
    fun buildPython() {
        val bundleDir = File(project.layout.buildDirectory.get().asFile, "pythonBundle")
        val source = packageDir
        if (source == null) {
            // Preserves the previous naive implementation's behavior for this case: it always
            // created `build/pythonBundle` (empty when there was nothing to copy), which
            // `AssemblePythonPackageTask.copy()` requires to exist or it throws. Skipping
            // packpack here is correct -- there is no ppp package to bundle -- but the directory
            // still has to appear so the downstream `packagePython` Zip task keeps working for a
            // project that has not set `python.localLibraryPath` at all.
            bundleDir.mkdirs()
            logger.lifecycle(
                "No Python package directory configured (python.localLibraryPath); " +
                    "skipping packpack resource bundling.",
            )
            return
        }

        val result = bundleWithPackpack(source, target, buildType, bundleDir)
        logger.lifecycle(
            "Bundled ${result.fileCount} file(s) for Python '$pythonVersion' via packpack's " +
                "'${result.bundleType.id}' bundler into ${result.outputDir}",
        )
    }
}

/**
 * The actual delegation to `pypackpack`, factored out of [BuildPythonArtifactTask.buildPython] so
 * it can be exercised without a Gradle [org.gradle.api.Project] -- see
 * `BuildPythonArtifactTaskTest`, which is what proves this call is real (produces
 * `ResourceBundler`'s actual on-disk manifest and payload) rather than merely compiling against
 * the dependency.
 */
fun bundleWithPackpack(
    packageDir: File,
    target: String,
    buildType: String,
    outputDir: File,
): BundleResult {
    val request =
        BundleRequest(
            packageDir = packageDir,
            target = target,
            buildType = buildType,
            outputDir = outputDir,
            overwrite = true,
        )
    return BaseInterface.create(BundleType.RESOURCE)
        .bundle(request)
        .getOrElse { error ->
            throw GradleException("packpack resource bundling failed: ${error.message}", error)
        }
}
