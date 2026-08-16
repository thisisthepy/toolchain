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

    init {
        group = "python"
        description = "Packages the Python application"
    }

    override fun copy() {
        logger.lifecycle("Packaging Python application with embedLevel: $embedLevel and fileName: $fileName")

        val bundleDir = File(project.layout.buildDirectory.get().asFile, "pythonBundle")
        if (!bundleDir.exists()) {
            logger.error("Bundle directory does not exist, cannot package.")
            throw RuntimeException("Bundle directory does not exist, cannot package.")
        }

        from(bundleDir)
        archiveFileName.set("$fileName.zip")
        destinationDirectory.set(File(project.layout.buildDirectory.get().asFile, "distributions"))
        super.copy()
    }
}
