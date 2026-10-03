package org.thisisthepy.python.multiplatform.toolchain.hotreload

import java.io.File

/**
 * The Android broadcast action sent to signal the running Python runtime that new source files
 * have been pushed. The runtime (python-multiplatform) is expected to listen for this and call
 * `importlib.reload()` on the affected modules.
 *
 * Toolchain's job ends at sending this broadcast. The runtime's job begins at receiving it.
 */
const val HOT_RELOAD_BROADCAST_ACTION = "org.thisisthepy.python.RELOAD_PYTHON"

/**
 * Returns all `.py` files under [sourceRoot], recursively.
 *
 * Returns an empty list when [sourceRoot] does not exist or is not a directory. This is a
 * no-op rather than an error: a consumer that has not configured [sourceRoot] yet gets silence,
 * the same convention `BuildPythonArtifactTask` uses for a missing `packageDir`.
 */
fun collectWatchedSources(sourceRoot: File): List<File> {
    if (!sourceRoot.isDirectory) return emptyList()
    return sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "py" }
        .toList()
}

/**
 * Builds the shell-command strings that push [sourceFiles] to a device via `adb push`, then
 * send a reload broadcast.
 *
 * Each file is pushed to `<remoteBasePath>/<relative-path-from-sourceRootDir>`, preserving the
 * directory structure so the device side can reconstruct the same package layout.
 *
 * The last element is always the `adb shell am broadcast` command that tells the running Python
 * runtime ([HOT_RELOAD_BROADCAST_ACTION]) that new files have arrived. The runtime side (in
 * python-multiplatform) is responsible for receiving that broadcast and calling
 * `importlib.reload()`; this function only produces the string, it does not execute it.
 *
 * @param sourceFiles the .py files to push (from [collectWatchedSources])
 * @param remoteBasePath the on-device root path (e.g. `/data/local/tmp/app/python`)
 * @param sourceRootDir the local root that [sourceFiles] are relative to
 * @param adbPath the path to the `adb` executable (defaults to `"adb"` = PATH lookup)
 */
fun buildAdbPushCommands(
    sourceFiles: List<File>,
    remoteBasePath: String,
    sourceRootDir: File,
    adbPath: String = "adb",
): List<String> {
    val pushCommands = sourceFiles.map { file ->
        val relative = file.relativeTo(sourceRootDir).path.replace(File.separatorChar, '/')
        "$adbPath push ${file.absolutePath} $remoteBasePath/$relative"
    }
    val broadcastCommand =
        "$adbPath shell am broadcast -a $HOT_RELOAD_BROADCAST_ACTION"
    return pushCommands + broadcastCommand
}

/**
 * Copies all `.py` files from [sourceRoot] to [destinationRoot], preserving subdirectory
 * structure. Used for the desktop target where `adb push` is not available.
 *
 * Returns the number of files copied, or 0 if [sourceRoot] does not exist.
 */
fun executeLocalCopy(sourceRoot: File, destinationRoot: File): Int {
    if (!sourceRoot.isDirectory) return 0
    destinationRoot.mkdirs()
    var count = 0
    sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "py" }
        .forEach { src ->
            val relative = src.relativeTo(sourceRoot)
            val dst = File(destinationRoot, relative.path)
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            count++
        }
    return count
}
