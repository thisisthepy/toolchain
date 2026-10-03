group = rootProject.group
// tcl's own version, which `tcl --version` prints and the toolchain-lite wheel carries:
// publish-pypi.yml refuses to publish unless it equals pyproject.toml's. The plugin keeps
// rootProject.version.
version = "0.1.0"

plugins {
    // No explicit version: `org.jetbrains.kotlin.jvm` is already on this settings' shared plugin
    // classpath (pulled in transitively by the other subprojects' Kotlin Multiplatform/`kotlin-dsl`
    // plugins), and Gradle rejects a second, explicitly-versioned request for a plugin ID it has
    // already resolved ("plugin is already on the classpath with an unknown version").
    id("org.jetbrains.kotlin.jvm")
    application
    id("org.graalvm.buildtools.native") version "0.10.6"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // `tcl` ("toolchain-lite", GitHub issue thisisthepy/toolchain#1) is a thin CLI layer over
    // `pypackpack`'s dependency backend -- the same artifact `:toolchain-gradle-plugin` already resolves through
    // `mavenLocal()` (see that module's `build.gradle.kts` for the `publishToMavenLocal`
    // prerequisite this also depends on).
    implementation("org.thisisthepy.python.multiplatform:packpack:0.1.0")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

application {
    // `main()` lives in `Cli.kt`, so its Kotlin-generated facade class is `CliKt`, not `MainKt`.
    mainClass.set("org.thisisthepy.python.multiplatform.tcl.CliKt")
}

// `packpack` is built with Kotlin 2.3.0 while this module's own `compileKotlin` resolves to
// Kotlin 2.1.0 (whichever Kotlin Gradle Plugin version wins the shared plugin classpath across
// this settings). Same escape hatch `:toolchain-gradle-plugin/build.gradle.kts` already uses for the same
// mismatch, for the same reason: this only relaxes the metadata-format version gate, not
// language/API level compatibility.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

tasks.test {
    systemProperty("tcl.expectedVersion", project.version.toString())
}

// `BuildInfo` reads this resource, so the version is not a literal in the source.
val generateBuildInfo by tasks.registering {
    val tclVersion = project.version.toString()
    val outputDir = layout.buildDirectory.dir("generated/build-info")
    inputs.property("version", tclVersion)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("org/thisisthepy/python/multiplatform/tcl/build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$tclVersion\n")
    }
}
sourceSets.main { resources.srcDir(generateBuildInfo) }

// The native `tcl` binary that the toolchain-lite wheel carries (publish-pypi.yml). Same settings
// as pypackpack's CLI, which this links: io.ktor reaches org.slf4j (slf4j-nop, no I/O at init),
// which GraalVM 21 otherwise refuses to initialize at build time.
graalvmNative {
    binaries {
        named("main") {
            imageName.set("tcl")
            mainClass.set("org.thisisthepy.python.multiplatform.tcl.CliKt")
            buildArgs.addAll(
                "--no-fallback",
                "--install-exit-handlers",
                "--initialize-at-build-time=kotlin,kotlinx.coroutines,io.ktor,kotlinx.io,org.slf4j",
                "-H:+AddAllCharsets",
                "--gc=serial",
            )
            if (System.getProperty("os.name").lowercase().contains("mac")) {
                // Without this the binary's minimum macOS is the build host's.
                buildArgs.add("-H:NativeLinkerOption=-mmacosx-version-min=11.0")
            }
            resources.autodetect()
        }
    }
    // native-image comes from GRAALVM_HOME/JAVA_HOME, not a toolchain Gradle might pick.
    toolchainDetection.set(false)
}
