package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.bundling.Zip
import java.io.File


open class AssemblePythonPackageTask : Zip() {
    // Same fix as `InstallDependenciesTask.dependenciesList` / `BuildPythonArtifactTask`'s
    // properties: Gradle 8's task property validation (execution-time) rejects an unannotated
    // public task property, and `Zip`'s own annotated properties (`archiveFileName`, etc.) do not
    // cover these two custom ones. `@Internal` since neither participates in up-to-date checking.
    @get:Internal
    var embedLevel: Int = 0

    @get:Internal
    var fileName: String = "app"

    /**
     * Which bundle directory to zip. `null` means `build/pythonBundle` -- the single hard-coded
     * location this task used before the per-variant graph existed, and still what the aggregate
     * `packagePython` uses when no `python { platforms { ... } }` block is declared. A variant task
     * points at that variant's own `build/pythonBundle/<platformVariant>-<buildType>`.
     */
    @get:Internal
    var bundleDir: File? = null

    /**
     * Distinguishes this variant's archive from its siblings: `<fileName>-<variantName>.zip` instead
     * of `<fileName>.zip`. `null` keeps the un-suffixed name, so the existing single-task chain still
     * produces exactly `build/distributions/<fileName>.zip`.
     */
    @get:Internal
    var variantName: String? = null

    init {
        group = "python"
        description = "Packages the Python application"

        // Declared here, not inside `copy()`. `AbstractCopyTask.getSource()` is `@SkipWhenEmpty`, and
        // Gradle evaluates it *before* running the action -- so a `from()` issued inside `copy()`
        // arrives too late and the task is skipped as NO-SOURCE without ever calling `copy()` at
        // all. That is what `:usage-example:packagePython` did before this change: it reported
        // NO-SOURCE and produced no archive even with `build/pythonBundle` populated. Registering
        // the spec as a provider keeps it lazy (`bundleDir`, `fileName` and `variantName` are all
        // set by `PythonPlugin` after construction) while making the source and the archive name
        // visible to Gradle's own up-to-date/skip machinery. Without this the per-variant
        // `packagePython<Variant>` tasks would all be NO-SOURCE too -- a graph that routes nothing.
        from(project.provider { resolvedBundleDir() })
        archiveFileName.set(project.provider { variantName?.let { "$fileName-$it.zip" } ?: "$fileName.zip" })
        destinationDirectory.set(File(project.layout.buildDirectory.get().asFile, "distributions"))
    }

    /** `null` [bundleDir] means the pre-variant-graph default, `build/pythonBundle`. */
    private fun resolvedBundleDir(): File =
        bundleDir ?: File(project.layout.buildDirectory.get().asFile, "pythonBundle")

    override fun copy() {
        logger.lifecycle("Packaging Python application with embedLevel: $embedLevel and fileName: $fileName")

        val bundleDir = resolvedBundleDir()
        if (!bundleDir.exists()) {
            logger.error("Bundle directory does not exist, cannot package.")
            throw RuntimeException("Bundle directory does not exist, cannot package.")
        }

        super.copy()
    }
}
