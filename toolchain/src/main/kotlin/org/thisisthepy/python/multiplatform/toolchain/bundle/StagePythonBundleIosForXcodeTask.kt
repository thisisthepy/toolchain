package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** Name of the task an Xcode build phase calls (SPEC §1.13, toolchain#43). */
const val STAGE_IOS_FOR_XCODE_TASK = "stagePythonBundleIosForXcode"

/** The prefix of the placeholder bundle directory `PythonPlugin` gives a destination with no eligible variant. */
const val UNSELECTED_BUNDLE_PREFIX = "unselected-"

/** The key of the one machine-readable stdout line. */
const val PYTHON_PAYLOAD_DIR_KEY = "PYTHON_PAYLOAD_DIR"

/**
 * Decides what the Xcode task prints. Takes no `Project`, so a plain test can drive it.
 *
 * Success is `PYTHON_PAYLOAD_DIR=<absolute path>`. Failure is a [GradleException] naming why:
 * no iOS variant of the active build type ([iosVariantSelected] false), a staged directory that
 * is missing, or one that is empty (no package configured).
 */
fun xcodePayloadLine(stagedDir: File, iosVariantSelected: Boolean = true): Result<String> {
    if (!iosVariantSelected) {
        return Result.failure(
            GradleException(
                "No iOS variant of the active build type is declared in python { }, so there is no " +
                    "iOS Python payload to attach. Declare an iOS platform (e.g. iosArm64) and make " +
                    "sure the active build type (-Ppython.buildType) has a variant for it.",
            ),
        )
    }
    if (!stagedDir.isDirectory) {
        return Result.failure(
            GradleException("The staged iOS Python payload $stagedDir does not exist; iOS staging did not produce it."),
        )
    }
    if (stagedDir.list().isNullOrEmpty()) {
        return Result.failure(
            GradleException(
                "The staged iOS Python payload $stagedDir is empty: no Python package is configured " +
                    "(set python.localLibraryPath or a commonMain source set), so there is nothing to attach.",
            ),
        )
    }
    return Result.success("$PYTHON_PAYLOAD_DIR_KEY=${stagedDir.absoluteFile.path}")
}

/**
 * Runs iOS staging, then prints the staged directory for an Xcode build phase. It never copies
 * into the `.app`: python-multiplatform owns that phase (its #59).
 */
open class StagePythonBundleIosForXcodeTask : DefaultTask() {
    /** The iOS staging task whose output is reported. */
    @get:Internal
    var iosStage: TaskProvider<StagePythonBundleTask>? = null

    init {
        // Prints on every run; there is nothing to cache.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun report() {
        val stage = iosStage?.get() ?: throw GradleException("$STAGE_IOS_FOR_XCODE_TASK has no iOS staging task")
        val selected = stage.bundleDir?.name?.startsWith(UNSELECTED_BUNDLE_PREFIX) != true
        val line = xcodePayloadLine(File(stage.destinationDir, stage.payloadPath), selected).getOrThrow()
        // quiet, so it still shows under `./gradlew -q`.
        logger.quiet(line)
    }
}

/** Registers [STAGE_IOS_FOR_XCODE_TASK]; the one call `PythonPlugin` makes. */
fun registerStagePythonBundleIosForXcode(project: Project, iosStage: TaskProvider<StagePythonBundleTask>) {
    project.tasks.register(STAGE_IOS_FOR_XCODE_TASK, StagePythonBundleIosForXcodeTask::class.java) {
        group = "python"
        description = "Stages the iOS Python payload and prints PYTHON_PAYLOAD_DIR=<path> for an Xcode build phase"
        this.iosStage = iosStage
        dependsOn(iosStage)
    }
}
