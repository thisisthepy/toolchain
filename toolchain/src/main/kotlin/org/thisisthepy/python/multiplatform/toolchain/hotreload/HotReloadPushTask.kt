package org.thisisthepy.python.multiplatform.toolchain.hotreload

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Pushes changed Python source files to a connected Android device via `adb push` and sends a
 * reload broadcast ([HOT_RELOAD_BROADCAST_ACTION]) to signal the running app.
 *
 * Registered as `hotReloadPython` by `PythonPlugin.apply` when at least one declared build type
 * has `enableHotReload = true`. The task is gated by [enabled]; when `false` it prints a clear
 * message explaining what is missing rather than silently doing nothing.
 *
 * Boundary -- what this task does:
 *  1. Walk [sourceRoot] for `.py` files ([collectWatchedSources]).
 *  2. Push each file to [remoteBasePath] on the connected device ([buildAdbPushCommands]).
 *  3. Send [HOT_RELOAD_BROADCAST_ACTION] via `adb shell am broadcast`.
 *
 * Boundary -- what this task does NOT do:
 *  - It does not restart the app or call `importlib.reload()`. That is the Python runtime's
 *    responsibility (python-multiplatform). The broadcast is the contract point.
 *  - It does not serve files over HTTPS; [hotReloadConfig] carries `serverHost` for future use
 *    once a server component exists.
 *  - It does not watch the filesystem for changes and re-run automatically. That would require
 *    a long-running daemon process; Gradle tasks are one-shot. A build-system-level file watcher
 *    (e.g. `./gradlew hotReloadPython --continuous`) covers this use case at the Gradle level.
 */
abstract class HotReloadPushTask : DefaultTask() {

    /** Whether hotReload is enabled for the active build type. False => task prints a guide. */
    @get:Input
    var hotReloadEnabled: Boolean = false

    /** Local Python source root directory to push from. */
    @get:InputDirectory
    @get:Optional
    var sourceRoot: File? = null

    /**
     * On-device path where Python source files live, e.g. `/data/local/tmp/app/python`.
     * Set by `PythonPlugin.apply`; no default because the right path is app-specific.
     */
    @get:Input
    var remoteBasePath: String = ""

    /** Path to the `adb` executable. Defaults to `"adb"` (PATH lookup). */
    @get:Input
    var adbPath: String = "adb"

    /** Captured validated config for log output. */
    @get:Input
    var serverHost: String = ""

    @TaskAction
    fun push() {
        if (!hotReloadEnabled) {
            logger.lifecycle(
                "hotReloadPython: hot reload is not enabled for this build type. " +
                    "Set enableHotReload = true in python { buildTypes { getByName(\"debug\") { } } }.",
            )
            return
        }

        val root = sourceRoot
        if (root == null || !root.isDirectory) {
            logger.lifecycle(
                "hotReloadPython: sourceRoot is not set or does not exist ($root). " +
                    "Set python { localLibraryPath = \"...\" } to point at your Python source tree.",
            )
            return
        }

        val files = collectWatchedSources(root)
        if (files.isEmpty()) {
            logger.lifecycle("hotReloadPython: no .py files found under $root.")
            return
        }

        if (remoteBasePath.isBlank()) {
            logger.lifecycle(
                "hotReloadPython: remoteBasePath is not configured. " +
                    "This is a toolchain bug -- please report it.",
            )
            return
        }

        logger.lifecycle("hotReloadPython: pushing ${files.size} file(s) to $remoteBasePath …")

        if (serverHost.isNotBlank()) {
            logger.info(
                "hotReloadPython: serverHost='$serverHost' is set but no HTTP upload client " +
                    "is implemented in this version of the toolchain. Files will be pushed via " +
                    "adb only. A server-based push path requires a server component and an upload " +
                    "client; neither is available in this repository today.",
            )
        }

        val commands = buildAdbPushCommands(
            sourceFiles = files,
            remoteBasePath = remoteBasePath,
            sourceRootDir = root,
            adbPath = adbPath,
        )

        commands.forEach { cmd ->
            logger.info("hotReloadPython: $cmd")
            val parts = cmd.split(" ")
            val result = ProcessBuilder(parts)
                .redirectErrorStream(true)
                .start()
            val output = result.inputStream.bufferedReader().readText()
            val exit = result.waitFor()
            if (exit != 0) {
                logger.error("hotReloadPython: command failed (exit=$exit): $cmd\n$output")
            } else if (output.isNotBlank()) {
                logger.lifecycle(output.trim())
            }
        }

        logger.lifecycle("hotReloadPython: done. Reload signal sent to device.")
    }
}
