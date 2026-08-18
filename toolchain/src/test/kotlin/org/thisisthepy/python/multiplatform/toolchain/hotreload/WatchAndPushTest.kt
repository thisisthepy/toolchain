package org.thisisthepy.python.multiplatform.toolchain.hotreload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * Tests for file-watch source collection and adb-push command generation.
 *
 * Pure unit tests: no Gradle [org.gradle.api.Project], no real adb, no real device.
 * [collectWatchedSources] and [buildAdbPushCommands] / [executeLocalCopy] are factored out of
 * [HotReloadPushTask]'s action so they can be exercised without spinning up a task.
 *
 * Toolchain boundary:
 *  - collectWatchedSources: walk a source root, return .py files.
 *  - buildAdbPushCommands: given files and a remote base path, return shell argument strings.
 *  - executeLocalCopy: for desktop, copy .py files to a destination directory.
 *
 * Not toolchain's job (python-multiplatform's runtime):
 *  - Receiving the reload broadcast and calling importlib.reload().
 */
class WatchAndPushTest {

    @Test
    fun `collectWatchedSources returns empty list when source root does not exist`() {
        val nonExistent = File("/tmp/does-not-exist-${System.nanoTime()}")
        val sources = collectWatchedSources(nonExistent)
        assertEquals(emptyList(), sources)
    }

    @Test
    fun `collectWatchedSources returns only py files recursively`() {
        val dir = Files.createTempDirectory("hr-test").toFile()
        try {
            File(dir, "main.py").writeText("# main")
            File(dir, "util.py").writeText("# util")
            File(dir, "readme.txt").writeText("not python")
            val subDir = File(dir, "sub").also { it.mkdir() }
            File(subDir, "sub_mod.py").writeText("# sub")

            val sources = collectWatchedSources(dir)
            val names = sources.map { it.name }.sorted()
            assertEquals(listOf("main.py", "sub_mod.py", "util.py"), names)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildAdbPushCommands produces one push entry per source file`() {
        val files = listOf(File("/src/main.py"), File("/src/util.py"))
        val commands = buildAdbPushCommands(
            sourceFiles = files,
            remoteBasePath = "/data/local/tmp/app/python",
            sourceRootDir = File("/src"),
            adbPath = "adb",
        )
        val pushCommands = commands.filter { it.contains("push") }
        assertEquals(2, pushCommands.size)
        assertTrue(pushCommands.any { it.contains("main.py") })
        assertTrue(pushCommands.any { it.contains("util.py") })
    }

    @Test
    fun `buildAdbPushCommands preserves relative paths under source root`() {
        val dir = Files.createTempDirectory("hr-cmd-test").toFile()
        try {
            val subDir = File(dir, "pkg").also { it.mkdir() }
            val pyFile = File(subDir, "mod.py").also { it.writeText("") }

            val commands = buildAdbPushCommands(
                sourceFiles = listOf(pyFile),
                remoteBasePath = "/data/local/tmp/python",
                sourceRootDir = dir,
                adbPath = "adb",
            )
            val pushLine = commands.first { it.contains("push") }
            // Remote path must include the subdirectory: pkg/mod.py
            assertTrue(
                pushLine.contains("pkg/mod.py") || pushLine.contains("pkg${File.separator}mod.py"),
                "Expected relative path 'pkg/mod.py' in: $pushLine",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `buildAdbPushCommands appends a reload broadcast after the pushes`() {
        val files = listOf(File("/src/main.py"))
        val commands = buildAdbPushCommands(
            sourceFiles = files,
            remoteBasePath = "/data/local/tmp/python",
            sourceRootDir = File("/src"),
            adbPath = "adb",
        )
        val last = commands.last()
        assertTrue(
            last.contains("am broadcast") && last.contains(HOT_RELOAD_BROADCAST_ACTION),
            "Expected reload broadcast in last command, got: $last",
        )
    }

    @Test
    fun `executeLocalCopy copies py files to destination`() {
        val srcDir = Files.createTempDirectory("hr-src").toFile()
        val dstDir = Files.createTempDirectory("hr-dst").toFile()
        try {
            File(srcDir, "main.py").writeText("# main")
            File(srcDir, "util.py").writeText("# util")

            val copied = executeLocalCopy(sourceRoot = srcDir, destinationRoot = dstDir)
            assertEquals(2, copied)
            assertTrue(File(dstDir, "main.py").exists())
            assertTrue(File(dstDir, "util.py").exists())
        } finally {
            srcDir.deleteRecursively()
            dstDir.deleteRecursively()
        }
    }

    @Test
    fun `executeLocalCopy returns 0 when source root is missing`() {
        val nonExistent = File("/tmp/hr-no-src-${System.nanoTime()}")
        val dstDir = Files.createTempDirectory("hr-dst-empty").toFile()
        try {
            val copied = executeLocalCopy(sourceRoot = nonExistent, destinationRoot = dstDir)
            assertEquals(0, copied)
        } finally {
            dstDir.deleteRecursively()
        }
    }
}
