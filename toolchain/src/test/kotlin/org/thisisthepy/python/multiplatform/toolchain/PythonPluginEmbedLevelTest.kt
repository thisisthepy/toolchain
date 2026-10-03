package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.bundle.AssemblePythonPackageTask
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.12: the resolved `embedLevel` reaches each packaging task -- per variant, by that
 * variant's platform family -- and the `python.embedLevel` property overrides the DSL.
 */
class PythonPluginEmbedLevelTest {
    private fun evaluated(
        property: String? = null,
        configure: PythonExtension.() -> Unit,
    ): Project {
        val project = ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("python-embed").toFile())
            .build()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        property?.let { project.extensions.extraProperties.set("python.embedLevel", it) }
        project.extensions.getByType(PythonExtension::class.java).configure()
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun Project.pack(name: String) = tasks.getByName(name) as AssemblePythonPackageTask

    @Test
    fun `each variant task carries the level resolved for its own platform family`() {
        val project = evaluated {
            packaging { embedLevel = 0 }
            androidArm64()
            macosArm64()
        }

        assertEquals(2, project.pack("packagePythonAndroidArm64Debug").embedLevel)
        assertEquals(0, project.pack("packagePythonMacosArm64Debug").embedLevel)
        assertEquals("android", project.pack("packagePythonAndroidArm64Debug").embedFamily)
        assertEquals("macos", project.pack("packagePythonMacosArm64Debug").embedFamily)
    }

    @Test
    fun `the host chain resolves with the host family, which is a desktop one`() {
        val project = evaluated { packaging { embedLevel = 1 } }

        assertEquals(1, project.pack(PythonPlugin.PACKAGE_TASK).embedLevel)
    }

    @Test
    fun `the gradle properties override beats the declared level`() {
        val project = evaluated(property = "2") {
            packaging { embedLevel = 0 }
            macosArm64()
        }

        assertEquals(2, project.pack("packagePythonMacosArm64Debug").embedLevel)
    }

    @Test
    fun `an invalid property value fails configuration with a message`() {
        val error = assertFailsWith<Throwable> {
            evaluated(property = "7") { macosArm64() }
        }
        val messages = generateSequence(error) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "python.embedLevel" in it && "'7'" in it }, "unexpected failure: $messages")
    }
}
