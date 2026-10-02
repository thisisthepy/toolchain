package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `defaultConfig { versionCode; versionName }` from `(플러그인예시)build.gradle.kts` is the Python
 * payload's version (decided 2026-10-03), handed to every bundling task and from there to
 * pypackpack's manifest. Undeclared stays undeclared: no invented `1` / `"1.0.0"`.
 */
class PythonPluginPayloadVersionTest {
    private fun evaluated(configure: PythonExtension.() -> Unit): ProjectInternal {
        val project = ProjectBuilder.builder().build() as ProjectInternal
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.extensions.getByType(PythonExtension::class.java).configure()
        project.evaluate()
        return project
    }

    @Test
    fun `the declared version reaches the host bundling task`() {
        val project = evaluated { defaultConfig { versionCode = 3; versionName = "1.2.0" } }

        val task = project.tasks.getByName("buildPython") as BuildPythonArtifactTask
        assertEquals("1.2.0", task.versionName)
        assertEquals(3, task.versionCode)
    }

    @Test
    fun `the declared version reaches every variant's bundling task`() {
        val project = evaluated {
            androidArm64()
            defaultConfig { versionCode = 3; versionName = "1.2.0" }
        }

        val task = project.tasks.getByName("buildPythonAndroidArm64Debug") as BuildPythonArtifactTask
        assertEquals("1.2.0", task.versionName)
        assertEquals(3, task.versionCode)
    }

    @Test
    fun `an undeclared version is not invented`() {
        val project = evaluated { }

        val task = project.tasks.getByName("buildPython") as BuildPythonArtifactTask
        assertNull(task.versionName)
        assertNull(task.versionCode)
    }
}
