package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingPlatform
import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformsExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.ProjectFlavorsContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `projectFlavors { }` from `(플러그인예시)build.gradle.kts`, AGP-style (decided 2026-10-03):
 * `projectFlavors { create("free"); create("paid") }` crosses each flavor into the variant graph as
 * `buildPython<Platform><Flavor><BuildType>`, the way AGP names `assemble<Flavor><BuildType>`. A
 * flavor's own dependencies go in a `<flavor>Main` source set. M1 has no per-flavor properties.
 */
class PythonPluginFlavorsTest {
    private fun androidArm64() = PlatformsExtension().apply { androidArm64() }

    private fun flavors(vararg names: String) = ProjectFlavorsContainer().apply { names.forEach { create(it) } }

    @Test
    fun `each flavor is crossed with every platform variant and build type`() {
        val buildTypes = BuildTypesContainer().apply {
            getByName("debug") {}
            getByName("release") {}
        }

        val variants = resolveVariants(androidArm64(), buildTypes, flavors("free", "paid"))

        assertEquals(
            listOf("AndroidArm64FreeDebug", "AndroidArm64FreeRelease", "AndroidArm64PaidDebug", "AndroidArm64PaidRelease"),
            variants.map { it.taskSuffix },
        )
        assertEquals("androidArm64-free-debug", variants.first().dirName)
        assertEquals("free", variants.first().flavorName)
    }

    @Test
    fun `no flavors keeps today's names`() {
        val variants = resolveVariants(androidArm64(), BuildTypesContainer(), ProjectFlavorsContainer())

        assertEquals(listOf("AndroidArm64Debug"), variants.map { it.taskSuffix })
        assertEquals("androidArm64-debug", variants.single().dirName)
        assertNull(variants.single().flavorName)
    }

    @Test
    fun `a flavor declared twice, named like a build type, or not lower-camel is refused`() {
        assertFailsWith<IllegalArgumentException> { flavors("free", "free") }
        assertFailsWith<IllegalArgumentException> { flavors("debug") }
        assertFailsWith<IllegalArgumentException> { flavors("release") }
        assertFailsWith<IllegalArgumentException> { flavors("Free") }
        assertFailsWith<IllegalArgumentException> { flavors("free-tier") }
    }

    @Test
    fun `the active flavor defaults to the first declared, and an undeclared one fails loudly`() {
        val declared = flavors("free", "paid")

        assertEquals("free", resolveActiveFlavor(declared, requestedName = null))
        assertEquals("paid", resolveActiveFlavor(declared, requestedName = "paid"))
        assertNull(resolveActiveFlavor(ProjectFlavorsContainer(), requestedName = null))
        val error = assertFailsWith<IllegalArgumentException> { resolveActiveFlavor(declared, "gold") }
        assertTrue(error.message.orEmpty().contains("free") && error.message.orEmpty().contains("paid"))
    }

    @Test
    fun `staging takes only variants of the active flavor`() {
        val variants = resolveVariants(androidArm64(), BuildTypesContainer(), flavors("free", "paid"))

        val selected = selectStagingVariants(variants, "debug", "aarch64-apple-darwin", activeFlavor = "paid")

        assertEquals("paid", selected[PythonStagingPlatform.ANDROID]?.flavorName)
    }

    @Test
    fun `flavors without a platform variant are refused, because they could change nothing`() {
        assertNotNull(flavorsWithoutVariantsRejection(flavors("free"), variants = emptyList()))
        assertNull(flavorsWithoutVariantsRejection(ProjectFlavorsContainer(), variants = emptyList()))
    }

    @Test
    fun `the plugin registers one task pair per flavor`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.extensions.getByType(PythonExtension::class.java).apply {
            androidArm64()
            projectFlavors {
                create("free")
                create("paid")
            }
        }
        (project as ProjectInternal).evaluate()

        listOf("FreeDebug", "PaidDebug").forEach { suffix ->
            assertNotNull(project.tasks.findByName("buildPythonAndroidArm64$suffix"), suffix)
            assertNotNull(project.tasks.findByName("packagePythonAndroidArm64$suffix"), suffix)
        }
    }
}
