package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallTargetDependenciesTask
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import java.io.File
import java.lang.reflect.Modifier
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.10, issue #16, on an applied plugin: each dependency set (platform variant x
 * flavor) gets an `installPythonDependencies<Set>` task that installs its own source sets'
 * dependencies for its own triple into `build/pythonDeps/<set>/`, and every bundle task of that set
 * lists the directory first in `libDirs` and depends on the task. The host chain does the same with
 * `installPythonDependenciesHost` and `build/pythonDeps/host/`.
 */
class PythonPluginTargetDependenciesTest {
    private fun newProject(configure: PythonExtension.() -> Unit): Project {
        val project = ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("python-target-deps").toFile())
            .build()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.extensions.getByType(PythonExtension::class.java).apply {
            compileSdk.assign("3.14.7")
            localLibraryPath = "python"
            sourceSets {
                getByName("commonMain").dependencies { implementation("six") }
                getByName("androidMain").dependencies { implementation("android-only") }
                getByName("iosMain").dependencies { implementation("ios-only") }
                getByName("desktopMain").dependencies { implementation("desktop-only") }
            }
            configure()
        }
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun Project.install(name: String): InstallTargetDependenciesTask =
        assertIs<InstallTargetDependenciesTask>(assertNotNull(tasks.findByName(name), "task '$name' is not registered"))

    private fun Task.dependencyNames(): Set<String> = taskDependencies.getDependencies(this).map { it.name }.toSet()

    @Test
    fun `each platform variant installs its own family's dependencies for its own triple`() {
        val project = newProject {
            androidArm64()
            iosArm64()
        }
        val buildDir = project.layout.buildDirectory.get().asFile

        val android = project.install("installPythonDependenciesAndroidArm64")
        assertEquals(listOf("six", "android-only"), android.requirements)
        assertEquals("aarch64-linux-android", android.pythonPlatform)
        assertEquals(File(buildDir, "pythonDeps/androidArm64"), android.installDir)
        assertEquals("3.14", android.installArguments["python-version"])
        assertEquals(":all:", android.installArguments["only-binary"])
        assertEquals(PythonPlugin.TASK_GROUP, android.group)

        val ios = project.install("installPythonDependenciesIosArm64")
        assertEquals(listOf("six", "ios-only"), ios.requirements)
        assertEquals("arm64-apple-ios", ios.pythonPlatform)
        assertEquals(File(buildDir, "pythonDeps/iosArm64"), ios.installDir)
    }

    @Test
    fun `a variant's bundle takes its own install directory first in libDirs and depends on its install`() {
        val project = newProject {
            androidArm64()
            iosArm64()
            sourceSets { getByName("commonMain").libDirs("vendor/site-packages") }
        }
        val buildDir = project.layout.buildDirectory.get().asFile

        val bundle = assertIs<BuildPythonArtifactTask>(project.tasks.getByName("buildPythonAndroidArm64Debug"))
        assertEquals(
            listOf(File(buildDir, "pythonDeps/androidArm64"), File(project.projectDir, "vendor/site-packages")),
            bundle.libDirs,
        )
        val dependencies = bundle.dependencyNames()
        assertTrue("installPythonDependenciesAndroidArm64" in dependencies, "$dependencies")
        assertFalse("installPythonDependenciesIosArm64" in dependencies, "$dependencies")
        // `installPythonDependencies` (uv add) still runs: it leaves the venv `bytecode` compiles with.
        assertTrue(PythonPlugin.INSTALL_TASK in dependencies, "$dependencies")
    }

    @Test
    fun `build types share one install per dependency set`() {
        val project = newProject {
            androidArm64()
            buildTypes {
                getByName("debug") {}
                getByName("release") {}
            }
        }

        assertNull(project.tasks.findByName("installPythonDependenciesAndroidArm64Debug"))
        assertNull(project.tasks.findByName("installPythonDependenciesAndroidArm64Release"))
        val install = project.install("installPythonDependenciesAndroidArm64")
        listOf("buildPythonAndroidArm64Debug", "buildPythonAndroidArm64Release").forEach { name ->
            val bundle = assertIs<BuildPythonArtifactTask>(project.tasks.getByName(name))
            assertEquals(install.installDir, bundle.libDirs.first(), name)
            assertTrue(install.name in bundle.dependencyNames(), name)
        }
    }

    @Test
    fun `a flavor's dependencies go only to that flavor's install`() {
        val project = newProject {
            androidArm64()
            projectFlavors {
                create("free")
                create("paid")
            }
            sourceSets {
                getByName("freeMain").dependencies { implementation("ads-sdk") }
                getByName("paidMain").dependencies { implementation("billing-sdk") }
            }
        }
        val buildDir = project.layout.buildDirectory.get().asFile

        val free = project.install("installPythonDependenciesAndroidArm64Free")
        assertEquals(listOf("six", "android-only", "ads-sdk"), free.requirements)
        assertEquals(File(buildDir, "pythonDeps/androidArm64-free"), free.installDir)
        val paid = project.install("installPythonDependenciesAndroidArm64Paid")
        assertEquals(listOf("six", "android-only", "billing-sdk"), paid.requirements)

        val paidBundle = assertIs<BuildPythonArtifactTask>(project.tasks.getByName("buildPythonAndroidArm64PaidDebug"))
        assertEquals(paid.installDir, paidBundle.libDirs.first())
        assertNull(free.rejection)
    }

    @Test
    fun `the host chain installs commonMain and desktopMain for the host triple`() {
        val project = newProject {}
        val buildDir = project.layout.buildDirectory.get().asFile

        val host = project.install("installPythonDependenciesHost")
        assertEquals(listOf("six", "desktop-only"), host.requirements)
        assertEquals(Platforms.detectHostTarget(), host.pythonPlatform)
        assertEquals(File(buildDir, "pythonDeps/host"), host.installDir)

        val bundle = assertIs<BuildPythonArtifactTask>(project.tasks.getByName(PythonPlugin.BUILD_TASK))
        assertEquals(listOf(File(buildDir, "pythonDeps/host")), bundle.libDirs)
        assertTrue(host.name in bundle.dependencyNames())
    }

    @Test
    fun `dependencies in a source set no variant reads fail the install tasks, not configuration`() {
        val project = newProject {
            androidArm64()
            sourceSets { getByName("androidArm64Main").dependencies { implementation("numpy") } }
        }

        val rejection = assertNotNull(project.install("installPythonDependenciesAndroidArm64").rejection)
        assertTrue("androidArm64Main" in rejection, rejection)
    }

    /**
     * AGENTS.md §15: Gradle validates property annotations only when a task runs, so compiling proves
     * nothing. Every public getter declared on the task must carry an input/output annotation, or a
     * changed requirement list would leave the task up to date.
     */
    @Test
    fun `every property of the install task is annotated`() {
        val annotations = listOf(Input::class.java, OutputDirectory::class.java, Internal::class.java)
        val getters = InstallTargetDependenciesTask::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.name.startsWith("get")
        }
        assertEquals(
            setOf("getRequirements", "getPythonPlatform", "getInstallArguments", "getRejection", "getPipRejection", "getInstallDir"),
            getters.map { it.name }.toSet(),
        )
        getters.forEach { getter ->
            assertTrue(annotations.any { getter.isAnnotationPresent(it) }, "${getter.name} has no input/output annotation")
        }
    }
}
