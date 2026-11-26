package org.thisisthepy.python.multiplatform.toolchain.devagent

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.JavaExec
import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/*
 * The launcher's half of the dev agent (toolchain#105; python-multiplatform SPEC L-12; docs/design/live-connection.md).
 *
 * python-multiplatform's runtime starts its agent only when both hold: the process has
 * [DEV_AGENT_ENVIRONMENT_VARIABLE], and a payload root holds [DEV_AGENT_ENABLE_MARKER]. This plugin provides
 * both, for a build whose active Python build type has `enableHotReload = true` (the example build file's
 * `debug`): the staging tasks write the marker, and the desktop run task gets a fresh port and token.
 * A build without it gets neither, and a process started with the variable by hand still opens no port.
 *
 * An Android or iOS app is started by the OS, which passes it no variable (toolchain#150 M2 and M3, Decision 15
 * (a), leader 2026-11-25). Its marker carries the request instead, in the variable's form, and the runtime reads
 * it when the variable is unset. The port and token come from [readOrCreateDevAgentLaunch]: made once per build
 * directory and kept, because the installed app holds them until it is reinstalled.
 */

/** The variable python-multiplatform's `PythonDevAgent` reads: `port=<n>;token=<t>`. */
const val DEV_AGENT_ENVIRONMENT_VARIABLE = "PYTHON_MULTIPLATFORM_DEV_AGENT"

/** python-multiplatform's `PythonDevAgent.ENABLE_MARKER`, written at the root of the staged `python/` payload. */
const val DEV_AGENT_ENABLE_MARKER = "python_multiplatform_devagent.enable"

/** The name of the `doFirst` action that starts an agent; Gradle shows it as "Execute startPythonDevAgent". */
const val DEV_AGENT_LAUNCH_ACTION = "startPythonDevAgent"

/** `<buildDir>/pythonDevAgent/<task>.json`: where a launch leaves its port and token for `tcl connect`. */
const val DEV_AGENT_CONNECTION_DIRECTORY = "pythonDevAgent"

/** The task that forwards the laptop's loopback port to an Android device's agent: `connectPythonAndroid`. */
const val DEV_AGENT_CONNECT_ANDROID_TASK = "connectPythonAndroid"

/** Random bytes in a token: python-multiplatform refuses fewer than `secrets.token_urlsafe(32)` gives. */
private const val TOKEN_BYTES = 32

/** python-multiplatform's `MIN_TOKEN_LENGTH`: `len(secrets.token_urlsafe(32))`. */
private const val MIN_TOKEN_CHARACTERS = 43

/**
 * Whether [activeBuildType] is a build the dev agent belongs in: it declares `enableHotReload = true`.
 *
 * No `buildTypes { }` block, or an active type that does not set it, is false: nothing asked for a live
 * channel, so no payload carries the marker.
 */
fun devAgentEnabled(buildTypes: BuildTypesContainer, activeBuildType: String): Boolean =
    buildTypes.all().firstOrNull { it.name == activeBuildType }?.enableHotReload == true

/** One launch's loopback port and token. */
data class DevAgentLaunch(val port: Int, val token: String) {
    /** The value of [DEV_AGENT_ENVIRONMENT_VARIABLE]. */
    val environmentValue: String get() = "port=$port;token=$token"

    /**
     * Writes `{"host", "port", "token"}` to [file], replacing it. On a POSIX file system the file is created
     * owner-only before the token is written: the token is what keeps any other local user from running code
     * in the app.
     */
    fun writeConnectionFile(file: File) {
        file.parentFile?.mkdirs()
        val path = file.toPath()
        Files.deleteIfExists(path)
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        file.writeText("""{"host": "127.0.0.1", "port": $port, "token": "$token"}""" + "\n")
    }
}

