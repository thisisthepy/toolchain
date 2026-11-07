// usage-example/ is its own Gradle build (toolchain#22, #62), run with the repository's wrapper:
// ./gradlew -p usage-example …. It consumes python-multiplatform, which is built with Kotlin 2.4, so it uses
// that toolchain (gradle/libs.versions.toml here); the plugin in ../toolchain stays on
// its own Kotlin for its consumers. python-multiplatform (library, KSP processor, bindings plugin and
// the typedpython-gate zip) resolves from Maven Central only: 3.15.0-alpha03 is its first Central
// release, so this build resolves once that upload is published. Only the toolchain plugin under test
// and packpack (group org.thisisthepy.python.multiplatform) come from mavenLocal, checked first, so the
// build exercises this checkout's plugin (`./gradlew :toolchain:publishToMavenLocal`; AGENTS.md §15-16)
// even after a version of it is on Central.
pluginManagement {
    repositories {
        mavenLocal {
            content {
                includeGroup("org.thisisthepy.python.multiplatform")
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral {
            // `org.thisisthepy.compose.*:1.11.1-ext.2` POMs on Maven Central omit Gradle's
            // `published-with-gradle-metadata` marker comment, so Gradle must be told to read `.module`
            // metadata alongside `.pom` to resolve KMP platform variants and plugin runtime elements.
            metadataSources {
                gradleMetadata()
                mavenPom()
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral {
            // `org.thisisthepy.compose.*:1.11.1-ext.2` POMs on Maven Central omit Gradle's
            // `published-with-gradle-metadata` marker comment, so Gradle must be told to read `.module`
            // metadata alongside `.pom` to resolve KMP platform variants and plugin runtime elements.
            metadataSources {
                gradleMetadata()
                mavenPom()
            }
        }
    }
}

rootProject.name = "usage-example"
