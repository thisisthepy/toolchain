package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The install task carries the `integration()` list apart from the flat install list (SPEC §1.10). */
class PythonPluginIntegrationListTest {
    @Test
    fun `collectIntegrationDependencies leaves implementation entries out`() {
        val a = SourceSetConfig("commonMain").apply {
            dependencies { implementation("pyzmq"); integration("pycomposeui") }
        }
        val b = SourceSetConfig("androidMain").apply { dependencies { integration("other") } }

        assertEquals(listOf("pycomposeui", "other"), collectIntegrationDependencies(listOf(a, b)))
    }

    @Test
    fun `the install task gets the integration list and the flat list`() {
        val project = ProjectBuilder.builder().build() as ProjectInternal
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.extensions.getByType(PythonExtension::class.java).apply {
            sourceSets {
                getByName("commonMain").dependencies { implementation("pyzmq"); integration("pycomposeui") }
            }
        }
        project.evaluate()

        val task = project.tasks.getByName("installPythonDependencies") as InstallDependenciesTask
        assertEquals(listOf("pycomposeui"), task.integrationsList)
        assertEquals(listOf("pyzmq", "pycomposeui"), task.dependenciesList)
    }
}
