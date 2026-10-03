group = rootProject.group
version = rootProject.version

plugins {
    // No explicit version: `org.jetbrains.kotlin.jvm` is already on this settings' shared plugin
    // classpath (pulled in transitively by the other subprojects' Kotlin Multiplatform/`kotlin-dsl`
    // plugins), and Gradle rejects a second, explicitly-versioned request for a plugin ID it has
    // already resolved ("plugin is already on the classpath with an unknown version").
    id("org.jetbrains.kotlin.jvm")
    application
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
