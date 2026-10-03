package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.ResolvedPythonSdk
import java.io.File

/** `build/pythonRuntime/<triple>/<version>/`: where an acquired interpreter is kept, one per pair. */
const val INTERPRETER_RUNTIME_DIRECTORY = "pythonRuntime"

/** Prefix of the shared acquisition tasks: `acquirePythonInterpreter<Triple>Py<Version>`. */
const val ACQUIRE_INTERPRETER_TASK = "acquirePythonInterpreter"

/**
 * What a packaging variant does about its interpreter (docs/SPEC.md §1.2, §1.12, issue #18). Decided
 * by [planInterpreter]; pure, so a plain test drives it (AGENTS.md §14).
 */
sealed class InterpreterPlan {
    /** embedLevel 0: no interpreter, nothing recorded. */
    object None : InterpreterPlan()

    /** embedLevel 1: the app uses an interpreter outside it; [version] is recorded, nothing bundled. */
    data class External(val version: String?) : InterpreterPlan()

    /** embedLevel 2: acquire [version] for [target] and bundle it. */
    data class Embed(val version: String, val target: String) : InterpreterPlan() {
        /** One task per (version, triple), shared by every variant with that pair. */
        val taskName: String
            get() = ACQUIRE_INTERPRETER_TASK +
                target.split('-', '_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } } +
                "Py" + version.replace('.', '_')

        fun runtimeDir(buildDir: File): File = File(buildDir, "$INTERPRETER_RUNTIME_DIRECTORY/$target/$version")
    }

    /** embedLevel 2 with no version to acquire. Fails that variant's bundling task with [reason]. */
    data class Refused(val reason: String) : InterpreterPlan()

    /** The version recorded in `<archive>.embed.json`, `null` when there is none. */
    val recordedVersion: String?
        get() = when (this) {
            is External -> version
            is Embed -> version
            else -> null
        }
}

/**
 * Maps a variant's resolved [embedLevel] (`resolveEmbedLevel`), its canonical [target] triple and the
 * resolved `compileSdk` ([sdk], `resolvePythonSdk`) to what happens to its interpreter.
 *
 * - 0: [InterpreterPlan.None].
 * - 1: [InterpreterPlan.External] with the compileSdk release, or `null` when none is declared.
 * - 2: [InterpreterPlan.Embed] with the compileSdk release (`X.Y.Z`, the form pypackpack's
 *   `installPython` takes). A missing or unprovidable compileSdk is [InterpreterPlan.Refused]: there is
 *   no version to bundle, and guessing one would ship an interpreter the build did not ask for.
 *
 * Whether pypackpack has an archive for (version, [target]) is not decided here; that is pypackpack's
 * table, and its refusal fails the acquisition task (`AcquirePythonInterpreterTask`).
 */
fun planInterpreter(embedLevel: Int, target: String, sdk: ResolvedPythonSdk?): InterpreterPlan =
    when (embedLevel) {
        0 -> InterpreterPlan.None
        1 -> InterpreterPlan.External(sdk?.takeIf { it.rejection == null }?.version?.toReleaseString())
        2 -> when {
            sdk == null -> InterpreterPlan.Refused(
                "embedLevel 2 bundles the interpreter compileSdk names, and python { compileSdk } is not " +
                    "set for $target. Set compileSdk (e.g. compileSdk = PY3_14_7).",
            )
            sdk.rejection != null -> InterpreterPlan.Refused(sdk.rejection)
            else -> InterpreterPlan.Embed(sdk.version.toReleaseString(), target)
        }
        else -> throw IllegalArgumentException("embedLevel $embedLevel is not 0, 1 or 2; resolveEmbedLevel never returns it.")
    }
