plugins {
    id("org.gradle.toolchains.foojay-resolver-convention").version("0.8.0")
}

rootProject.name = "toolchain"

include("instance:script-definition")
include("instance:host")
