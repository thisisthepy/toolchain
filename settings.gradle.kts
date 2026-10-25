enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
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
        gradlePluginPortal()
        mavenLocal {
            content {
                includeGroupAndSubgroups("org.thisisthepy")
                excludeGroupAndSubgroups("org.thisisthepy.compose")
                excludeGroupAndSubgroups("org.thisisthepy.kotlin")
            }
        }
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
        // Last, and only for our own group (toolchain#97): packpack comes from Maven Central once it is
        // published there, and from mavenLocal only while a version is not (an unreleased pypackpack, built
        // with `./gradlew :packpack:publishToMavenLocal`). Bump packpack's version when developing against an
        // unreleased one, or Central's copy of the same version wins.
        mavenLocal {
            content {
                includeGroupAndSubgroups("org.thisisthepy")
                excludeGroupAndSubgroups("org.thisisthepy.compose")
                excludeGroupAndSubgroups("org.thisisthepy.kotlin")
            }
        }
    }
}

rootProject.name = "ToolchainProject"

include(":toolchain")
// usage-example/ is its own build, run with this wrapper: ./gradlew -p usage-example …. It uses python-multiplatform's
// Kotlin; the plugin here stays on its own for its consumers (toolchain#22, #62).
