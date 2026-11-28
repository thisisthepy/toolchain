package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingPlatform
import org.thisisthepy.python.multiplatform.toolchain.bundle.STAGE_ANDROID_RUNTIME_LIBRARIES_TASK
import org.thisisthepy.python.multiplatform.toolchain.bundle.StageAndroidRuntimeLibrariesTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.androidRuntimeLibrariesRoot
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.13, the hand-off half: the staged root reaches the platform's own packaging step.
 *
 * - Desktop: the `stagePythonBundleDesktop` output is a source of the JVM target's
 *   `<target>ProcessResources`, so it ends up in the desktop jar.
 * - Android: the staged Android root is an asset source directory of `android.sourceSets.main`,
 *   registered at apply time (AGENTS.md §15: AGP reads source sets in its own `afterEvaluate`).
 *   `PythonPlugin` reaches that object reflectively, so the test applies the real Android Gradle
 *   plugin (the version `usage-example` uses) rather than a fake, to prove the method chain exists.
 */
class PythonPluginAttachmentTest {
    private fun newProject(): Project =
        ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("python-plugin-attach").toFile())
            .build()

    @Test
    fun `the staged desktop root is a source of desktopProcessResources and its staging task a dependency`() {
        val project = newProject()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).jvm("desktop")

        val processResources = project.tasks.getByName("desktopProcessResources") as Copy
        val stageTask = project.tasks.getByName("stagePythonBundleDesktop")

        assertTrue(
            stageTask in processResources.taskDependencies.getDependencies(processResources),
            "desktopProcessResources does not depend on stagePythonBundleDesktop",
        )

        val stagedRoot = PythonStagingPlatform.DESKTOP.rootIn(project.layout.buildDirectory.get().asFile)
        val stagedFile = File(stagedRoot, "python/app/main.py").apply { parentFile.mkdirs() }
        stagedFile.writeText("print('hi')\n")
        assertTrue(
            stagedFile.canonicalFile in processResources.source.files.map { it.canonicalFile },
            "the staged desktop root is not among desktopProcessResources' sources: ${processResources.source.files}",
        )
    }

    @Test
    fun `the staged android root is an asset source directory of android sourceSets main`() {
        val project = newProject()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.pluginManager.apply("com.android.application")

        val android = project.extensions.getByType(com.android.build.gradle.BaseExtension::class.java)
        val assetDirs = android.sourceSets.getByName("main").assets.srcDirs.map { it.canonicalFile }

        val stagedRoot = PythonStagingPlatform.ANDROID.rootIn(project.layout.buildDirectory.get().asFile)
        assertTrue(
            stagedRoot.canonicalFile in assetDirs,
            "android.sourceSets.main.assets does not contain $stagedRoot: $assetDirs",
        )
    }

    @Test
    fun `the android runtime libraries root is a jniLibs source directory, filled after android staging`() {
        // toolchain#155: AGP packages `<jniLibs dir>/<abi>/*.so` into `lib/<abi>/`, which is where the loader
        // finds `libc++_shared.so` for the staged extensions that need it.
        val project = newProject()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.pluginManager.apply("com.android.application")

        val android = project.extensions.getByType(com.android.build.gradle.BaseExtension::class.java)
        val jniLibDirs = android.sourceSets.getByName("main").jniLibs.srcDirs.map { it.canonicalFile }
        val root = androidRuntimeLibrariesRoot(project.layout.buildDirectory.get().asFile)
        assertTrue(root.canonicalFile in jniLibDirs, "android.sourceSets.main.jniLibs does not contain $root: $jniLibDirs")

        val task = project.tasks.getByName(STAGE_ANDROID_RUNTIME_LIBRARIES_TASK) as StageAndroidRuntimeLibrariesTask
        assertTrue(
            project.tasks.getByName("stagePythonBundleAndroid") in task.taskDependencies.getDependencies(task),
            "$STAGE_ANDROID_RUNTIME_LIBRARIES_TASK does not run after stagePythonBundleAndroid",
        )
        assertTrue(task.destinationDir.canonicalFile == root.canonicalFile)
    }
}
