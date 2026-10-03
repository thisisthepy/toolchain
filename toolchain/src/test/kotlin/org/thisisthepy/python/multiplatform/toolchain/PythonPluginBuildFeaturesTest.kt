package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.16 and §1.7, the wiring half: what `buildFeatures { }` and `excludeMetaclass`
 * change on the tasks of a real ProjectBuilder project (the decisions are in `BuildFeaturesTest`).
 */
class PythonPluginBuildFeaturesTest {
    private fun newProject(kotlinMultiplatform: Boolean = false): Project {
        val project = ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("python-plugin-features").toFile())
            .build()
        if (kotlinMultiplatform) project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        if (kotlinMultiplatform) project.extensions.getByType(KotlinMultiplatformExtension::class.java).jvm("desktop")
        return project
    }

    private fun Project.python(action: PythonExtension.() -> Unit) =
        extensions.getByType(PythonExtension::class.java).action()

    private fun Project.evaluated(): Project = also { (it as ProjectInternal).evaluate() }

    private fun Project.withMetaDirs(): Project = also {
        python { sourceSets { getByName("commonMain").metaDirs("src/commonMain/generated/meta") } }
    }

    private fun Project.metaDirsOf(task: String): List<File> =
        (tasks.getByName(task) as BuildPythonArtifactTask).metaDirs

    // ---- metaclass / excludeMetaclass -----------------------------------------------------------

    @Test
    fun `by default buildPython forwards commonMain metaDirs`() {
        val project = newProject().withMetaDirs().evaluated()
        assertEquals(
            listOf(File(project.projectDir, "src/commonMain/generated/meta")),
            project.metaDirsOf(PythonPlugin.BUILD_TASK),
        )
    }

    @Test
    fun `metaclass false leaves buildPython without metaDirs`() {
        val project = newProject().withMetaDirs()
        project.python { buildFeatures { metaclass = false } }
        project.evaluated()
        assertEquals(emptyList(), project.metaDirsOf(PythonPlugin.BUILD_TASK))
    }

    @Test
    fun `the active build type's excludeMetaclass leaves buildPython without metaDirs`() {
        val project = newProject().withMetaDirs()
        project.extensions.extraProperties.set("python.buildType", "release")
        project.python { buildTypes { getByName("debug"); getByName("release") { excludeMetaclass = true } } }
        project.evaluated()
        assertEquals(emptyList(), project.metaDirsOf(PythonPlugin.BUILD_TASK))
    }

    @Test
    fun `excludeMetaclass drops metaDirs from the release variant task only`() {
        val project = newProject().withMetaDirs()
        project.python {
            macosArm64()
            buildTypes { getByName("debug"); getByName("release") { excludeMetaclass = true } }
        }
        project.evaluated()
        assertEquals(emptyList(), project.metaDirsOf("buildPythonMacosArm64Release"))
        assertEquals(
            listOf(File(project.projectDir, "src/commonMain/generated/meta")),
            project.metaDirsOf("buildPythonMacosArm64Debug"),
        )
    }

    @Test
    fun `metaclass false drops metaDirs from every variant task`() {
        val project = newProject().withMetaDirs()
        project.python {
            macosArm64()
            buildFeatures { metaclass = false }
        }
        project.evaluated()
        assertEquals(emptyList(), project.metaDirsOf("buildPythonMacosArm64Debug"))
    }

    // ---- compose, Python half ---------------------------------------------------------------------

    private fun Project.installTask(): InstallDependenciesTask =
        tasks.getByName(PythonPlugin.INSTALL_TASK) as InstallDependenciesTask

    @Test
    fun `compose with a requirement adds it to installPythonDependencies`() {
        val project = newProject()
        project.extensions.extraProperties.set("python.compose.pythonxCompose", "pythonx-compose==0.1.0")
        project.python {
            buildFeatures { compose = true }
            sourceSets { getByName("commonMain").dependencies { implementation("pyzmq") } }
        }
        project.evaluated()
        assertEquals(listOf("pyzmq", "pythonx-compose==0.1.0"), project.installTask().dependenciesList)
    }

    @Test
    fun `compose with a wheel directory adds pythonx-compose and merges the directory into find-links`() {
        val project = newProject()
        val wheels = File(project.projectDir, "wheels").apply { mkdirs() }
        val local = File(project.projectDir, "local").apply { mkdirs() }
        project.extensions.extraProperties.set("python.compose.pythonxCompose", "wheels")
        project.python {
            buildFeatures { compose = true }
            defaultConfig { pip { repositories { local { url = local.absolutePath } } } }
        }
        project.evaluated()
        val task = project.installTask()
        assertEquals(listOf("pythonx-compose"), task.dependenciesList)
        assertEquals("${local.absolutePath},${wheels.absolutePath}", task.pipArguments["find-links"])
    }

    @Test
    fun `compose without pythonxCompose fails installPythonDependencies' action, not configuration`() {
        val project = newProject()
        project.python {
            localLibraryPath = "app"
            buildFeatures { compose = true }
        }
        project.evaluated()
        val error = assertFailsWith<GradleException> { project.installTask().installDependencies() }
        assertTrue("python.compose.pythonxCompose" in error.message.orEmpty(), error.message)
    }

    // ---- compose, Kotlin half ---------------------------------------------------------------------

    @Test
    fun `compose adds python-multiplatform-compose to Kotlin commonMain implementation`() {
        val project = newProject(kotlinMultiplatform = true)
        project.extensions.extraProperties.set(
            "python.compose.kotlinModule",
            "org.thisisthepy.python.multiplatform:python-multiplatform-compose:0.1.0",
        )
        project.python { buildFeatures { compose = true } }
        project.evaluated()

        val dependencies = project.configurations.getByName("commonMainImplementation").dependencies
        assertTrue(
            dependencies.any {
                it.group == "org.thisisthepy.python.multiplatform" &&
                    it.name == "python-multiplatform-compose" && it.version == "0.1.0"
            },
            "commonMainImplementation: ${dependencies.map { "${it.group}:${it.name}:${it.version}" }}",
        )
    }

    @Test
    fun `compose with Kotlin Multiplatform and no kotlinModule fails configuration naming the property`() {
        val project = newProject(kotlinMultiplatform = true)
        project.python { buildFeatures { compose = true } }
        val error = assertFailsWith<Exception> { project.evaluated() }
        val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("python.compose.kotlinModule" in messages, messages)
    }

    @Test
    fun `compose without Kotlin Multiplatform configures and still installs pythonx-compose`() {
        val project = newProject()
        project.extensions.extraProperties.set("python.compose.pythonxCompose", "pythonx-compose")
        project.python { buildFeatures { compose = true } }
        project.evaluated()
        assertEquals(listOf("pythonx-compose"), project.installTask().dependenciesList)
    }
}
