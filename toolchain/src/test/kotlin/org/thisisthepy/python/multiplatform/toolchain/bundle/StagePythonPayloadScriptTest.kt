package org.thisisthepy.python.multiplatform.toolchain.bundle

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Runs `tools/xcode/stage-python-payload.sh` with a fake gradlew and a fake rsync that records calls. */
class StagePythonPayloadScriptTest {
    private val bash: String? = listOf("/bin/bash", "/usr/bin/bash", "/usr/local/bin/bash", "/opt/homebrew/bin/bash")
        .firstOrNull { File(it).canExecute() }

    private fun script(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tools/xcode/stage-python-payload.sh")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("tools/xcode/stage-python-payload.sh not found above ${File("").absolutePath}")
    }

    private class Run(val exit: Int, val rsyncCalls: List<String>)

    private fun run(gradlewBody: String): Pair<Run, File> {
        val work = createTempDirectory("xcode-script").toFile()
        val gradlew = File(work, "fake-gradlew").apply { writeText("#!/bin/sh\n$gradlewBody\n"); setExecutable(true) }
        val bin = File(work, "bin").apply { mkdirs() }
        val log = File(work, "rsync.log")
        File(bin, "rsync").apply {
            writeText("#!/bin/sh\necho \"\$*\" >> '${log.path}'\n")
            setExecutable(true)
        }
        val pb = ProcessBuilder(bash, script().path).redirectErrorStream(true)
        pb.environment().apply {
            put("PATH", "${bin.path}:${get("PATH")}")
            put("GRADLEW", gradlew.path)
            put("TARGET_BUILD_DIR", "/tb")
            put("UNLOCALIZED_RESOURCES_FOLDER_PATH", "App.app")
        }
        val p = pb.start()
        p.inputStream.readBytes()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        return Run(p.exitValue(), if (log.exists()) log.readLines() else emptyList()) to work
    }

    @Test
    fun `a failing gradle exits non-zero and never calls rsync`() {
        if (bash == null) return
        val (r, _) = run("exit 1")
        assertNotEquals(0, r.exit)
        assertTrue(r.rsyncCalls.isEmpty())
    }

    @Test
    fun `no PYTHON_PAYLOAD_DIR line exits non-zero and never calls rsync`() {
        if (bash == null) return
        val (r, _) = run("echo hello")
        assertNotEquals(0, r.exit)
        assertTrue(r.rsyncCalls.isEmpty())
    }

    @Test
    fun `a payload dir that does not exist exits non-zero and never calls rsync`() {
        if (bash == null) return
        val (r, _) = run("echo PYTHON_PAYLOAD_DIR=/nonexistent/xyz/python")
        assertNotEquals(0, r.exit)
        assertTrue(r.rsyncCalls.isEmpty())
    }

    @Test
    fun `a valid payload dir calls rsync once with the dir and the app destination`() {
        if (bash == null) return
        val payload = createTempDirectory("payload").toFile()
        val (r, _) = run("echo noise; echo PYTHON_PAYLOAD_DIR=${payload.path}")
        assertEquals(0, r.exit)
        assertEquals(listOf("-a --delete ${payload.path}/ /tb/App.app/python/"), r.rsyncCalls)
    }
}
