package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.testfixtures.ProjectBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * python-multiplatform's embedded `pythonVersion`, as a published dependency states it (#49):
 * the module-metadata variant attribute `org.thisisthepy.python.version`, else the jar resource
 * `META-INF/python-multiplatform/python.properties` (python-multiplatform#61).
 */
class PublishedPythonVersionTest {
    @Test
    fun `python properties text gives pythonVersion, blank or absent gives null`() {
        assertEquals("3.14.7", parsePythonProperties("pythonVersion=3.14.7\nfreeThreaded=false\n"))
        assertNull(parsePythonProperties("freeThreaded=false\n"))
        assertNull(parsePythonProperties("pythonVersion=  \n"))
    }

    @Test
    fun `the resource is read from a jar and from an AAR's classes jar`() {
        val dir = createTempDirectory("pm-archives").toFile()
        val jar = File(dir, "pm.jar").apply { writeBytes(zipOf(PYTHON_PROPERTIES_RESOURCE to "pythonVersion=3.14.7\n")) }
        val aar = File(dir, "pm.aar").apply { writeBytes(zipOf("classes.jar" to jar.readBytes())) }
        val other = File(dir, "other.jar").apply { writeBytes(zipOf("a.txt" to "x")) }

        assertEquals("3.14.7", readPythonPropertiesFromArchive(jar))
        assertEquals("3.14.7", readPythonPropertiesFromArchive(aar))
        assertNull(readPythonPropertiesFromArchive(other))
        assertNull(readPythonPropertiesFromArchive(File(dir, "missing.jar")))
    }

    @Test
    fun `only python-multiplatform runtime modules count`() {
        assertTrue(isPythonMultiplatformModule("io.github.thisisthepy", "python-multiplatform"))
        assertTrue(isPythonMultiplatformModule("io.github.thisisthepy", "python-multiplatform-desktop"))
        assertTrue(isPythonMultiplatformModule("io.github.thisisthepy", "python-multiplatform-android"))
        assertFalse(isPythonMultiplatformModule("io.github.thisisthepy", "python-multiplatform-gradle-plugin"))
        assertFalse(isPythonMultiplatformModule("io.github.thisisthepy", "python-multiplatform-ksp"))
        assertFalse(isPythonMultiplatformModule("org.example", "python-multiplatform"))
    }

    @Test
    fun `the published source is consulted only when the property and the extension say nothing`() {
        var calls = 0
        val published = { calls++; "3.13.0" }

        assertEquals("3.14.7", selectPythonMultiplatformVersion("3.14.7", null, published))
        assertEquals("3.14.7", selectPythonMultiplatformVersion(null, "3.14.7", published))
        assertEquals(0, calls)
        assertEquals("3.13.0", selectPythonMultiplatformVersion(null, null, published))
        assertEquals(1, calls)
        assertNull(selectPythonMultiplatformVersion("", " ") { null })
    }

    @Test
    fun `a published dependency's module metadata attribute states the version`() {
        val repo = createTempDirectory("pm-repo").toFile()
        publishFakeModule(repo, "python-multiplatform-desktop", attributeVersion = "3.14.7", resourceVersion = null)

        assertEquals("3.14.7", readPublishedPythonMultiplatformVersion(consumer(repo, "python-multiplatform-desktop")))
    }

    @Test
    fun `without the attribute the jar resource states it`() {
        val repo = createTempDirectory("pm-repo").toFile()
        publishFakeModule(repo, "python-multiplatform-desktop", attributeVersion = null, resourceVersion = "3.14.7")

        assertEquals("3.14.7", readPublishedPythonMultiplatformVersion(consumer(repo, "python-multiplatform-desktop")))
    }

    @Test
    fun `no python-multiplatform dependency means nothing is known`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")

        assertNull(readPublishedPythonMultiplatformVersion(project))
    }

    private fun consumer(
        repo: File,
        module: String,
    ) = ProjectBuilder.builder().build().also { project ->
        project.pluginManager.apply("java")
        project.repositories.maven { setUrl(repo.toURI()) }
        project.dependencies.add("implementation", "$PYTHON_MULTIPLATFORM_GROUP:$module:$VERSION")
    }

    /**
     * A minimal Maven layout for [module]: a jar (with [resourceVersion] in python.properties when
     * given), a pom, and -- when [attributeVersion] is given -- Gradle module metadata whose runtime
     * variant carries `org.thisisthepy.python.version`. Without metadata, Gradle uses the pom.
     */
    private fun publishFakeModule(
        repo: File,
        module: String,
        attributeVersion: String?,
        resourceVersion: String?,
    ) {
        val dir = File(repo, "${PYTHON_MULTIPLATFORM_GROUP.replace('.', '/')}/$module/$VERSION").apply { mkdirs() }
        val jarName = "$module-$VERSION.jar"
        val jarBytes =
            if (resourceVersion != null) {
                zipOf(PYTHON_PROPERTIES_RESOURCE to "pythonVersion=$resourceVersion\nfreeThreaded=false\n")
            } else {
                zipOf("placeholder.txt" to "x")
            }
        File(dir, jarName).writeBytes(jarBytes)
        val metadataMarker = if (attributeVersion != null) "  <!-- do_not_remove: published-with-gradle-metadata -->\n" else ""
        File(dir, "$module-$VERSION.pom").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project xmlns="http://maven.apache.org/POM/4.0.0">
            |$metadataMarker  <modelVersion>4.0.0</modelVersion>
            |  <groupId>$PYTHON_MULTIPLATFORM_GROUP</groupId>
            |  <artifactId>$module</artifactId>
            |  <version>$VERSION</version>
            |</project>
            """.trimMargin(),
        )
        if (attributeVersion == null) return
        File(dir, "$module-$VERSION.module").writeText(
            """
            {
              "formatVersion": "1.1",
              "component": { "group": "$PYTHON_MULTIPLATFORM_GROUP", "module": "$module", "version": "$VERSION" },
              "createdBy": { "gradle": { "version": "8.9" } },
              "variants": [
                {
                  "name": "runtimeElements",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.dependency.bundling": "external",
                    "org.gradle.libraryelements": "jar",
                    "org.gradle.usage": "java-runtime",
                    "$PYTHON_VERSION_ATTRIBUTE": "$attributeVersion"
                  },
                  "files": [
                    {
                      "name": "$jarName",
                      "url": "$jarName",
                      "size": ${jarBytes.size},
                      "sha1": "${digest("SHA-1", jarBytes)}",
                      "md5": "${digest("MD5", jarBytes)}"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
    }

    private fun zipOf(vararg entries: Pair<String, Any>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(if (content is ByteArray) content else content.toString().toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun digest(
        algorithm: String,
        bytes: ByteArray,
    ): String = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val VERSION = "3.14.7-alpha01"
    }
}
