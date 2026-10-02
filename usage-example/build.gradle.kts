import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig


group = rootProject.group
version = rootProject.version

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // Versioned explicitly, not via `includeBuild`: `:toolchain` is a regular subproject of this
    // same build (`ToolchainProject`), not a nested build with its own settings file, so it cannot
    // be its own composite-build plugin source the way `python-multiplatform-gradle-plugin` is for
    // PythonMultiplatform's root build. Resolving by Maven coordinate through `mavenLocal()`
    // (already in `pluginManagement.repositories`) is what makes this work today; it requires
    // `./gradlew :toolchain:publishToMavenLocal` to have been run at least once first.
    id("org.thisisthepy.python.multiplatform") version "1.0.0-alpha"
}

// Exercises the target DSL surface end to end -- until now `usage-example` did not apply the
// plugin at all, so the DSL in `dsl/` had no executable definition (`docs/ecosystem.md` §2, §4
// item 7). This is deliberately the subset of `(플러그인예시)build.gradle.kts` that today's tasks
// (`PythonPlugin.kt`) actually read: `compileSdk`, `packaging`, `buildTypes`, `platforms`, and
// `integration()`. The rest of the DSL surface (`buildFeatures`) is declared but not wired to any
// task yet -- see the plugin's own README/report for what is scaffolding versus live.
python {
    compileSdk = PY3_14_7
    // A real `pypackpack` package (`pyproject.toml` + `src/main/<pkg>`), so the chain has a payload
    // to carry. Without it `buildPython` skips `pypackpack` entirely and every step downstream of it
    // -- the zip, and now the staging tasks -- correctly produces nothing, which makes "the artifact
    // contains Python" unfalsifiable. `example_py` deliberately includes a non-`.py` file, because
    // `ResourceBundler` carries data files next to modules and staging has to preserve that.
    localLibraryPath = "python"
    // The example build file's pip block, minus `jit` (rejected until pypackpack builds recipes).
    // PyPI repeated as the default index changes nothing, but it makes the wiring part of a real build.
    defaultConfig {
        pip {
            autoUpdate = false
            repositories {
                central {
                    setUrl("https://pypi.org/simple", "https://pypi.org/simple")
                }
            }
        }
    }
    packaging {
        fileName = "usage-example"
    }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Project"
            isStatic = true
        }
    }
    
    jvm("desktop")
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "Project"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "Project.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }
    
    sourceSets {
        val desktopMain by getting
        
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "$group.toolchain.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "$group.toolchain.example"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = version as String
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "$group.toolchain.example.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "$group.toolchain.example"
            packageVersion = (version as String).substringBefore("-")
        }
    }
}
