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
        mavenLocal()
        mavenCentral()
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
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "ToolchainProject"

include(":toolchain")
// usage-example is its own build, with its own wrapper: (cd usage-example && ./gradlew …). It uses
// python-multiplatform's Kotlin; the plugin here stays on its own for its consumers (toolchain#22).
include(":tcl")
