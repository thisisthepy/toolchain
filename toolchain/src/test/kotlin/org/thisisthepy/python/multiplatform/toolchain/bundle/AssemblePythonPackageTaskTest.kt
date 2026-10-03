package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.PythonPlugin
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.12: `packagePython` zips the bundle directory into
 * `build/distributions/<fileName>.zip` (`<fileName>-<variant>.zip` for a variant task) and fails
 * when the bundle directory does not exist.
 *
 * The real task class is driven: the plugin is applied to a ProjectBuilder project and the task's
 * own actions (its `@TaskAction` `copy()`) are executed on the instance, so the archive name,
 * destination and source wiring under test are the ones `AssemblePythonPackageTask`'s `init` sets.
 */
class AssemblePythonPackageTaskTest {
    private fun newProject(): Project =
        ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("python-package").toFile())
            .build()
            .also { it.pluginManager.apply("org.thisisthepy.python.multiplatform") }

    /**
     * What Gradle's executor does around a task action that this test has to do itself: create the
     * output directory (`destinationDirectory`) before the action runs.
     */
    private fun AssemblePythonPackageTask.runActions() {
        destinationDirectory.get().asFile.mkdirs()
        actions.forEach { it.execute(this) }
    }

    private fun writeBundle(dir: File) {
        File(dir, "python/app/__init__.py").apply { parentFile.mkdirs() }.writeText("")
        File(dir, "python/app/main.py").writeText("print('hi')\n")
        File(dir, "resource-manifest.json").writeText("{}")
    }

    private fun zipEntries(zip: File): Set<String> =
        ZipFile(zip).use { file -> file.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet() }

    @Test
    fun `packagePython zips build-pythonBundle into build-distributions-fileName zip`() {
        val project = newProject()
        val buildDir = project.layout.buildDirectory.get().asFile
        writeBundle(File(buildDir, "pythonBundle"))

        val task = project.tasks.getByName(PythonPlugin.PACKAGE_TASK) as AssemblePythonPackageTask
        task.fileName = "myapp"
        task.runActions()

        val zip = File(buildDir, "distributions/myapp.zip")
        assertTrue(zip.isFile, "expected $zip")
        assertEquals(
            setOf("python/app/__init__.py", "python/app/main.py", "resource-manifest.json"),
            zipEntries(zip),
        )
    }

    @Test
    fun `a variant package task zips its own bundle dir into fileName-variant zip`() {
        val project = newProject()
        val buildDir = project.layout.buildDirectory.get().asFile
        val variantDir = File(buildDir, "pythonBundle/androidArm64-debug")
        writeBundle(variantDir)
        // A sibling that must not end up in the variant's archive.
        File(buildDir, "pythonBundle/other.txt").writeText("not mine")

        val task = project.tasks.register("packagePythonAndroidArm64Debug", AssemblePythonPackageTask::class.java) {
            fileName = "myapp"
            bundleDir = variantDir
            variantName = "androidArm64-debug"
        }.get()
        task.runActions()

        val zip = File(buildDir, "distributions/myapp-androidArm64-debug.zip")
        assertTrue(zip.isFile, "expected $zip")
        assertEquals(
            setOf("python/app/__init__.py", "python/app/main.py", "resource-manifest.json"),
            zipEntries(zip),
        )
    }

    @Test
    fun `packagePython fails when the bundle directory does not exist`() {
        val project = newProject()
        val buildDir = project.layout.buildDirectory.get().asFile

        val task = project.tasks.getByName(PythonPlugin.PACKAGE_TASK) as AssemblePythonPackageTask
        task.fileName = "myapp"
        val error = assertFailsWith<Throwable> { task.runActions() }

        val messages = generateSequence(error) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(
            messages.any { "Bundle directory does not exist" in it },
            "unexpected failure: $messages",
        )
        assertFalse(File(buildDir, "distributions/myapp.zip").exists())
    }
}
