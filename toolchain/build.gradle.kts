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
    // Pinned to the same version as the root catalog's `kotlin` entry (`gradle/libs.versions.toml`),
    // not left as an independent literal. A skewed pin here (previously `2.0.0` against a `2.1.0`
    // root) put two different `kotlin-gradle-plugin` versions on the plugin classpath any consumer
    // sees once it applies both this plugin and `org.jetbrains.kotlin.multiplatform` in the same
    // `plugins {}` block (as `usage-example/build.gradle.kts` does). Gradle does not merge/conflict
    // -resolve those into one: `usage-example/build.gradle.kts` compiled its `KotlinWebpackConfig
    // .DevServer()` call (browser { commonWebpackConfig { ... } }) against 2.0.0's shape (`proxy:
    // Map<String, Any>`), while the actually-applied multiplatform plugin (2.1.0, `proxy:
    // List<Proxy>` as of that release) drove `wasmJsBrowserTest` task creation at runtime --
    // `NoSuchMethodError` on `DevServer.<init>` (root `./gradlew build` failure, reproduced via
    // `ANDROID_HOME=... ./gradlew build --stacktrace`; confirmed by decompiling both
    // `kotlin-gradle-plugin-{2.0.0,2.1.0}.jar`'s `KotlinWebpackConfig$DevServer.class` with `javap`).
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
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
    // Test-only: `PythonPluginAttachmentTest` applies `com.android.application` to a ProjectBuilder
    // project to check that the staged Android root really lands in `android.sourceSets.main.assets`
    // (the plugin reaches that object reflectively, so only a real AGP proves the method chain).
    // Same version as `usage-example` (`gradle/libs.versions.toml` `agp`); never on the plugin's
    // own runtime classpath.
    testImplementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
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
// `TypedpythonCheckTaskTest` installs the real `typedpython` gate, which is not on PyPI yet:
// `-Ptypedpython.wheelDir=<dir>` names the directory holding its wheel. Forwarded as-is; the test
// fails with that instruction when it is missing rather than skipping.
tasks.test {
    systemProperty("typedpython.wheelDir", providers.gradleProperty("typedpython.wheelDir").getOrElse(""))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

// `java-gradle-plugin` (applied above) already registers a `pluginMaven` publication `from(
// components["java"])` for every `gradlePlugin { plugins { ... } }` entry, plus that plugin's
// marker publication. A hand-written `mavenJava` publication doing the same `from(components
// ["java"])` used to sit here too -- both published to the exact same coordinates (`group`/
// `artifactId` default to the project's, and neither publication overrode them), so `publish
// ToMavenLocal` published `mavenJava` then `pluginMaven` over it and warned "will overwrite each
// other". Nothing on the consumer side ever resolved `mavenJava` by name -- consumers apply the
// plugin id (resolved via the marker) and get the jar through `pluginMaven` -- so it was pure
// duplication, not an intentional second artifact. Removed rather than re-coordinated: there was
// no second artifact to keep.
publishing {
    repositories {
        mavenLocal()
    }
}