private val CONNECTION_PORT = Regex("\"port\"\\s*:\\s*(\\d+)")
private val CONNECTION_TOKEN = Regex("\"token\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"")

/** The launch [file] holds (as [DevAgentLaunch.writeConnectionFile] writes it), or null if it holds none the runtime accepts. */
fun readDevAgentLaunch(file: File): DevAgentLaunch? {
    if (!file.isFile) return null
    val text = file.readText()
    val port = CONNECTION_PORT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val token = CONNECTION_TOKEN.find(text)?.groupValues?.get(1) ?: return null
    if (port !in 1..65535 || token.length < MIN_TOKEN_CHARACTERS) return null
    return DevAgentLaunch(port, token)
}

/**
 * A mobile build's launch: the one [file] holds, or a new one written there. Kept across builds, because the
 * marker inside an installed app carries it; deleting the file (or `clean`) makes a new one, and the app then
 * needs reinstalling.
 */
fun readOrCreateDevAgentLaunch(file: File, random: SecureRandom = SecureRandom()): DevAgentLaunch =
    readDevAgentLaunch(file) ?: newDevAgentLaunch(random).also { it.writeConnectionFile(file) }

/** `adb forward tcp:<port> tcp:<port>`: the laptop's loopback port reaches the device's, where the agent listens. */
fun adbForwardCommand(adb: String, port: Int): List<String> = listOf(adb, "forward", "tcp:$port", "tcp:$port")

/**
 * The `adb` to run: `platform-tools/adb` under `ANDROID_HOME` (or `ANDROID_SDK_ROOT`), else under [sdkDir]
 * (`local.properties`' `sdk.dir`), else `adb` from `PATH`. `adb` itself honours `ANDROID_SERIAL` to pick a device.
 */
fun resolveAdb(environment: Map<String, String>, sdkDir: File?): String {
    val candidates = listOfNotNull(environment["ANDROID_HOME"], environment["ANDROID_SDK_ROOT"])
        .map(::File) + listOfNotNull(sdkDir)
    return candidates.map { File(it, "platform-tools/adb") }.firstOrNull { it.isFile }?.path ?: "adb"
}

/** Runs [adbForwardCommand] for the launch in [connectionFile] and returns it; fails naming what is missing. */
fun forwardDevAgent(connectionFile: File, adb: String, run: (List<String>) -> Int): DevAgentLaunch {
    val launch = readDevAgentLaunch(connectionFile) ?: throw GradleException(
        "No dev-agent connection file at $connectionFile. It is written when a build type with enableHotReload " +
            "stages the Android payload: build and install the debug app first (for example installDebug).",
    )
    val command = adbForwardCommand(adb, launch.port)
    val exit = run(command)
    if (exit != 0) throw GradleException("adb forward failed (exit $exit): ${command.joinToString(" ")}. Is a device or emulator connected?")
    return launch
}

/**
 * `connectPythonAndroid`: forwards the laptop's port to the installed debug app's agent, after which
 * `tcl connect` and the proxy kernel use [connectionFile] as they do on desktop.
 */
abstract class ConnectPythonDevAgentTask : DefaultTask() {
    @get:Internal
    lateinit var connectionFile: File

    @get:Internal
    var sdkDir: File? = null

    @TaskAction
    fun connect() {
        val adb = resolveAdb(System.getenv(), sdkDir)
        val launch = forwardDevAgent(connectionFile, adb) { command ->
            ProcessBuilder(command).inheritIO().start().waitFor()
        }
        logger.lifecycle("Python dev agent: 127.0.0.1:${launch.port} forwarded to the device, connection file $connectionFile")
    }
}

/** A launch with a free loopback port and [TOKEN_BYTES] random bytes as an unpadded URL-safe token (43 characters). */
fun newDevAgentLaunch(random: SecureRandom = SecureRandom()): DevAgentLaunch {
    val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    return DevAgentLaunch(port, token)
}

/**
 * The `doFirst` of a desktop run task: a fresh launch per run, handed to the app as the variable and to the
 * laptop as [connectionFile]. The port is picked here rather than by the agent (port 0) because the laptop
 * has to know it before the app has printed anything; the window between closing the probe socket and the
 * app binding it is the only race, and losing it fails the agent's bind with a warning, not the app.
 */
class DevAgentLaunchAction(private val connectionFile: File) : Action<Task> {
    override fun execute(task: Task) {
        val launch = newDevAgentLaunch()
        (task as JavaExec).environment(DEV_AGENT_ENVIRONMENT_VARIABLE, launch.environmentValue)
        launch.writeConnectionFile(connectionFile)
        task.logger.lifecycle("Python dev agent: 127.0.0.1:${launch.port}, connection file $connectionFile")
    }
}
