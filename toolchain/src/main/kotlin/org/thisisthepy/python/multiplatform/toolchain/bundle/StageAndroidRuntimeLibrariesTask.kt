package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.packpack.bundle.AndroidRuntimeLibrary
import org.thisisthepy.python.multiplatform.packpack.bundle.androidNdkRoot
import org.thisisthepy.python.multiplatform.packpack.bundle.androidRuntimeLibraries
import org.thisisthepy.python.multiplatform.toolchain.deleteWithoutFollowingLinks
import java.io.File

/** The task that fills [androidRuntimeLibrariesRoot] (toolchain#155). */
const val STAGE_ANDROID_RUNTIME_LIBRARIES_TASK = "stagePythonAndroidRuntimeLibraries"

/**
 * `<buildDir>/pythonStaging/androidJniLibs`: an AGP jniLibs source root, so `<abi>/libc++_shared.so` here
 * lands in the APK's `lib/<abi>/`, where the loader looks for a staged extension's DT_NEEDED libraries.
 * Derived from the build directory alone for the same reason as [PythonStagingPlatform.rootIn]: it is
 * registered with AGP while the build script is still being evaluated.
 */
fun androidRuntimeLibrariesRoot(buildDir: File): File =
    File(File(buildDir, PythonStagingLayout.STAGING_DIRECTORY), "androidJniLibs")

/**
 * Decision 20 (a), approved by the leader 2026-11-25: package the NDK's `libc++_shared.so` per ABI into
 * jniLibs when any staged `.so` needs it, from the same NDK the wheels were built with. An extension the
 * cross build compiles with C++ needs it and nothing else ships it (on a Galaxy Z Flip4 the loader took an
 * old `/vendor/lib64` copy and failed). Which libraries, and the NDK they come from, are pypackpack's
 * `androidRuntimeLibraries` and `androidNdkRoot` (pypackpack#172), which find the NDK the way its cross
 * build does.
 */
abstract class StageAndroidRuntimeLibrariesTask : DefaultTask() {
    /** The staged Android payload (`<buildDir>/pythonStaging/android/python`). */
    @get:Internal
    lateinit var payloadDir: File

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val payloadSharedObjects: FileTree
        get() = project.fileTree(payloadDir).matching { include("**/*.so") }

    /** The staged Android variant's canonical triple; `null` when no Android variant is staged. */
    @get:Input
    @get:Optional
    var target: String? = null

    /** The NDK the libraries are copied from, an input so a different NDK re-stages. */
    @get:Input
    @get:Optional
    val ndkPath: String?
        get() = androidNdkRoot()?.path

    @get:OutputDirectory
    var destinationDir: File = File("unset")

    @TaskAction
    fun stage() {
        val staged = stageAndroidRuntimeLibraries(payloadDir, target, destinationDir, androidNdkRoot())
        staged.forEach { library ->
            logger.lifecycle(
                "Packaged ${library.name} (${library.abi}) from ${library.source}, needed by ${library.neededBy.joinToString(", ")}",
            )
        }
    }
}

/**
 * Clears [destinationRoot], then copies each NDK runtime library that the `.so` files under [payloadDir]
 * need into `<destinationRoot>/<abi>/`. A `null` [target] (no Android variant staged) or a payload that needs
 * nothing leaves the root empty. A library that is needed but cannot be found fails the build with
 * pypackpack's message.
 */
fun stageAndroidRuntimeLibraries(
    payloadDir: File,
    target: String?,
    destinationRoot: File,
    ndkRoot: File?,
): List<AndroidRuntimeLibrary> {
    destinationRoot.deleteWithoutFollowingLinks()
    destinationRoot.mkdirs()
    if (target == null || !payloadDir.isDirectory) return emptyList()
    val libraries = androidRuntimeLibraries(payloadDir, target, ndkRoot).getOrElse { error ->
        throw GradleException(error.message ?: "could not find the NDK runtime libraries the Android payload needs", error)
    }
    libraries.forEach { library ->
        library.source.copyTo(File(File(destinationRoot, library.abi), library.name), overwrite = true)
    }
    return libraries
}
