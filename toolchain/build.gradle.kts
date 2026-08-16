group = rootProject.group
version = rootProject.version


plugins {
    `kotlin-dsl`
    `maven-publish`
    `java-gradle-plugin`
}

dependencies {
    //implementation(gradleApi())
    //implementation(localGroovy())
    //implementation("org.jetbrains.kotlin:kotlin-stdlib")

    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.5.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.ow2.asm:asm-util:9.4")
    implementation("org.ow2.asm:asm:9.4")
    // Needed to call `pypackpack`'s `dependency.backend.BaseInterface` (`suspend fun
    // addDependencies`) from `InstallDependenciesTask` via `runBlocking`. `packpack` depends on the
    // same artifact itself (`pypackpack/gradle/libs.versions.toml`), but as `implementation`, so it
    // is not on this module's compile classpath transitively -- declared explicitly here at the same
    // version to avoid a runtime-classpath-only mismatch.
    implementation(libs.kotlinx.coroutines.core)

    // `toolchain` translates the Gradle DSL into `pypackpack` middleware calls -- it owns none of
    // the packaging/dependency-resolution/bundling logic itself (docs/ecosystem.md §1, §5). This is
    // resolved through `mavenLocal()` (declared in the root `settings.gradle.kts`
    // `pluginManagement`/`dependencyResolutionManagement` repositories), which requires
    // `pypackpack`'s `packpack/build.gradle.kts` to have run `publishToMavenLocal` at least once.
    implementation("org.thisisthepy.python.multiplatform:packpack:0.1.0")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

gradlePlugin {
    plugins {
        create("toolchain") {
            id = group as String
            implementationClass = "$id.toolchain.PythonPlugin"
        }
    }
}

// `pypackpack` is built with Kotlin 2.3.0 (`pypackpack/gradle/libs.versions.toml`); this module's
// own `compileKotlin` resolves to Kotlin 2.1.0's compiler (whichever Kotlin Gradle Plugin version
// wins the shared plugin classpath across this settings -- `gradlew -v` reports the Gradle
// distribution's embedded 1.9.23, which is a different number and not what actually compiles
// `src/main/kotlin` here). A 2.1.0 reader rejects 2.3.0 metadata outright:
// "Module was compiled with an incompatible version of Kotlin. The binary version of its metadata
// is 2.3.0, expected version is 2.1.0." `-Xskip-metadata-version-check` is the targeted escape
// hatch for that specific check; it does not relax language/API level compatibility, only the
// metadata-format version gate, and the surface used from this module (data classes, a factory
// function, an enum) predates 2.1 either way. Bumping this whole build to Kotlin 2.3.0 instead was
// rejected as out of scope -- `compose-multiplatform 1.7.0` (`gradle/libs.versions.toml`) is not
// verified against it, and that is exactly the kind of build-wide change a single delegation should
// not carry.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}
