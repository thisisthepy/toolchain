package org.thisisthepy.python.multiplatform.toolchain.devagent

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The laptop's client for python-multiplatform's dev agent (its SPEC L-12), in Kotlin for `hotReloadPython`
 * (toolchain#150 M2). The same protocol as toolchain-lite's `agent.py`: one JSON object per line, each carrying
 * its request's `id`, and `hello` with the token first.
 */
class DevAgentClient private constructor(
    private val socket: Socket,
    private val reader: BufferedReader,
    private val writer: Writer,
) : Closeable {
    /** The agent's `hello` reply: `protocol`, `python`, `pid`. */
    lateinit var hello: JsonObject
        private set

    private val gson = Gson()
    private var nextId = 1

    /** What an `execute` printed to stdout, and its `done` message. */
    class Execution(val stdout: String, val done: JsonObject)

    fun execute(code: String): Execution {
        val stdout = StringBuilder()
        val done = request(mapOf("op" to "execute", "code" to code)) { reply ->
            if (reply.string("type") == "stream" && reply.string("name") == "stdout") stdout.append(reply.string("text"))
        }
        return Execution(stdout.toString(), done)
    }

    /** `push`: [files] (path relative to the payload root to text) into the overlay; the `done` message. */
    fun push(files: Map<String, String>): JsonObject = request(mapOf("op" to "push", "files" to files)) { }

    private fun request(message: Map<String, Any>, until: String = "done", onReply: (JsonObject) -> Unit): JsonObject {
        val id = nextId++
        writer.write(gson.toJson(message + ("id" to id)) + "\n")
        writer.flush()
        while (true) {
            val line = reader.readLine()
                ?: throw IOException("the dev agent closed the connection (a wrong token closes it at once)")
            val reply = gson.fromJson(line, JsonObject::class.java)
            if (reply.get("id")?.takeIf { it.isJsonPrimitive }?.asInt != id) continue
            if (reply.string("type") == "error") {
                throw IOException("the dev agent refused the request: ${reply.string("ename")}: ${reply.string("evalue")}")
            }
            onReply(reply)
            if (reply.string("type") == until) return reply
        }
    }

    override fun close() = socket.close()

    companion object {
        const val PROTOCOL = 1

        /** Connects to [launch] on [host] and says `hello`; fails if the agent is absent, refuses, or speaks another protocol. */
        fun connect(host: String, launch: DevAgentLaunch, timeoutMillis: Int = 30_000): DevAgentClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, launch.port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                val client = DevAgentClient(
                    socket,
                    socket.getInputStream().bufferedReader(Charsets.UTF_8),
                    socket.getOutputStream().bufferedWriter(Charsets.UTF_8),
                )
                client.hello = client.request(mapOf("op" to "hello", "token" to launch.token), until = "hello") { }
                val protocol = client.hello.get("protocol")?.asInt
                if (protocol != PROTOCOL) throw IOException("the dev agent speaks protocol $protocol, this client $PROTOCOL")
                return client
            } catch (error: IOException) {
                socket.close()
                throw error
            }
        }

        private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
