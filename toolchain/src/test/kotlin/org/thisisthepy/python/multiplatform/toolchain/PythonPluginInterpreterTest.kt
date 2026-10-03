package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.toolchain.bundle.AcquirePythonInterpreterTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.AssemblePythonPackageTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonInterpreterInstaller
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import java.io.File
import java.lang.reflect.Modifier
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.2 and §1.12, issue #18, on an applied plugin: a variant at embedLevel 2 depends on
 * one shared `acquirePythonInterpreter<Triple>Py<Version>` task per (compileSdk version, triple),
 * whose output is `build/pythonRuntime/<triple>/<version>/`, and the requested version reaches the
 * installer for the variant's own triple. The installer is a fake: no network.
 */
class PythonPluginInterpreterTest {
    private fun evaluated(sdk: String? = "3.14.7", configure: PythonExtension.() -> Unit): Project {
        val project = ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("python-interpreter").toFile())
            .build()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.extensions.getByType(PythonExtension::class.java).apply {
            sdk?.let { compileSdk.assign(it) }
            localLibraryPath = "python"
            configure()
        }
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun Project.acquireTasks(): List<AcquirePythonInterpreterTask> =
        tasks.withType(AcquirePythonInterpreterTask::class.java).toList()

    private fun Project.build(name: String) = assertIs<BuildPythonArtifactTask>(tasks.getByName(name))

    private fun Project.pack(name: String) = assertIs<AssemblePythonPackageTask>(tasks.getByName(name))

    private fun Task.dependencyNames(): Set<String> = taskDependencies.getDependencies(this).map { it.name }.toSet()

    private val androidTask = "acquirePythonInterpreterAarch64LinuxAndroidPy3_14_7"

    @Test
    fun `one acquisition per pair, shared by every build type of the variant`() {
        val project = evaluated {
            androidArm64()
            buildTypes {
                getByName("debug")
                getByName("release")
            }
        }
        val buildDir = project.layout.buildDirectory.get().asFile

        val acquire = project.acquireTasks().single()
        assertEquals(androidTask, acquire.name)
        assertEquals("3.14.7", acquire.pythonVersion)
        assertEquals("aarch64-linux-android", acquire.target)
        assertEquals(File(buildDir, "pythonRuntime/aarch64-linux-android/3.14.7"), acquire.runtimeDir)

        listOf("Debug", "Release").forEach { buildType ->
            val bundle = project.build("buildPythonAndroidArm64$buildType")
            assertTrue(androidTask in bundle.dependencyNames(), "$buildType: ${bundle.dependencyNames()}")
            assertEquals("3.14.7", bundle.interpreterVersion)
            assertEquals(acquire.runtimeDir, bundle.interpreterDir)
            assertNull(bundle.interpreterRejection)

            val pack = project.pack("packagePythonAndroidArm64$buildType")
            assertEquals("3.14.7", pack.interpreterVersion)
            assertTrue(pack.interpreterBundled)
        }
    }

    @Test
    fun `the variant zip carries runtime beside python and its record says the interpreter is bundled`() {
        val project = evaluated { androidArm64() }
        val buildDir = project.layout.buildDirectory.get().asFile
        val bundle = File(buildDir, "pythonBundle/androidArm64-debug")
        File(bundle, "python/app/main.py").apply { parentFile.mkdirs() }.writeText("")
        File(bundle, "runtime/bin/python3").apply { parentFile.mkdirs() }.writeText("#!fake\n")

        val pack = project.pack("packagePythonAndroidArm64Debug")
        pack.destinationDirectory.get().asFile.mkdirs()
        pack.actions.forEach { it.execute(pack) }

        val zip = File(buildDir, "distributions/app-androidArm64-debug.zip")
        val entries = java.util.zip.ZipFile(zip).use { file -> file.entries().asSequence().map { it.name }.toSet() }
        assertTrue("runtime/bin/python3" in entries && "python/app/main.py" in entries, "$entries")
        val record = File(buildDir, "distributions/app-androidArm64-debug.embed.json").readText()
        assertTrue("\"interpreterVersion\": \"3.14.7\"" in record && "\"interpreterBundled\": true" in record, record)
    }

