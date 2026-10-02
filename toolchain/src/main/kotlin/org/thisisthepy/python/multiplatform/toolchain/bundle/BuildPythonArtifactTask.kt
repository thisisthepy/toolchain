package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.packpack.bundle.BundlerInterface
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleRequest
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleResult
import org.thisisthepy.python.multiplatform.packpack.bundle.BundleType
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.toolchain.resolveBuildLevel
import java.io.File

/**
 * Delegates "build a Python bundle" to `pypackpack`'s `resource` bundler
 * (`org.thisisthepy.python.multiplatform.packpack.bundle.resource.ResourceBundler`, reached
 * through `BundlerInterface.create(BundleType.RESOURCE)`) instead of hand-copying files.
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

    /**
     * The raw `python { buildTypes { getByName(...) { compileLevel = ... } } }` value for this
     * task's variant, resolved to `pypackpack`'s `BundleRequest.buildLevel` by
     * [org.thisisthepy.python.multiplatform.toolchain.resolveBuildLevel] **inside the task action,
     * not at configuration time**.
     *
     * Where that resolution happens is the whole point of the per-variant task graph. `pypackpack`'s
     * `ResourceBundler` implements exactly one build level (`require(request.buildLevel ==
     * "instant")`), so a consumer declaring `release { compileLevel = "native" }` has to be rejected
     * somewhere. Rejecting in `PythonPlugin.apply`'s `afterEvaluate` -- what this did before the
     * graph existed -- fails *configuration*, which takes every other variant down with it.
     * Rejecting here fails one task, so `gradle packagePython --continue` still builds every variant
     * whose level `ppp` does support. That is Issue #2's "reject only that variant, let the rest
     * run" requirement, and it is only expressible because there is now one task per variant.
     *
     * Blank resolves to `"instant"`, which is what both `DebugBuildType` and `ReleaseBuildType`
     * default to and what this value was hard-coded to before.
     */
    @get:Internal
    var compileLevel: String = ""

    /**
     * Where this variant's bundle is written. `null` means `build/pythonBundle` -- the single
     * hard-coded location used before the variant graph existed, and still what the aggregate
     * `buildPython` uses when no `python { }` platform block is declared. A variant task
     * sets `build/pythonBundle/<platformVariant>-<buildType>` instead, so two variants cannot
     * overwrite each other's payload.
     */
    @get:Internal
    var bundleDir: File? = null

    /**
     * The declared platform min SDK for this variant (`android { androidSdk = 24 }` or
     * `ios { iosSdk = 14 }`), `null` when that platform declares none.
     *
     * Forwarded to `pypackpack` as `BundleRequest.minSdk` (added in `pypackpack`'s `6e36d3d`, after
     * this field existed here with nowhere to send it -- see git history for that gap). `ppp`'s
     * `ResourceBundler` validates it against the target's platform family and, when declared for an
     * android-family target, records it in the bundle manifest.
     */
    @get:Internal
    var minSdk: Int? = null

    /**
     * `python { sourceSets { commonMain { metaDirs(...) } } }`, resolved by
     * [org.thisisthepy.python.multiplatform.toolchain.resolveMetaDirs] and forwarded to `pypackpack`
     * as [BundleRequest.metaDirs] verbatim. Empty by default, matching every caller that declares no
     * `metaDirs` -- see [BundleRequest.metaDirs]'s kdoc for what it is: generated metadata (`.pyi`
     * stubs) merged into the resource bundle's payload.
     */
    @get:Internal
    var metaDirs: List<File> = emptyList()

    /**
     * `python { sourceSets { commonMain { libDirs(...) } } }`, resolved by
     * [org.thisisthepy.python.multiplatform.toolchain.resolveLibDirs] and forwarded to `pypackpack`
     * as [BundleRequest.libDirs] verbatim. Empty by default -- see [BundleRequest.libDirs]'s kdoc.
     */
    @get:Internal
    var libDirs: List<File> = emptyList()

    @TaskAction
    fun buildPython() {
        val bundleDir = bundleDir ?: File(project.layout.buildDirectory.get().asFile, "pythonBundle")
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

        // Throws for anything `ppp`'s ResourceBundler cannot bundle. Deliberately here rather than
        // during configuration -- see `compileLevel`'s kdoc.
        val resolvedBuildLevel = resolveBuildLevel(compileLevel)

        val result = bundleWithPackpack(source, target, buildType, bundleDir, resolvedBuildLevel, minSdk, metaDirs, libDirs)
        logger.lifecycle(
            "Bundled ${result.fileCount} file(s) for Python '$pythonVersion' via packpack's " +
                "'${result.bundleType.id}' bundler into ${result.outputDir} " +
                "(target $target, buildType $buildType, buildLevel $resolvedBuildLevel" +
                (minSdk?.let { ", declared minSdk $it" } ?: "") +
                ")",
        )
    }
}

/**
 * The actual delegation to `pypackpack`, factored out of [BuildPythonArtifactTask.buildPython] so
 * it can be exercised without a Gradle [org.gradle.api.Project] -- see
 * `BuildPythonArtifactTaskTest`, which is what proves this call is real (produces
 * `ResourceBundler`'s actual on-disk manifest and payload) rather than merely compiling against
 * the dependency.
 *
 * @param minSdk forwarded to [BundleRequest.minSdk] verbatim. `null` (the default) matches every
 *   caller that has no platform SDK to declare (e.g. a non-android target); `ppp`'s
 *   `Platforms.requireValidMinSdk` rejects a non-null value declared against a non-android-family
 *   [target], so callers should not pass one for those targets.
 * @param metaDirs forwarded to [BundleRequest.metaDirs] verbatim. Empty by default, matching every
 *   caller that declares no `python { sourceSets { commonMain { metaDirs(...) } } }`.
 * @param libDirs forwarded to [BundleRequest.libDirs] verbatim. Empty by default, matching every
 *   caller that declares no `python { sourceSets { commonMain { libDirs(...) } } }`.
 */
fun bundleWithPackpack(
    packageDir: File,
    target: String,
    buildType: String,
    outputDir: File,
    buildLevel: String = "instant",
    minSdk: Int? = null,
    metaDirs: List<File> = emptyList(),
    libDirs: List<File> = emptyList(),
): BundleResult {
    val request =
        BundleRequest(
            packageDir = packageDir,
            target = target,
            buildType = buildType,
            buildLevel = buildLevel,
            outputDir = outputDir,
            overwrite = true,
            minSdk = minSdk,
            metaDirs = metaDirs,
            libDirs = libDirs,
        )
    return BundlerInterface.create(BundleType.RESOURCE)
        .bundle(request)
        .getOrElse { error ->
            throw GradleException("packpack resource bundling failed: ${error.message}", error)
        }
}
