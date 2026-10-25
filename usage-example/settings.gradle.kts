// usage-example/ is its own Gradle build (toolchain#22, #62), run with the repository's wrapper:
// ./gradlew -p usage-example …. It consumes python-multiplatform, which is built with Kotlin 2.4, so it uses
// that toolchain (gradle/libs.versions.toml here); the plugin in ../toolchain stays on
// its own Kotlin for its consumers. Both plugins and the
// python-multiplatform library come from mavenLocal: publish them first (see AGENTS.md §16).
pluginManagement {
    repositories {
        mavenLocal {
            content {
                excludeGroupAndSubgroups("org.thisisthepy.compose")
                excludeGroupAndSubgroups("org.thisisthepy.kotlin")
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
        mavenLocal {
            content {
                excludeGroupAndSubgroups("org.thisisthepy.compose")
                excludeGroupAndSubgroups("org.thisisthepy.kotlin")
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
    }
}

rootProject.name = "usage-example"
