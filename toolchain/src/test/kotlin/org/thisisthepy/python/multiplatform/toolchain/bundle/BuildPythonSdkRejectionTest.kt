package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A `compileSdk` python-multiplatform has no runtime for (`resolvePythonSdk`'s rejection) fails the
 * bundling task's action, not the configuration, and only when there is a package to bundle.
 */
class BuildPythonSdkRejectionTest {
    private val rejection = "Python compileSdk '3.11.9-alpha' is not one python-multiplatform provides a runtime for"

    private fun task(packageDir: java.io.File?): BuildPythonArtifactTask {
        val project = ProjectBuilder.builder().build()
        return project.tasks.register("buildPythonUnderTest", BuildPythonArtifactTask::class.java) {
            this.packageDir = packageDir
            pythonSdkRejection = rejection
        }.get()
    }

    @Test
    fun `a rejected compileSdk fails the bundling task with the reason`() {
        val packageDir = kotlin.io.path.createTempDirectory("sdk-rejection-pkg").toFile()

        val error = assertFailsWith<GradleException> { task(packageDir).buildPython() }

        assertTrue(error.message.orEmpty().contains("3.11.9-alpha"), "was: ${error.message}")
    }

    @Test
    fun `with no package to bundle the rejection does not fire`() {
        task(packageDir = null).buildPython()
    }
}
