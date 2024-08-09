plugins {
    kotlin("jvm")
}

group = "io.github.thisisthepy.toolchain"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