    @Test
    fun `the requested version reaches the installer for the variant's own triple`() {
        val project = evaluated(sdk = "3.14") {
            androidArm64()
            iosArm64()
        }
        val calls = mutableListOf<Triple<String, String, File>>()

        project.acquireTasks().sortedBy { it.name }.forEach { task ->
            task.installer = PythonInterpreterInstaller { version, target, destination ->
                calls += Triple(version, target, destination)
                Result.success("fake install of $version for $target")
            }
            task.acquire()
        }

        val buildDir = project.layout.buildDirectory.get().asFile
        assertEquals(
            listOf(
                Triple("3.14.7", "aarch64-linux-android", File(buildDir, "pythonRuntime/aarch64-linux-android/3.14.7")),
                Triple("3.14.7", "arm64-apple-ios", File(buildDir, "pythonRuntime/arm64-apple-ios/3.14.7")),
            ),
            calls,
        )
    }

    @Test
    fun `an unsupported pair fails that acquisition with pypackpack's message, and only its variant needs it`() {
        val project = evaluated(sdk = "3.13.0") {
            packaging { embedLevel = 0 }
            androidArm64()
            macosArm64()
        }

        val acquire = project.acquireTasks().single()
        val error = assertFailsWith<GradleException> { acquire.acquire() }
        val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("3.13.0" in messages && "aarch64-linux-android" in messages && "no pinned SHA-256" in messages, messages)

        assertTrue(acquire.name in project.build("buildPythonAndroidArm64Debug").dependencyNames())
        val macos = project.build("buildPythonMacosArm64Debug")
        assertFalse(macos.dependencyNames().any { it.startsWith(ACQUIRE_INTERPRETER_TASK) }, "${macos.dependencyNames()}")
        assertNull(macos.interpreterVersion)
    }

    @Test
    fun `level 0 on desktop acquires nothing, level 1 records the version only`() {
        val none = evaluated {
            packaging { embedLevel = 0 }
            macosArm64()
        }
        assertTrue(none.acquireTasks().isEmpty())
        assertNull(none.pack("packagePythonMacosArm64Debug").interpreterVersion)

        val external = evaluated {
            packaging { embedLevel = 1 }
            macosArm64()
        }
        assertTrue(external.acquireTasks().isEmpty())
        assertNull(external.build("buildPythonMacosArm64Debug").interpreterVersion)
        val pack = external.pack("packagePythonMacosArm64Debug")
        assertEquals("3.14.7", pack.interpreterVersion)
        assertFalse(pack.interpreterBundled)
    }

    @Test
    fun `level 2 without compileSdk fails that variant's bundling task, not configuration`() {
        val project = evaluated(sdk = null) { androidArm64() }

        assertTrue(project.acquireTasks().isEmpty())
        val rejection = assertNotNull(project.build("buildPythonAndroidArm64Debug").interpreterRejection)
        assertTrue("compileSdk" in rejection, rejection)
    }

    @Test
    fun `the host chain at level 2 acquires for the host triple`() {
        val project = evaluated { packaging { embedLevel = 2 } }

        val acquire = project.acquireTasks().single()
        assertEquals(Platforms.detectHostTarget(), acquire.target)
        val bundle = project.build(PythonPlugin.BUILD_TASK)
        assertTrue(acquire.name in bundle.dependencyNames(), "${bundle.dependencyNames()}")
        assertTrue(project.pack(PythonPlugin.PACKAGE_TASK).interpreterBundled)
    }

    /** AGENTS.md §15: every public property of the acquisition task carries an annotation. */
    @Test
    fun `every property of the acquisition task is annotated`() {
        val annotations = listOf(Input::class.java, OutputDirectory::class.java, Internal::class.java)
        val getters = AcquirePythonInterpreterTask::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.name.startsWith("get")
        }
        assertEquals(
            setOf("getPythonVersion", "getTarget", "getRuntimeDir", "getInstaller"),
            getters.map { it.name }.toSet(),
        )
        getters.forEach { getter ->
            assertTrue(annotations.any { getter.isAnnotationPresent(it) }, "${getter.name} has no input/output annotation")
        }
    }
}
