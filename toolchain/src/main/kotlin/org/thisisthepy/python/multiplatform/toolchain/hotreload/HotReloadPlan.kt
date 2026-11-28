package org.thisisthepy.python.multiplatform.toolchain.hotreload

import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest

/*
 * What `hotReloadPython` pushes (docs/SPEC.md §1.14, toolchain#150 M2; Decision (b), team lead 2026-11-27): the
 * staged payload's `.py` files whose SHA-256 differs from the app's copy. The app's copy is read over the channel
 * with [digestQueryCode], so a dependency the app already carries is never pushed and pypackpack alone decides
 * what the payload holds.
 */

/** Marks the line of an `execute`'s stdout that carries [digestQueryCode]'s answer. */
const val DIGEST_LINE_PREFIX = "toolchain-hot-reload-digest:"

/** The digest [digestQueryCode] printed into [stdout], or null when no line carries it. */
fun parseDigestReply(stdout: String): Map<String, String>? {
    val line = stdout.lineSequence().lastOrNull { it.startsWith(DIGEST_LINE_PREFIX) } ?: return null
    val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
    return com.google.gson.Gson().fromJson<Map<String, String>>(line.removePrefix(DIGEST_LINE_PREFIX), type)
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Every `.py` file under [payloadDir], keyed by its `/`-separated path relative to it, to its SHA-256. */
fun stagedPythonSources(payloadDir: File): Map<String, String> {
    if (!payloadDir.isDirectory) return emptyMap()
    return payloadDir.walkTopDown()
        .filter { it.isFile && it.extension == "py" }
        .associate { it.relativeTo(payloadDir).invariantSeparatorsPath to sha256Hex(it.readBytes()) }
        .toSortedMap()
}

/** The paths of [local] (path to SHA-256) whose hash [device] does not have: edited or new files, sorted. */
fun hotReloadPushPlan(local: Map<String, String>, device: Map<String, String>): List<String> =
    local.filter { (path, hash) -> device[path] != hash }.keys.sorted()

/**
 * Python for the agent's `execute`: for each of [paths], the SHA-256 of the first file the app's `sys.path`
 * resolves it to (the agent's overlay is first), printed as one JSON object on a line starting with
 * [DIGEST_LINE_PREFIX] (the app's own threads may print during it). A path the app does not have is left out.
 * It defines one function and deletes it, so the app's `__main__` keeps no name from it.
 */
fun digestQueryCode(paths: Collection<String>): String {
    val literal = GsonBuilder().disableHtmlEscaping().create().toJson(paths.toList())
    return """
        |def __toolchain_hot_reload_digest(paths):
        |    import hashlib, json, os, sys
        |    found = {}
        |    for path in paths:
        |        for root in sys.path:
        |            candidate = os.path.join(root, path) if root else None
        |            if candidate and os.path.isfile(candidate):
        |                with open(candidate, "rb") as source:
        |                    found[path] = hashlib.sha256(source.read()).hexdigest()
        |                break
        |    return json.dumps(found)
        |try:
        |    print("$DIGEST_LINE_PREFIX" + __toolchain_hot_reload_digest($literal))
        |finally:
        |    del __toolchain_hot_reload_digest
        """.trimMargin()
}
