package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetsExtension
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #35 / docs/SPEC.md §1.9: `val commonMain by getting { ... }` applies its block at
 * declaration, as Gradle's `getting { }` does.
 *
 * Why these tests fail without the fix: Kotlin calls a delegate's `getValue` only when the property
 * is read, and the old `AutoSourceSetDelegateWithConfig` applied the block inside `getValue`. Every
 * test below declares the property and never reads it, so before the fix the block never ran,
 * `getByName("commonMain")` was an empty config and `collectInstallDependencies` returned nothing.
 */
class SourceSetGettingDelegateTest {
    @Test
    fun `by getting with a block is applied at declaration without reading the property`() {
        val ext = SourceSetsExtension().apply {
            @Suppress("UNUSED_VARIABLE")
            val commonMain by getting {
                srcDirs("src/commonMain/python")
                metaDirs("m")
                libDirs("l")
                dependencies { implementation("pyzmq"); integration("pycomposeui") }
            }
        }

        val config = ext.getByName("commonMain")
        assertEquals(listOf("src/commonMain/python"), config.srcDirs)
        assertEquals(listOf("m"), config.metaDirs)
        assertEquals(listOf("l"), config.libDirs)
        assertEquals(listOf("pyzmq"), config.dependencies.implementations)
        assertEquals(listOf("pycomposeui"), config.dependencies.integrations)
        assertTrue("pyzmq" in collectInstallDependencies(ext.allSourceSets()))
    }

    @Test
    fun `the block is applied through the plugin's python sourceSets extension`() {
        val project = ProjectBuilder.builder()
            .withProjectDir(createTempDirectory("source-set-getting").toFile())
            .build()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        val python = project.extensions.getByName("python") as PythonExtension

        python.sourceSets {
            @Suppress("UNUSED_VARIABLE")
            val commonMain by getting {
                srcDirs("src/commonMain/python")
                dependencies { implementation("pyzmq") }
            }
        }

        val config = python.sourceSets.getByName("commonMain")
        assertEquals(listOf("src/commonMain/python"), config.srcDirs)
        assertTrue("pyzmq" in collectInstallDependencies(python.sourceSets.allSourceSets()))
    }

    @Test
    fun `a non-commonMain source set gets its dependencies at declaration`() {
        val ext = SourceSetsExtension().apply {
            @Suppress("UNUSED_VARIABLE")
            val androidMain by getting { dependencies { implementation("numpy") } }
        }

        assertEquals(listOf("numpy"), ext.getByName("androidMain").dependencies.implementations)
        assertTrue("numpy" in collectInstallDependencies(ext.allSourceSets()))
    }

    @Test
    fun `plain by getting registers the source set at declaration`() {
        val ext = SourceSetsExtension().apply {
            @Suppress("UNUSED_VARIABLE")
            val iosMain by getting
        }

        assertEquals(listOf("iosMain"), ext.allSourceSets().map { it.name })
    }

    @Test
    fun `srcDirs on a non-commonMain set throws at declaration`() {
        assertFailsWith<IllegalStateException> {
            SourceSetsExtension().apply {
                @Suppress("UNUSED_VARIABLE")
                val androidMain by getting { srcDirs("x") }
            }
        }
    }

    @Test
    fun `the block runs once even when the property is read`() {
        val ext = SourceSetsExtension().apply {
            val commonMain by getting { srcDirs("a") }
            commonMain.srcDirs
            commonMain.srcDirs
        }

        assertEquals(listOf("a"), ext.getByName("commonMain").srcDirs)
    }
}
