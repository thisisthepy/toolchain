package org.thisisthepy.python.multiplatform.toolchain.dsl

/**
 * `projectFlavors { create("free"); create("paid") }`: `(플러그인예시)build.gradle.kts` declares the
 * block (empty); its meaning follows AGP's product flavors (decided 2026-10-03). Each flavor is
 * crossed into the variant graph between platform and build type
 * (`buildPythonAndroidArm64FreeDebug`), and its own dependencies go in a `<flavor>Main` source set.
 * There are no per-flavor properties yet.
 *
 * A flavor name is lower-camel (it becomes part of a task name and a directory), unique, and not a
 * build type's name, which would make `<platform><flavor><buildType>` ambiguous.
 */
open class ProjectFlavorsContainer {
    private val flavors = linkedMapOf<String, ProjectFlavor>()

    fun create(name: String, action: ProjectFlavor.() -> Unit = {}): ProjectFlavor {
        require(NAME.matches(name)) {
            "Python project flavor name '$name' must be lower-camel (letters and digits, starting lower-case)."
        }
        require(name !in BUILD_TYPE_NAMES) {
            "Python project flavor '$name' has a build type's name; flavor and build type would be ambiguous."
        }
        require(name !in flavors) { "Python project flavor '$name' is declared twice." }
        return ProjectFlavor(name).apply(action).also { flavors[name] = it }
    }

    fun getByName(name: String): ProjectFlavor =
        flavors[name] ?: throw IllegalArgumentException(
            "Unknown Python project flavor '$name'. Declared: ${flavors.keys.joinToString(", ")}",
        )

    fun all(): List<ProjectFlavor> = flavors.values.toList()

    private companion object {
        val NAME = Regex("^[a-z][A-Za-z0-9]*$")
        val BUILD_TYPE_NAMES = setOf("debug", "release")
    }
}

open class ProjectFlavor(val name: String)
