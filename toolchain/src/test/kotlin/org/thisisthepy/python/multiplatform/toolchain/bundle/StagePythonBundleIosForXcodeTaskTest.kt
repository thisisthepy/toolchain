package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StagePythonBundleIosForXcodeTaskTest {
    private fun tmp(): File = createTempDirectory("xcode-line").toFile()

    @Test
    fun `a missing staged directory fails naming it`() {
        val missing = File(tmp(), "python")
        val error = xcodePayloadLine(missing).exceptionOrNull()
        assertTrue(error is GradleException && "does not exist" in error.message!!)
    }

    @Test
    fun `an empty staged directory fails naming the missing package`() {
        val dir = File(tmp(), "python").apply { mkdirs() }
        val error = xcodePayloadLine(dir).exceptionOrNull()
        assertTrue(error is GradleException && "no Python package is configured" in error.message!!)
    }

    @Test
    fun `no iOS variant fails naming the variant`() {
        val dir = File(tmp(), "python").apply { mkdirs(); File(this, "a.py").writeText("") }
        val error = xcodePayloadLine(dir, iosVariantSelected = false).exceptionOrNull()
        assertTrue(error is GradleException && "No iOS variant" in error.message!!)
    }

    @Test
    fun `a populated directory prints the exact line`() {
        val dir = File(tmp(), "python").apply { mkdirs(); File(this, "a.py").writeText("") }
        assertEquals("PYTHON_PAYLOAD_DIR=${dir.absoluteFile.path}", xcodePayloadLine(dir).getOrThrow())
    }

    @Test
    fun `the task exists in group python and depends on stagePythonBundleIos`() {
        val project = ProjectBuilder.builder().withProjectDir(tmp()).build()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        val task = project.tasks.getByName("stagePythonBundleIosForXcode")
        val stage = project.tasks.getByName("stagePythonBundleIos")
        assertEquals("python", task.group)
        assertTrue(stage in task.taskDependencies.getDependencies(task))
    }
}
