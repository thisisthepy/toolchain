package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import org.thisisthepy.python.multiplatform.toolchain.dsl.ResolvedPythonSdk

/**
 * The `gradle.properties` / `-P` name that states python-multiplatform's `pythonVersion` explicitly.
 * When set, it wins over the extension read from the `:python-multiplatform` project
 * ([selectPythonMultiplatformVersion]).
 */
const val PYTHON_MULTIPLATFORM_VERSION_PROPERTY = "python.multiplatform.pythonVersion"

/** The project path python-multiplatform has when it is part of the same build. */
const val PYTHON_MULTIPLATFORM_PROJECT_PATH = ":python-multiplatform"

/**
 * The extension python-multiplatform registers on its own project
 * (`python.multiplatform.gradle.EmbeddedPythonVersion`, thisisthepy/python-multiplatform#61): its
 * `pythonVersion` ("3.14.7") is the version it links and embeds; it also carries `freeThreaded` and
 * `majorMinor`, which toolchain does not read. Read reflectively: python-multiplatform is not on this
 * plugin's classpath.
 */
const val PYTHON_MULTIPLATFORM_EXTENSION = "pythonMultiplatform"

/** The getter of that extension's `pythonVersion` property. */
private const val PYTHON_MULTIPLATFORM_VERSION_GETTER = "getPythonVersion"

/**
 * Which python-multiplatform `pythonVersion` toolchain checks `compileSdk` against: the explicit
 * [propertyOverride] (`python.multiplatform.pythonVersion`) when set, otherwise [extensionValue] (the
 * `:python-multiplatform` project's extension), otherwise `null`, which means no check. Blank counts as
 * unset. Pure, so a plain test drives the precedence (AGENTS.md §14).
 */
fun selectPythonMultiplatformVersion(propertyOverride: String?, extensionValue: String?): String? =
    propertyOverride?.trim()?.takeIf { it.isNotEmpty() }
        ?: extensionValue?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Why [compileSdk] cannot be used with the python-multiplatform that embeds the interpreter, or `null`.
 *
 * python-multiplatform links libpython into its binaries and ships the matching stdlib; toolchain
 * ships only `python/`, whose wheels `compileSdk` selected (`--python-version`). The two must name the
 * same `X.Y.Z`. A rejection is returned only when both are known and differ: either side `null` means
 * there is nothing to compare. A [pythonMultiplatformVersion] that is not a version is a rejection too,
 * since it cannot be compared.
 */
fun checkPythonVersionAgreement(compileSdk: ResolvedPythonSdk?, pythonMultiplatformVersion: String?): String? {
    if (compileSdk == null || pythonMultiplatformVersion == null) return null
    val expected = compileSdk.version.toReleaseString()
    val embedded =
        runCatching { PythonVersion.parse(pythonMultiplatformVersion).toReleaseString() }.getOrElse {
            return "python-multiplatform's pythonVersion '$pythonMultiplatformVersion' is not an X.Y.Z " +
                "version, so compileSdk $expected cannot be checked against it."
        }
    if (expected == embedded) return null
    return "compileSdk is $expected but python-multiplatform's pythonVersion (its gradle.properties) is " +
        "$embedded, and that is the interpreter and stdlib embedded in the app. The wheels in python/ " +
        "are selected for $expected and would not load on $embedded. Set compileSdk to $embedded, or " +
        "use a python-multiplatform built for $expected."
}

/**
 * Makes the `:python-multiplatform` project of this build, when there is one, evaluate before [project],
 * so its `pythonMultiplatform` extension has its value when [readPythonMultiplatformExtensionVersion]
 * reads it. Nothing happens when there is no such project (or it is [project] itself).
 */
fun evaluatePythonMultiplatformFirst(project: Project) {
    val pythonMultiplatform = project.rootProject.findProject(PYTHON_MULTIPLATFORM_PROJECT_PATH) ?: return
    if (pythonMultiplatform != project) project.evaluationDependsOn(pythonMultiplatform.path)
}

/**
 * Reads `pythonMultiplatform.pythonVersion` from the `:python-multiplatform` project of this build,
 * reflectively (the extension's class is not on this classpath). `null` when there is no such project,
 * extension or property, or it has no value. A `Provider` is unwrapped; anything else is
 * `toString()`-ed.
 *
 * Only a project of the same build is reachable this way. Published consumers will read the version
 * from python-multiplatform's module metadata or jar resource (docs/SPEC.md §1.12); until then, and for
 * an included build, `python.multiplatform.pythonVersion` states it.
 */
fun readPythonMultiplatformExtensionVersion(project: Project): String? {
    val pythonMultiplatform = project.rootProject.findProject(PYTHON_MULTIPLATFORM_PROJECT_PATH) ?: return null
    val extension = pythonMultiplatform.extensions.findByName(PYTHON_MULTIPLATFORM_EXTENSION) ?: return null
    val getter =
        extension.javaClass.methods.firstOrNull {
            it.name == PYTHON_MULTIPLATFORM_VERSION_GETTER && it.parameterCount == 0
        } ?: return null
    return when (val value = getter.invoke(extension)) {
        null -> null
        is Provider<*> -> value.orNull?.toString()
        else -> value.toString()
    }
}
