package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetConfig
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.thisisthepy.python.multiplatform.toolchain.bundle.AssemblePythonPackageTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask


class PythonPlugin : Plugin<Project> {
    companion object {
        const val TASK_GROUP = "python"
        const val BUILD_TASK = "buildPython"
        const val INSTALL_TASK = "installPythonDependencies"
        const val PACKAGE_TASK = "packagePython"
    }

    override fun apply(project: Project) {
        val extension = project.extensions.create("python", PythonExtension::class.java, project.objects)

        val installTask = project.tasks.register<InstallDependenciesTask>(INSTALL_TASK) {
            group = TASK_GROUP
            description = "Installs Python dependencies using uv"
        }
        val buildTask = project.tasks.register<BuildPythonArtifactTask>(BUILD_TASK) {
            group = TASK_GROUP
            description = "Builds Python bundle including interpreter and source files"
            pythonVersion = extension.compileSdk
        }
        // `packageDir` is read lazily in `afterEvaluate` below, once `extension.localLibraryPath`
        // has its final value -- DSL blocks run before the plugin's own `afterEvaluate` callbacks.
        val packageTask = project.tasks.register<AssemblePythonPackageTask>(PACKAGE_TASK) {
            group = TASK_GROUP
            description = "Packages the Python application"
            embedLevel = extension.packaging.embedLevel
            fileName = extension.packaging.fileName
        }

        buildTask.configure { dependsOn(installTask) }
        packageTask.configure { dependsOn(buildTask) }

        project.afterEvaluate {
            project.logger.lifecycle("Configured Python compileSdk: ${extension.compileSdk}")

            // Only wires the directory through today; it must already be a `pypackpack` package
            // (`pyproject.toml` + `src/{main,<platform>}`) for `BuildPythonArtifactTask` to bundle
            // it, which nothing in this DSL enforces or documents yet -- see this task's report for
            // why that is left as a follow-up rather than done here.
            buildTask.configure {
                packageDir = extension.localLibraryPath?.let { project.file(it) }
            }
            installTask.configure {
                packageDir = extension.localLibraryPath?.let { project.file(it) }
            }

            // Wires `python { buildTypes { getByName("release") { ... } } }` through to
            // `BuildPythonArtifactTask.buildType`, which previously stayed at its hard-coded default
            // (`"debug"`) regardless of what a consumer declared -- nothing read `extension.buildTypes`
            // at all. There is no AGP-style variant task graph here (`toolchain` registers one
            // `buildPython` task, not one per build type -- see `docs/ecosystem.md`'s toolchain gap
            // list item 3, "wire up DSL", for the larger unfinished piece), so selection is a Gradle
            // project property rather than a task name: `-Ppython.buildType=release`, defaulting to
            // `"debug"`. `resolveActiveBuildType` fails loudly on an undeclared name rather than
            // silently falling back, so a typo in `-P` surfaces immediately instead of quietly
            // building `debug`.
            val requestedBuildType = (project.findProperty("python.buildType") as? String) ?: "debug"
            buildTask.configure {
                buildType = resolveActiveBuildType(extension.buildTypes, requestedBuildType)
            }
            // The naive copy-to-`build/pythonLibraries`-then-to-`src/main/assets/python` logic that
            // used to live here is gone: `docs/ecosystem.md`'s toolchain gap list ("Delete redundant
            // tasks ... and asset copy") named it directly, and it is now dead weight rather than a
            // second code path doing the same job -- `buildTask` (`BuildPythonArtifactTask`) already
            // delegates the real work to `pypackpack`'s `ResourceBundler` into `build/pythonBundle`
            // (this task's own report; `docs/ecosystem.md` §1, §5). Confirmed nothing depended on
            // it before deleting: no source under `usage-example/` reads `src/main/assets/python` or
            // `build/pythonLibraries`, `usage-example` itself never sets `python.localLibraryPath`
            // today, and `copyPythonLibrariesToAssets`/`preBuild` wiring existed only inside this
            // now-removed block, so nothing outside it could have depended on that task by name
            // either. Wiring the real bundle output into Android assets (or any other platform's
            // resource step) is a separate, not-yet-designed follow-up, not a like-for-like
            // replacement of this naive copy.

            installTask.configure {
                dependenciesList = collectInstallDependencies(extension.sourceSets.allSourceSets())
            }
        }
    }
}

/**
 * Resolves which declared `python { buildTypes { ... } }` entry is active, factored out of
 * [PythonPlugin.apply] so it can be exercised without a Gradle [org.gradle.api.Project] -- see
 * `PythonPluginBuildTypeTest`.
 *
 * No declared build types at all (the common case today -- `usage-example` does not use the
 * `buildTypes { }` block) passes [requestedName] straight through, so `buildType` keeps behaving
 * exactly as it did before this DSL block was wired up. Once at least one build type is declared,
 * [requestedName] must name one of them; an unmatched name throws rather than silently defaulting,
 * since that is a configuration mistake and `bundleWithPackpack`'s `BundleRequest.buildType` value
 * only affects packpack's output path convention (`<package>/build/packpack/resource/<buildType>/...`),
 * not something that would otherwise fail loudly on its own.
 */
fun resolveActiveBuildType(
    buildTypes: BuildTypesContainer,
    requestedName: String,
): String {
    val declared = buildTypes.all()
    if (declared.isEmpty()) return requestedName

    return declared.firstOrNull { it.name == requestedName }?.name
        ?: throw IllegalArgumentException(
            "Unknown Python build type '$requestedName'. Declared build types: " +
                declared.joinToString(", ") { it.name },
        )
}

/**
 * Collects the flat dependency list handed to [InstallDependenciesTask.dependenciesList], factored
 * out of [PythonPlugin.apply] the same way [resolveActiveBuildType] was -- see
 * `PythonPluginDependencyTest`.
 *
 * Folds `python { sourceSets { <name> { dependencies { integration(...) } } } }`
 * (`DSLBuild.kt`'s `DependenciesExtension.integrations`) in alongside `implementation(...)`. Until
 * now only `implementations` was read here, so an `integration()` declaration compiled but had zero
 * effect -- it never reached [InstallDependenciesTask], so `installWithPackpack` never saw it and no
 * `uv add` for it ever ran.
 *
 * This makes an `integration()` dependency install exactly like an `implementation()` one, no more
 * and no less: `pypackpack`'s `uv` backend (`installWithPackpack` -> `DependencyBackend.addDependencies`)
 * takes one flat `List<String>` with no type parameter, so it cannot treat the two differently even if
 * asked to. `(플러그인예시)build.gradle.kts`'s comment on `integration()` describes more --
 * "kotlin dependent python package - requires KLIBDEPENS file in whl dist directory ... KLIBDEPENS
 * 파일 없으면 install을 그냥 쓰라고 워닝 표시" (warn instead of installing when the wheel has no
 * `KLIBDEPENS` file) -- but grepping `pypackpack` for `KLIBDEPENS` turns up nothing: no wheel
 * dist-info inspection exists anywhere in this repository or `pypackpack` to check against. Wiring
 * that check is left undone rather than guessed at from one code comment; what is wired is the part
 * that is unambiguous -- the dependency reaching installation instead of being silently dropped.
 */
fun collectInstallDependencies(sourceSets: List<SourceSetConfig>): List<String> =
    sourceSets.flatMap { it.dependencies.implementations + it.dependencies.integrations }
