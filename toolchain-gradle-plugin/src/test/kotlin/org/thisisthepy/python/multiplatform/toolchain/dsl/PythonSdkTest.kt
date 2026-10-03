package org.thisisthepy.python.multiplatform.toolchain.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `compileSdk` takes either form `(플러그인예시)build.gradle.kts` writes:
 *
 * - a string, `compileSdk = "3.11.9-alpha"`: any version. One the release server does not publish
 *   would be built automatically (INTENT §4.1), which is not available yet, so it is refused, inside
 *   the bundling task.
 * - a named constant, `compileSdk = PY3_13_0`: only versions the server publishes exist as constants.
 *
 * In a build script both are plain `=` (Gradle's Kotlin assignment overloading); plain Kotlin calls
 * `assign` directly.
 */
class PythonSdkTest {
    @Test
    fun `nothing assigned resolves to nothing`() {
        assertNull(resolvePythonSdk(PythonSdk()))
    }

    @Test
    fun `a named constant is a published version`() {
        val sdk = PythonSdk().apply { assign(PythonSdkVersion.PY3_13_0) }

        val resolved = resolvePythonSdk(sdk)!!

        assertEquals("3.13.0", resolved.version.toString())
        assertTrue(resolved.fromConstant)
        assertNull(resolved.rejection)
    }

    @Test
    fun `every constant names a version the release server publishes`() {
        PythonSdkVersion.values().forEach { constant ->
            assertTrue(constant.version in PythonReleaseServer.PUBLISHED, "$constant -> ${constant.version}")
        }
    }

    @Test
    fun `python-multiplatform's pinned 3_14_7 is a constant`() {
        val resolved = resolvePythonSdk(PythonSdk().apply { assign(PythonSdkVersion.PY3_14_7) })!!

        assertEquals("3.14.7", resolved.version.toString())
        assertNull(resolved.rejection)
    }

    @Test
    fun `a string naming a published version is accepted, X dot Y included`() {
        listOf("3.13.0", "3.13").forEach { raw ->
            val resolved = resolvePythonSdk(PythonSdk().apply { assign(raw) })!!

            assertEquals("3.13.0", resolved.version.toReleaseString(), raw)
            assertNull(resolved.rejection, raw)
        }
    }

    @Test
    fun `a string the server does not publish is refused because auto-build is not available yet`() {
        val resolved = resolvePythonSdk(PythonSdk().apply { assign("3.11.9-alpha") })!!

        val rejection = resolved.rejection.orEmpty()
        assertTrue(rejection.contains("3.11.9-alpha"), rejection)
        assertTrue(rejection.contains("3.14.7") && rejection.contains("3.13.0"), "should list what is published: $rejection")
        assertTrue(rejection.contains("build", ignoreCase = true), rejection)
    }

    @Test
    fun `the last assignment wins, whichever form it uses`() {
        val sdk = PythonSdk().apply {
            assign(PythonSdkVersion.PY3_13_0)
            assign("3.11.9-alpha")
        }

        assertEquals(false, resolvePythonSdk(sdk)!!.fromConstant)
    }

    @Test
    fun `a malformed string still fails configuration`() {
        assertFailsWith<IllegalArgumentException> { resolvePythonSdk(PythonSdk().apply { assign("three") }) }
    }
}
