package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import java.io.File
import java.io.StringReader
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** python-multiplatform's Maven group (thisisthepy/python-multiplatform#61). */
const val PYTHON_MULTIPLATFORM_GROUP = "io.github.thisisthepy"

/** Every python-multiplatform runtime module's name starts with this. */
const val PYTHON_MULTIPLATFORM_MODULE_PREFIX = "python-multiplatform"

/** The module-metadata variant attribute carrying the embedded `pythonVersion` ("3.14.7"). */
const val PYTHON_VERSION_ATTRIBUTE = "org.thisisthepy.python.version"

/** The resource in python-multiplatform's jar (and an AAR's classes.jar) with `pythonVersion=`. */
const val PYTHON_PROPERTIES_RESOURCE = "META-INF/python-multiplatform/python.properties"

/** Modules with the prefix that are not the runtime a consumer embeds. */
private val NON_RUNTIME_MODULES =
    setOf(
        "python-multiplatform-gradle-plugin",
        "python-multiplatform-ksp",
        "python-multiplatform-wasm-runtime",
    )

/** Whether `group:name` is a python-multiplatform runtime module (any platform's). */
fun isPythonMultiplatformModule(
    group: String?,
    name: String,
): Boolean = group == PYTHON_MULTIPLATFORM_GROUP && name.startsWith(PYTHON_MULTIPLATFORM_MODULE_PREFIX) && name !in NON_RUNTIME_MODULES

/** `pythonVersion` from a `python.properties` text, or `null` when absent or blank. */
fun parsePythonProperties(text: String): String? =
    Properties().apply { load(StringReader(text)) }.getProperty("pythonVersion")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * `pythonVersion` from [archive]'s [PYTHON_PROPERTIES_RESOURCE]: directly in a jar, or inside an
 * AAR's `classes.jar`. `null` when the archive has none or cannot be read.
 */
fun readPythonPropertiesFromArchive(archive: File): String? =
    runCatching {
        ZipFile(archive).use { zip ->
            zip.getEntry(PYTHON_PROPERTIES_RESOURCE)?.let { entry ->
                return@use parsePythonProperties(zip.getInputStream(entry).bufferedReader().readText())
            }
            val classesJar = zip.getEntry("classes.jar") ?: return@use null
            ZipInputStream(zip.getInputStream(classesJar)).use { inner ->
                generateSequence { inner.nextEntry }
                    .firstOrNull { it.name == PYTHON_PROPERTIES_RESOURCE }
                    ?.let { parsePythonProperties(inner.bufferedReader().readText()) }
            }
        }
    }.getOrNull()

/**
 * python-multiplatform's embedded `pythonVersion` as a **published** dependency states it, for a consumer
 * that has no `:python-multiplatform` project in its build (docs/SPEC.md §1.12, #49).
 *
 * Looks at the project's resolvable runtime classpaths that declare a python-multiplatform module,
 * in name order. For each it reads the [PYTHON_VERSION_ATTRIBUTE] of the resolved variant (module
 * metadata), then falls back to [PYTHON_PROPERTIES_RESOURCE] in the resolved artifact. `null` when no
 * classpath declares python-multiplatform or neither source says. Resolution failures are not
 * errors here: the check is skipped, as when nothing is known.
 *
 * This resolves a configuration, so callers invoke it lazily: only when a version check is needed
 * and the property and the same-build extension gave none.
 */
fun readPublishedPythonMultiplatformVersion(project: Project): String? =
    project.configurations
        .filter { configuration ->
            configuration.isCanBeResolved &&
                (configuration.name == "runtimeClasspath" || configuration.name.endsWith("RuntimeClasspath")) &&
                configuration.allDependencies.any { isPythonMultiplatformModule(it.group, it.name) }
        }.sortedBy { it.name }
        .firstNotNullOfOrNull { configuration ->
            runCatching { versionFromVariantAttribute(configuration) ?: versionFromArtifact(configuration) }.getOrNull()
        }

private fun versionFromVariantAttribute(configuration: Configuration): String? =
    configuration.incoming.resolutionResult.allComponents
        .asSequence()
        .filter { component ->
            val id = component.moduleVersion
            id != null && isPythonMultiplatformModule(id.group, id.name)
        }.flatMap { it.variants.asSequence() }
        .mapNotNull { variant ->
            val key = variant.attributes.keySet().firstOrNull { it.name == PYTHON_VERSION_ATTRIBUTE } ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            variant.attributes.getAttribute(key as Attribute<Any>)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.firstOrNull()

private fun versionFromArtifact(configuration: Configuration): String? =
    configuration.incoming
        .artifactView { lenient(true) }
        .artifacts.artifacts
        .asSequence()
        .filter { artifact ->
            val id = artifact.id.componentIdentifier
            id is ModuleComponentIdentifier && isPythonMultiplatformModule(id.group, id.module)
        }.mapNotNull { readPythonPropertiesFromArchive(it.file) }
        .firstOrNull()
