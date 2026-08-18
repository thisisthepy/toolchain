package org.thisisthepy.python.multiplatform.toolchain.hotreload

import org.thisisthepy.python.multiplatform.toolchain.dsl.CertExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.CodePushExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.HotReloadExtension


/**
 * Validated, immutable snapshot of `python { packaging { hotReload { ... } } }`.
 *
 * Produced by [validateHotReloadConfig]; consumed by [HotReloadPushTask] and by
 * `PythonPlugin.apply`'s `afterEvaluate` registration logic.
 */
data class HotReloadConfig(
    val enabled: Boolean,
    val serverHost: String,
    val redirectErrorStream: Boolean,
    val cert: CertConfig,
) {
    data class CertConfig(
        val keyStore: String?,
        val autoGenerate: Boolean,
    )
}

/**
 * Validated, immutable snapshot of `python { packaging { codePush { ... } } }`.
 *
 * Produced by [validateCodePushConfig].
 *
 * Note on what this repository cannot do: `serverHost` is a URL the user points at a server
 * they run themselves. This plugin validates the field but does not implement an upload protocol.
 * The `codePushPython` task is registered and reports this explicitly when it runs.
 */
data class CodePushConfig(
    val enabled: Boolean,
    val serverHost: String,
    val cert: CertConfig,
    val forceUpload: Boolean,
    val loginId: String,
    val loginPassword: String,
) {
    data class CertConfig(
        val keyStore: String?,
        val autoGenerate: Boolean,
    )
}

/**
 * Validates the `hotReload { }` DSL block and returns a [HotReloadConfig].
 *
 * Rules:
 * - If [enabled] is false, every other field is ignored and the result has `enabled = false`.
 * - If [enabled] is true, [HotReloadExtension.serverHost] must be non-blank.
 * - [CertExtension.keyStore] and [CertExtension.autoGenerate] are mutually exclusive.
 *
 * Factored out of `PythonPlugin.apply`'s `afterEvaluate` so it can be exercised without a
 * Gradle [org.gradle.api.Project] -- see [PythonPluginHotReloadTest].
 */
fun validateHotReloadConfig(enabled: Boolean, ext: HotReloadExtension): HotReloadConfig {
    if (!enabled) {
        return HotReloadConfig(
            enabled = false,
            serverHost = ext.serverHost,
            redirectErrorStream = ext.redirectErrorStream,
            cert = HotReloadConfig.CertConfig(
                keyStore = ext.cert.keyStore,
                autoGenerate = ext.cert.autoGenerate,
            ),
        )
    }

    require(ext.serverHost.isNotBlank()) {
        "python { buildTypes { ... } { enableHotReload = true } } requires " +
            "python { packaging { hotReload { serverHost = \"...\" } } } to be set."
    }

    require(!(ext.cert.keyStore != null && ext.cert.autoGenerate)) {
        "python { packaging { hotReload { cert { } } } }: " +
            "keyStore and autoGenerate are mutually exclusive; set only one."
    }

    return HotReloadConfig(
        enabled = true,
        serverHost = ext.serverHost,
        redirectErrorStream = ext.redirectErrorStream,
        cert = HotReloadConfig.CertConfig(
            keyStore = ext.cert.keyStore,
            autoGenerate = ext.cert.autoGenerate,
        ),
    )
}

/**
 * Validates the `codePush { }` DSL block and returns a [CodePushConfig].
 *
 * Rules mirror [validateHotReloadConfig]:
 * - If [enabled] is false, fields are carried through unchanged, `enabled = false`.
 * - If [enabled] is true, [CodePushExtension.serverHost] must be non-blank.
 * - [CertExtension.keyStore] and [CertExtension.autoGenerate] are mutually exclusive.
 *
 * What this repository CANNOT do: there is no server. The error message on the blank-serverHost
 * path says so explicitly, and the [org.thisisthepy.python.multiplatform.toolchain.hotreload.CodePushPendingTask]
 * registered in `PythonPlugin.apply` repeats it at task-execution time so the user sees it whether
 * or not they try to run the task.
 */
fun validateCodePushConfig(enabled: Boolean, ext: CodePushExtension): CodePushConfig {
    if (!enabled) {
        return CodePushConfig(
            enabled = false,
            serverHost = ext.serverHost,
            cert = CodePushConfig.CertConfig(
                keyStore = ext.cert.keyStore,
                autoGenerate = ext.cert.autoGenerate,
            ),
            forceUpload = ext.uploadConfig.forceUpload,
            loginId = ext.uploadConfig.login.id,
            loginPassword = ext.uploadConfig.login.password,
        )
    }

    require(ext.serverHost.isNotBlank()) {
        "python { buildTypes { ... } { enableCodePush = true } } requires " +
            "python { packaging { codePush { serverHost = \"...\" } } } to be set. " +
            "Note: no upload client is implemented; a server and client are needed to use codePush."
    }

    require(!(ext.cert.keyStore != null && ext.cert.autoGenerate)) {
        "python { packaging { codePush { cert { } } } }: " +
            "keyStore and autoGenerate are mutually exclusive; set only one."
    }

    return CodePushConfig(
        enabled = true,
        serverHost = ext.serverHost,
        cert = CodePushConfig.CertConfig(
            keyStore = ext.cert.keyStore,
            autoGenerate = ext.cert.autoGenerate,
        ),
        forceUpload = ext.uploadConfig.forceUpload,
        loginId = ext.uploadConfig.login.id,
        loginPassword = ext.uploadConfig.login.password,
    )
}
