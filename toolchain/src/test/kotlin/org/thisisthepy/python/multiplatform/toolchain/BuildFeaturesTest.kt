package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildFeaturesExtension
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.16 and §1.7: `buildFeatures { metaclass; compose }` and release's
 * `excludeMetaclass`, as the Project-free decisions `PythonPlugin.apply` wires
 * (`PythonPluginBuildFeaturesTest` checks the wiring itself).
 */
class BuildFeaturesTest {
    private val metaDirs = listOf(File("/project/src/commonMain/generated/meta"))

    // ---- metaclass / excludeMetaclass -----------------------------------------------------------

    @Test
    fun `metaclass defaults to true, so declared metaDirs keep being forwarded`() {
        assertTrue(BuildFeaturesExtension().metaclass)
    }

    @Test
    fun `metaclass true keeps metaDirs`() {
        assertEquals(metaDirs, resolveBundledMetaDirs(metaDirs, metaclassFeature = true, excludeMetaclass = false))
    }

    @Test
    fun `metaclass false drops metaDirs`() {
        assertEquals(emptyList(), resolveBundledMetaDirs(metaDirs, metaclassFeature = false, excludeMetaclass = false))
    }

    @Test
    fun `a build type's excludeMetaclass drops metaDirs even with metaclass true`() {
        assertEquals(emptyList(), resolveBundledMetaDirs(metaDirs, metaclassFeature = true, excludeMetaclass = true))
    }

    // ---- compose, Python half ---------------------------------------------------------------------

    @Test
    fun `compose false installs nothing extra and demands no property`() {
        val install = resolveComposePythonInstall(compose = false, pythonxCompose = null, projectDir = File("/project"))
        assertEquals(emptyList(), install.requirements)
        assertNull(install.findLinks)
        assertNull(install.rejection)
    }

    @Test
    fun `compose with a pip requirement installs that requirement`() {
        val install = resolveComposePythonInstall(true, "pythonx-compose==0.1.0", File("/project"))
        assertEquals(listOf("pythonx-compose==0.1.0"), install.requirements)
        assertNull(install.findLinks)
        assertNull(install.rejection)
    }

    @Test
    fun `compose with a directory installs pythonx-compose and adds the directory as find-links`() {
        val projectDir = createTempDirectory("compose-project").toFile()
        val wheels = File(projectDir, "wheels").apply { mkdirs() }

        val absolute = resolveComposePythonInstall(true, wheels.absolutePath, File("/elsewhere"))
        assertEquals(listOf("pythonx-compose"), absolute.requirements)
        assertEquals(wheels.absolutePath, absolute.findLinks)
        assertNull(absolute.rejection)

        val relative = resolveComposePythonInstall(true, "wheels", projectDir)
        assertEquals(wheels.absolutePath, relative.findLinks)

        val uri = resolveComposePythonInstall(true, wheels.toURI().toString(), File("/elsewhere"))
        assertEquals(wheels.absolutePath, uri.findLinks)
    }

    @Test
    fun `compose without the pythonxCompose property is a rejection naming the property and its purpose`() {
        val install = resolveComposePythonInstall(true, null, File("/project"))
        val rejection = assertNotNull(install.rejection)
        assertTrue("python.compose.pythonxCompose" in rejection, rejection)
        assertTrue("pythonx-compose" in rejection && "installPythonDependencies" in rejection, rejection)
        assertEquals(emptyList(), install.requirements)

        assertNotNull(resolveComposePythonInstall(true, "  ", File("/project")).rejection)
    }

    @Test
    fun `a pythonxCompose value that is neither a directory nor a pythonx-compose requirement is rejected`() {
        val rejection = assertNotNull(resolveComposePythonInstall(true, "/no/such/wheels", File("/project")).rejection)
        assertTrue("/no/such/wheels" in rejection, rejection)
        assertNotNull(resolveComposePythonInstall(true, "numpy==2.0", File("/project")).rejection)
        assertNull(resolveComposePythonInstall(true, "PythonX_Compose>=0.1", File("/project")).rejection)
    }

    @Test
    fun `the compose wheel directory is appended to pip's local find-links with a comma`() {
        assertEquals(
            mapOf("find-links" to "/wheels"),
            mergeComposeFindLinks(emptyMap(), "/wheels"),
        )
        assertEquals(
            mapOf("default-index" to "https://x/simple", "find-links" to "/local,/wheels"),
            mergeComposeFindLinks(mapOf("default-index" to "https://x/simple", "find-links" to "/local"), "/wheels"),
        )
        assertEquals(mapOf("find-links" to "/wheels"), mergeComposeFindLinks(mapOf("find-links" to "/wheels"), "/wheels"))
        assertEquals(mapOf("upgrade" to ""), mergeComposeFindLinks(mapOf("upgrade" to ""), null))
    }

    // ---- compose, Kotlin half ---------------------------------------------------------------------

    @Test
    fun `compose with Kotlin Multiplatform adds the configured coordinate`() {
        assertEquals(
            ComposeKotlinDependency.Add("org.thisisthepy:python-multiplatform-compose:0.1.0"),
            resolveComposeKotlinDependency(true, true, "org.thisisthepy:python-multiplatform-compose:0.1.0"),
        )
    }

    @Test
    fun `compose false adds no Kotlin dependency`() {
        assertEquals(ComposeKotlinDependency.None, resolveComposeKotlinDependency(false, true, null))
    }

    @Test
    fun `compose with Kotlin Multiplatform but no kotlinModule property fails naming the property and its purpose`() {
        val error = assertFailsWith<IllegalArgumentException> { resolveComposeKotlinDependency(true, true, null) }
        val message = error.message.orEmpty()
        assertTrue("python.compose.kotlinModule" in message, message)
        assertTrue("python-multiplatform-compose" in message && "commonMain" in message, message)
    }

    @Test
    fun `a kotlinModule that is not group colon artifact colon version is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            resolveComposeKotlinDependency(true, true, "python-multiplatform-compose")
        }
        assertTrue("group:artifact:version" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `compose without Kotlin Multiplatform skips the Kotlin half and demands no coordinate`() {
        val decision = resolveComposeKotlinDependency(true, false, null)
        val skipped = decision as? ComposeKotlinDependency.Skipped
        assertNotNull(skipped, "expected Skipped, got $decision")
        assertTrue("org.jetbrains.kotlin.multiplatform" in skipped.reason, skipped.reason)
    }
}
