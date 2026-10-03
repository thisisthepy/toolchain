package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.HotReloadExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.PackagingExtension
import org.thisisthepy.python.multiplatform.toolchain.hotreload.validateHotReloadConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for `python { packaging { hotReload { ... } } }` DSL validation.
 *
 * The DSL (`DSLPackaging.kt`) has carried `HotReloadExtension` fields since the first commit but
 * nothing in `PythonPlugin.apply` ever read them. A user declaring `hotReload { serverHost = "..." }`
 * got silence instead of an active task or an error.
 *
 * [validateHotReloadConfig] is factored out of `PythonPlugin.apply`'s `afterEvaluate` the same way
 * [resolveActiveBuildType] and [resolveBuildLevel] were -- a pure function testable without a Gradle
 * [org.gradle.api.Project].
 *
 * Boundary:
 *  - toolchain does: file-watch, adb push / local copy, trigger signal, DSL validation.
 *  - toolchain does NOT do: serve bundles (no server), reload running Python (python-multiplatform's
 *    runtime job), or act on serverHost until a server component exists.
 */
class PythonPluginHotReloadTest {

    @Test
    fun `disabled hotReload passes validation with empty serverHost`() {
        val ext = HotReloadExtension()
        val config = validateHotReloadConfig(enabled = false, ext)
        assertEquals(false, config.enabled)
    }

    @Test
    fun `enabled hotReload with blank serverHost fails with a clear message`() {
        val ext = HotReloadExtension().apply { serverHost = "" }
        val error = assertFailsWith<IllegalArgumentException> {
            validateHotReloadConfig(enabled = true, ext)
        }
        assertEquals(
            "python { buildTypes { ... } { enableHotReload = true } } requires " +
                "python { packaging { hotReload { serverHost = \"...\" } } } to be set.",
            error.message,
        )
    }

    @Test
    fun `enabled hotReload with valid serverHost passes`() {
        val ext = HotReloadExtension().apply { serverHost = "https://localhost:8080" }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals("https://localhost:8080", config.serverHost)
        assertEquals(true, config.enabled)
    }

    @Test
    fun `hotReload cert with both keyStore and autoGenerate fails`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            cert {
                keyStore = "path/to/keystore.jks"
                autoGenerate = true
            }
        }
        val error = assertFailsWith<IllegalArgumentException> {
            validateHotReloadConfig(enabled = true, ext)
        }
        assertEquals(
            "python { packaging { hotReload { cert { } } } }: " +
                "keyStore and autoGenerate are mutually exclusive; set only one.",
            error.message,
        )
    }

    @Test
    fun `hotReload cert with only autoGenerate passes`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            cert { autoGenerate = true }
        }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals(true, config.cert.autoGenerate)
        assertEquals(null, config.cert.keyStore)
    }

    @Test
    fun `hotReload cert with only keyStore passes`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            cert { keyStore = "path/to/keystore.jks" }
        }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals("path/to/keystore.jks", config.cert.keyStore)
        assertEquals(false, config.cert.autoGenerate)
    }

    @Test
    fun `redirectErrorStream is carried through to the config`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            redirectErrorStream = true
        }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals(true, config.redirectErrorStream)
    }

    @Test
    fun `packaging with no hotReload block leaves enabled false`() {
        val packaging = PackagingExtension()
        val config = validateHotReloadConfig(enabled = false, packaging.hotReload)
        assertEquals(false, config.enabled)
    }
}
