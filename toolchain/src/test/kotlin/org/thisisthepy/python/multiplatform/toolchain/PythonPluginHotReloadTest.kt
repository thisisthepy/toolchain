package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.HotReloadExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.PackagingExtension
import org.thisisthepy.python.multiplatform.toolchain.hotreload.HotReloadPushTask
import org.thisisthepy.python.multiplatform.toolchain.hotreload.validateHotReloadConfig
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.thisisthepy.python.multiplatform.toolchain.devagent.DevAgentLaunch
import org.thisisthepy.python.multiplatform.toolchain.hotreload.sha256Hex
import org.thisisthepy.python.multiplatform.toolchain.hotreload.DIGEST_LINE_PREFIX
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Tests for `python { packaging { hotReload { ... } } }`: the DSL validation ([validateHotReloadConfig], a pure
 * function factored out of `PythonPlugin.apply`), and `hotReloadPython` over the live channel (docs/SPEC.md §1.14,
 * toolchain#150 M2): it pushes to python-multiplatform's dev agent the staged `.py` files that differ from the
 * app's copy (Decision (b), team lead 2026-11-27). The old `adb push` + RELOAD_PYTHON broadcast (#103) is gone.
 */
class PythonPluginHotReloadTest {

    /**
     * A stand-in for python-multiplatform's dev agent (its SPEC L-12): the same line protocol, `hello` with the
     * token, `execute` answering the digest query from [files] (what the app's `sys.path` holds), and `push`
     * recorded and applied to [files].
     */
    private class FakeAgent(val files: MutableMap<String, String>, var pid: Int = 100, val refusePush: Boolean = false) : AutoCloseable {
        val token = "t".repeat(43)
        private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val port = server.localPort
        val executes = mutableListOf<String>()
        val pushes = mutableListOf<Map<String, String>>()
        private val gson = Gson()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use { serve(it) }
                }
            }
        }

        private fun serve(socket: java.net.Socket) {
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream().bufferedWriter()
            fun send(vararg pairs: Pair<String, Any?>) {
                writer.write(gson.toJson(mapOf(*pairs)) + "\n"); writer.flush()
            }
            while (true) {
                val line = reader.readLine() ?: return
                val message = gson.fromJson(line, JsonObject::class.java)
                val id = message.get("id").asInt
                when (message.get("op").asString) {
                    "hello" -> {
                        if (message.get("token").asString != token) return
                        send("id" to id, "type" to "hello", "protocol" to 1, "pid" to pid)
                    }
                    "execute" -> {
                        executes += message.get("code").asString
                        val digest = files.mapValues { sha256Hex(it.value.toByteArray()) }
                        send("id" to id, "type" to "stream", "name" to "stdout", "text" to "app log line\n" + DIGEST_LINE_PREFIX + gson.toJson(digest) + "\n")
                        send("id" to id, "type" to "done", "status" to "ok", "count" to executes.size)
                    }
                    "push" -> {
                        val pushed = gson.fromJson(message.get("files"), Map::class.java)
                            .entries.associate { it.key as String to it.value as String }
                        pushes += pushed
                        if (refusePush) {
                            send("id" to id, "type" to "done", "status" to "error", "ename" to "TypeCheckError", "evalue" to "app/main.py")
                        } else {
                            files.putAll(pushed)
                            send("id" to id, "type" to "done", "status" to "ok", "reloaded" to pushed.keys.map { it.removeSuffix(".py").replace('/', '.') })
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    private class Setup(val payload: File, val connectionFile: File, val task: HotReloadPushTask)

    /** A staged payload holding an app module and a dependency, and a task pointed at [agent]'s connection file. */
    private fun setup(agent: FakeAgent?, enabled: Boolean = true): Setup {
        val project = ProjectBuilder.builder().build()
        val payload = createTempDirectory("hot-reload-payload").toFile()
        payload.resolve("app").mkdirs()
        payload.resolve("dep").mkdirs()
        payload.resolve("app/main.py").writeText("X = 2\n")
        payload.resolve("dep/lib.py").writeText("LIB = 1\n")
        val connectionDir = createTempDirectory("hot-reload-connection").toFile()
        val connectionFile = File(connectionDir, "android.json")
        agent?.let { DevAgentLaunch(it.port, it.token).writeConnectionFile(connectionFile) }
        val task = project.tasks.register("hotReloadPython", HotReloadPushTask::class.java) {
            hotReloadEnabled = enabled
            payloadDir = payload
            this.connectionFile = connectionFile
            connectionHint = "run connectPythonAndroid after installing the debug app"
        }.get()
        return Setup(payload, connectionFile, task)
    }

    private fun HotReloadPushTask.run() = actions.forEach { it.execute(this) }

    @Test
    fun `the first push reads the app's digest once and pushes the edited app file, not the unchanged dependency`() {
        FakeAgent(mutableMapOf("app/main.py" to "X = 1\n", "dep/lib.py" to "LIB = 1\n")).use { agent ->
            val setup = setup(agent)

            setup.task.run()

            assertEquals(1, agent.executes.size)
            assertEquals(listOf(mapOf("app/main.py" to "X = 2\n")), agent.pushes)
            assertTrue(File(setup.connectionFile.parentFile, "android.digest.json").isFile)
            assertFalse(agent.token in File(setup.connectionFile.parentFile, "android.digest.json").readText())
        }
    }

    @Test
    fun `a later push uses the cached digest and sends only the next edit, and nothing changed sends nothing`() {
        FakeAgent(mutableMapOf("app/main.py" to "X = 1\n", "dep/lib.py" to "LIB = 1\n")).use { agent ->
            val setup = setup(agent)
            setup.task.run()

            setup.payload.resolve("app/main.py").writeText("X = 3\n")
            setup.task.run()
            setup.task.run()

            assertEquals(1, agent.executes.size, "the digest is read once per running app")
            assertEquals(listOf(mapOf("app/main.py" to "X = 2\n"), mapOf("app/main.py" to "X = 3\n")), agent.pushes)
        }
    }

    @Test
    fun `a restarted app is read again`() {
        FakeAgent(mutableMapOf("app/main.py" to "X = 1\n", "dep/lib.py" to "LIB = 1\n")).use { agent ->
            val setup = setup(agent)
            setup.task.run()

            agent.pid = 101
            agent.files["app/main.py"] = "X = 1\n"
            setup.task.run()

            assertEquals(2, agent.executes.size)
            assertEquals(listOf(mapOf("app/main.py" to "X = 2\n"), mapOf("app/main.py" to "X = 2\n")), agent.pushes)
        }
    }

    @Test
    fun `a missing connection file fails naming it and how to make it`() {
        val setup = setup(agent = null)

        val error = assertFailsWith<GradleException> { setup.task.run() }

        val message = error.message.orEmpty()
        assertTrue(setup.connectionFile.path in message && "connectPythonAndroid" in message, message)
    }

    @Test
    fun `a push the agent refuses fails with its error`() {
        FakeAgent(mutableMapOf("app/main.py" to "X = 1\n", "dep/lib.py" to "LIB = 1\n"), refusePush = true).use { agent ->
            val setup = setup(agent)

            val error = assertFailsWith<GradleException> { setup.task.run() }

            assertTrue("TypeCheckError" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `a disabled hot reload still only prints its guide`() {
        setup(agent = null, enabled = false).task.run()
    }

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
