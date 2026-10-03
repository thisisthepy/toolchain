package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.CodePushExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.PackagingExtension
import org.thisisthepy.python.multiplatform.toolchain.hotreload.validateCodePushConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for `python { packaging { codePush { ... } } }` DSL validation.
 *
 * The DSL carries `serverHost`, `cert { keyStore }`, and `uploadConfig { forceUpload, login { ... } }`,
 * but no task has ever consumed them. A user enabling `enableCodePush = true` in a release build
 * type got no error and no upload task.
 *
 * [validateCodePushConfig] is the same explicit-validation shape as [validateHotReloadConfig], so
 * the same test conventions apply.
 *
 * Boundary -- what this repository CANNOT do:
 *  - There is no server. `serverHost` is a URL the user points at something they run themselves.
 *    This plugin validates the field is non-blank when codePush is enabled, but it does not
 *    implement an upload protocol. A `codePushPython` task is registered and reports exactly this
 *    when it runs: "serverHost is set but no upload client is implemented; provide a server that
 *    accepts bundles and implement a client, or use an existing service."
 *  - `uploadConfig.login.id/password` would be credentials for that server. They are validated
 *    (non-blank when serverHost is set) but not sent anywhere.
 */
class PythonPluginCodePushTest {

    @Test
    fun `disabled codePush passes validation with empty serverHost`() {
        val ext = CodePushExtension()
        val config = validateCodePushConfig(enabled = false, ext)
        assertEquals(false, config.enabled)
    }

    @Test
    fun `enabled codePush with blank serverHost fails with a clear message`() {
        val ext = CodePushExtension().apply { serverHost = "" }
        val error = assertFailsWith<IllegalArgumentException> {
            validateCodePushConfig(enabled = true, ext)
        }
        assertEquals(
            "python { buildTypes { ... } { enableCodePush = true } } requires " +
                "python { packaging { codePush { serverHost = \"...\" } } } to be set. " +
                "Note: no upload client is implemented; a server and client are needed to use codePush.",
            error.message,
        )
    }

    @Test
    fun `enabled codePush with valid serverHost passes`() {
        val ext = CodePushExtension().apply { serverHost = "https://cdn.example.com:8080" }
        val config = validateCodePushConfig(enabled = true, ext)
        assertEquals("https://cdn.example.com:8080", config.serverHost)
        assertEquals(true, config.enabled)
    }

    @Test
    fun `codePush cert with both keyStore and autoGenerate fails`() {
        val ext = CodePushExtension().apply {
            serverHost = "https://cdn.example.com:8080"
            cert {
                keyStore = "path/to/keystore.jks"
                autoGenerate = true
            }
        }
        val error = assertFailsWith<IllegalArgumentException> {
            validateCodePushConfig(enabled = true, ext)
        }
        assertEquals(
            "python { packaging { codePush { cert { } } } }: " +
                "keyStore and autoGenerate are mutually exclusive; set only one.",
            error.message,
        )
    }

    @Test
    fun `codePush forceUpload is carried to config`() {
        val ext = CodePushExtension().apply {
            serverHost = "https://cdn.example.com:8080"
            uploadConfig { forceUpload = true }
        }
        val config = validateCodePushConfig(enabled = true, ext)
        assertEquals(true, config.forceUpload)
    }

    @Test
    fun `codePush login credentials are carried to config`() {
        val ext = CodePushExtension().apply {
            serverHost = "https://cdn.example.com:8080"
            uploadConfig {
                login {
                    id = "user@example.com"
                    password = "s3cr3t"
                }
            }
        }
        val config = validateCodePushConfig(enabled = true, ext)
        assertEquals("user@example.com", config.loginId)
        assertEquals("s3cr3t", config.loginPassword)
    }

    @Test
    fun `packaging with no codePush block leaves enabled false`() {
        val packaging = PackagingExtension()
        val config = validateCodePushConfig(enabled = false, packaging.codePush)
        assertEquals(false, config.enabled)
    }
}
