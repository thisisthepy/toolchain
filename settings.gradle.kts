plugins {
    id("org.gradle.toolchains.foojay-resolver-convention").version("0.8.0")
}

rootProject.name = "pmm-toolchain"

include("toolchain-test:script-definition")
include("toolchain-test:host")
include("toolchain")
