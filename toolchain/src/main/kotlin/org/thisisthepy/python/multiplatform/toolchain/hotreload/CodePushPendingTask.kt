package org.thisisthepy.python.multiplatform.toolchain.hotreload

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Placeholder task for `codePushPython`, registered whenever at least one declared build type
 * has `enableCodePush = true`.
 *
 * This task deliberately does nothing beyond printing an honest status: **codePush upload is not
 * implemented** because there is no server to upload to. The pattern mirrors `pypackpack`'s
 * [org.thisisthepy.python.multiplatform.packpack.deploy.UnimplementedDeployer]: a `Result.failure`
 * is the honest response when the deployment target server is not specified or decided yet.
 *
 * What is implemented here:
 *  - DSL validation ([validateCodePushConfig]): invalid field combinations are caught at
 *    configuration time with a message pointing at the bad field.
 *  - Task registration: users can see `codePushPython` in `./gradlew tasks --group python` and
 *    understand what would be needed, instead of finding no task at all and not knowing whether
 *    `enableCodePush = true` did anything.
 *
 * What is NOT implemented, and why:
 *  - Upload protocol: `serverHost` is a URL the user would point at a CDN or OTA server they
 *    run. No such server API is defined in this repository. Implementing an upload client without
 *    a server spec to match against would be guesswork.
 *  - Authentication: `uploadConfig.login.id/password` are validated but not transmitted.
 *
 * When a server is chosen and its API is specified, this task should be replaced with a real
 * implementation that calls the server's upload endpoint with the bundle produced by
 * [packagePython][org.thisisthepy.python.multiplatform.toolchain.PythonPlugin.PACKAGE_TASK].
 */
abstract class CodePushPendingTask : DefaultTask() {

    @get:Input
    var codePushEnabled: Boolean = false

    @get:Input
    var serverHost: String = ""

    @TaskAction
    fun reportStatus() {
        if (!codePushEnabled) {
            logger.lifecycle(
                "codePushPython: code push is not enabled for this build type. " +
                    "Set enableCodePush = true in python { buildTypes { getByName(\"release\") { } } }.",
            )
            return
        }

        logger.lifecycle(
            "codePushPython: serverHost='$serverHost' is configured but no upload client is " +
                "implemented in the toolchain. To use codePush:\n" +
                "  1. Run a server that accepts Python bundle uploads (URL set in serverHost).\n" +
                "  2. Implement an upload client in the toolchain (this task).\n" +
                "  3. The app's Python runtime must call the server to fetch updates on launch.\n" +
                "None of these exist yet. The task is registered so you can see it in `./gradlew tasks`.",
        )
    }
}
