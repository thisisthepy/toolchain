package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.thisisthepy.python.multiplatform.toolchain.bundle.AssemblePythonPackageTask
import org.thisisthepy.python.multiplatform.toolchain.bundle.BuildPythonArtifactTask
import java.io.File


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

            extension.localLibraryPath?.let { path ->
                try {
                    val libFolder = project.file(path)
                    if (!libFolder.exists() || !libFolder.isDirectory) {
                        throw IllegalStateException("Library folder does not exist at: $path")
                    }
                    val localLibs = libFolder.walkTopDown().filter { it.isFile }.toList()
                    project.logger.lifecycle("Loaded ${localLibs.size} library files from local path: $path")

                    localLibs.forEach { file ->
                        val relativePath = file.relativeTo(libFolder)
                        project.logger.lifecycle("Detected library file: ${relativePath.path}")
                    }

                    val destDir = File(project.layout.buildDirectory.get().asFile, "pythonLibraries")
                    destDir.mkdirs()

                    localLibs.forEach { file ->
                        val relativePath = file.relativeTo(libFolder)
                        val destFile = File(destDir, relativePath.path)
                        destFile.parentFile.mkdirs()
                        file.copyTo(destFile, overwrite = true)
                        project.logger.lifecycle("Copied ${relativePath.path} to ${destFile.absolutePath}")
                    }

                    if (project.extensions.findByName("android") != null) {
                        project.logger.lifecycle("Android project detected. Copying Python libraries to assets.")
                        val assetsDir = File(project.projectDir, "src/main/assets/python")
                        project.logger.lifecycle("Assets directory: ${assetsDir.absolutePath}")
                        val copyTask = project.tasks.register<org.gradle.api.tasks.Copy>("copyPythonLibrariesToAssets") {
                            from(destDir)
                            into(assetsDir)
                        }
                        project.tasks.named("preBuild") {
                            dependsOn(copyTask)
                        }
                    }
                } catch (e: Exception) {
                    project.logger.error("Error loading local library files: ${e.message}")
                }
            }

            val deps = extension.sourceSets.allSourceSets().flatMap { sourceSet ->
                sourceSet.dependencies.implementations
            }
            installTask.configure {
                dependenciesList = deps
            }
        }
    }
}
