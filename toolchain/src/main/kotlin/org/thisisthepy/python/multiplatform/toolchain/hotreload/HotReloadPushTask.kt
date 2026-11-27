package org.thisisthepy.python.multiplatform.toolchain.hotreload

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.thisisthepy.python.multiplatform.toolchain.devagent.DevAgentClient
import org.thisisthepy.python.multiplatform.toolchain.devagent.readDevAgentLaunch
import java.io.File
import java.io.IOException

/**
 * `hotReloadPython` (docs/SPEC.md §1.14; toolchain#150 M2, `docs/design/live-connection.md`): sends the staged
 * `.py` files that differ from the running debug app's copy to python-multiplatform's dev agent (`push`), which
 * writes them into its overlay and re-imports the modules among them. Which files differ comes from one digest
 * `execute` per running app ([digestQueryCode]; Decision (b), team lead 2026-11-27), cached in
 * `<name>.digest.json` beside the connection file and updated by every push.
 *
 * It replaces the old `adb push` + RELOAD_PYTHON broadcast (#103), which no app received. Disabled, the task
 * prints how to enable it.
 */
abstract class HotReloadPushTask : DefaultTask() {

    /** Whether hot reload is enabled for any build type. False: the task prints a guide. */
    @get:Input
    var hotReloadEnabled: Boolean = false

    /** The staged `python/` payload of the destination (`build/pythonStaging/<platform>/python`). */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:Optional
    var payloadDir: File? = null

    /** `build/pythonDevAgent/<name>.json`: the running app's port and token. */
    @get:Internal
    var connectionFile: File? = null

    /** How to make [connectionFile] when it is missing, for the failure message. */
    @get:Internal
    var connectionHint: String = ""

    @TaskAction
    fun push() {
        if (!hotReloadEnabled) {
            logger.lifecycle(
                "hotReloadPython: hot reload is not enabled for this build type. " +
                    "Set enableHotReload = true in python { buildTypes { getByName(\"debug\") { } } }.",
            )
            return
        }
        val connection = requireNotNull(connectionFile) { "hotReloadPython: no connection file configured" }
        val launch = readDevAgentLaunch(connection) ?: throw GradleException(
            "hotReloadPython: no running debug app to push to: $connection holds no dev agent port and token. $connectionHint.",
        )
        val payload = payloadDir
        val local = payload?.let(::stagedPythonSources).orEmpty()
        if (local.isEmpty()) {
            throw GradleException(
                "hotReloadPython: the staged payload ${payload ?: "(none)"} has no .py files. Hot reload sends source, " +
                    "so the build type needs build level 'instant' (a 'bytecode' or 'native' staging carries none).",
            )
        }
        val cacheFile = File(connection.parentFile, "${connection.nameWithoutExtension}.digest.json")
        try {
            DevAgentClient.connect("127.0.0.1", launch).use { agent ->
                val key = "${launch.port}:${sha256Hex(launch.token.toByteArray())}:${agent.hello.get("pid")?.asString}"
                val device = readDigestCache(cacheFile, key) ?: queryDigest(agent, local.keys).also {
                    logger.lifecycle("hotReloadPython: read the app's digest of ${local.size} staged .py files")
                }
                val plan = hotReloadPushPlan(local, device)
                if (plan.isEmpty()) {
                    writeDigestCache(cacheFile, key, device)
                    logger.lifecycle("hotReloadPython: the app already has every staged .py file; nothing pushed")
                    return
                }
                val files = plan.associateWith { File(payload, it).readText(Charsets.UTF_8) }
                val done = agent.push(files)
                // The files are written before any re-import, so a refused re-import still leaves them on the device.
                writeDigestCache(cacheFile, key, device + plan.associateWith { local.getValue(it) })
                if (done.get("status")?.asString != "ok") {
                    throw GradleException(
                        "hotReloadPython: the app refused the push of ${plan.joinToString()}: " +
                            "${done.get("ename")?.asString}: ${done.get("evalue")?.asString}",
                    )
                }
                val reloaded = done.getAsJsonArray("reloaded")?.joinToString { it.asString }.orEmpty()
                logger.lifecycle(
                    "hotReloadPython: pushed ${plan.joinToString()}" + if (reloaded.isNotEmpty()) "; re-imported $reloaded" else "",
                )
            }
        } catch (error: IOException) {
            throw GradleException("hotReloadPython: ${error.message} (127.0.0.1:${launch.port}, from $connection)", error)
        }
    }

    private fun queryDigest(agent: DevAgentClient, paths: Collection<String>): Map<String, String> {
        val execution = agent.execute(digestQueryCode(paths))
        if (execution.done.get("status")?.asString != "ok") {
            throw GradleException(
                "hotReloadPython: the app could not report its files: " +
                    "${execution.done.get("ename")?.asString}: ${execution.done.get("evalue")?.asString}",
            )
        }
        return parseDigestReply(execution.stdout)
            ?: throw GradleException("hotReloadPython: the app's digest reply was missing from its output")
    }

    private fun readDigestCache(file: File, key: String): Map<String, String>? {
        if (!file.isFile) return null
        val cache = runCatching { Gson().fromJson(file.readText(), JsonObject::class.java) }.getOrNull() ?: return null
        if (cache.get("key")?.asString != key) return null
        return cache.getAsJsonObject("files")?.entrySet()?.associate { it.key to it.value.asString }
    }

    private fun writeDigestCache(file: File, key: String, files: Map<String, String>) {
        file.parentFile?.mkdirs()
        file.writeText(Gson().toJson(mapOf("key" to key, "files" to files.toSortedMap())))
    }
}
