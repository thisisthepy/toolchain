package org.thisisthepy.python.multiplatform.tcl

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real judgment call this module makes for GitHub issue thisisthepy/toolchain#1
 * (toolchain-lite, `tcl install <package>`): a python-only user may or may not already have a
 * `pypackpack` project at the target directory, and `install` must handle both. Uses real `uv`
 * and real network access (PyPI), the same tradeoff `InstallDependenciesTaskTest`
 * (`:toolchain`) documents and accepts for the same reason: a fake backend would only prove this
 * module calls the right function, not that the resulting `pyproject.toml` is real.
 *
 * Pre-implementation state: this file fails to compile (no `Installer`, no `installBlocking`, no
 * `findProjectRoot` on the classpath) until `Installer.kt` exists -- that is the "red" this
 * repository's TDD rule asks for, confirmed by running `:tcl:test` against a `tcl` module that
 * has this test file and a `build.gradle.kts` but no `src/main` yet.
 */
class InstallerTest {
    @Test
    fun `install initializes a fresh project when none exists yet`() {
        val dir = createTempDirectory("tcl-install-fresh").toFile()
        assertFalse(File(dir, "pyproject.toml").isFile, "fixture must start without a project")

        val result = installBlocking("iniconfig", dir)

        assertTrue(result.isSuccess, "install should succeed: ${result.exceptionOrNull()}")
        val pyproject = File(dir, "pyproject.toml")
        assertTrue(pyproject.isFile, "install should have run `uv init --bare` first")
        assertTrue(
            pyproject.readText().contains("iniconfig"),
            "the freshly initialized project's pyproject.toml should record the dependency",
        )
    }

    @Test
    fun `install reuses an existing project instead of re-initializing`() {
        val dir = createTempDirectory("tcl-install-existing").toFile()
        File(dir, "pyproject.toml").writeText(
            """
            [project]
            name = "tcl-fixture-package"
            version = "0.0.1"
            requires-python = ">=3.13"
            dependencies = []
            """.trimIndent(),
        )

        val result = installBlocking("six", dir)

        assertTrue(result.isSuccess, "install should succeed: ${result.exceptionOrNull()}")
        val pyproject = File(dir, "pyproject.toml").readText()
        assertTrue(
            pyproject.contains("name = \"tcl-fixture-package\""),
            "existing project metadata must survive untouched, proving init was not re-run",
        )
        assertTrue(pyproject.contains("six"), "the existing project's pyproject.toml should record the new dependency")
    }

    @Test
    fun `findProjectRoot walks up from a nested directory to the pyproject toml of the same repository`() {
        val root = createTempDirectory("tcl-find-root").toFile()
        File(root, ".git").mkdirs()
        File(root, "pyproject.toml").writeText("[project]\nname = \"x\"\n")
        val nested = File(root, "a/b/c").apply { mkdirs() }

        assertTrue(findProjectRoot(nested) == root.canonicalFile)
    }

    @Test
    fun `findProjectRoot uses the working directory's own pyproject toml`() {
        val dir = createTempDirectory("tcl-find-root-cwd").toFile()
        File(dir, "pyproject.toml").writeText("[project]\nname = \"x\"\n")

        assertTrue(findProjectRoot(dir) == dir.canonicalFile)
    }

    @Test
    fun `findProjectRoot does not cross a git boundary into an unrelated parent project`() {
        val parent = createTempDirectory("tcl-find-root-git-boundary").toFile()
        File(parent, "pyproject.toml").writeText("[project]\nname = \"unrelated\"\n")
        val repo = File(parent, "repo").apply { mkdirs() }
        // A worktree's `.git` is a file, not a directory: both mark the boundary.
        File(repo, ".git").writeText("gitdir: elsewhere\n")
        val nested = File(repo, "src").apply { mkdirs() }

        assertNull(findProjectRoot(nested), "the parent's pyproject.toml lies outside the repository")
    }

    @Test
    fun `findProjectRoot outside any repository does not take an ancestor's pyproject toml`() {
        val parent = createTempDirectory("tcl-find-root-no-repo").toFile()
        File(parent, "pyproject.toml").writeText("[project]\nname = \"unrelated\"\n")
        val child = File(parent, "child").apply { mkdirs() }

        assertNull(findProjectRoot(child), "with no repository boundary, only the working directory counts")
    }

    @Test
    fun `install below an unrelated project creates its own project and leaves the ancestor untouched`() {
        val parent = createTempDirectory("tcl-install-unrelated-ancestor").toFile()
        val parentPyproject =
            """
            [project]
            name = "unrelated-ancestor"
            version = "0.0.1"
            requires-python = ">=3.13"
            dependencies = []
            """.trimIndent()
        File(parent, "pyproject.toml").writeText(parentPyproject)
        val child = File(parent, "child").apply { mkdirs() }

        val result = installBlocking("six", child)

        assertTrue(result.isSuccess, "install should succeed: ${result.exceptionOrNull()}")
        assertEquals(parentPyproject, File(parent, "pyproject.toml").readText(), "the ancestor project must not be edited")
        val own = File(child, "pyproject.toml")
        assertTrue(own.isFile, "install should have initialized a project in the working directory")
        assertTrue(own.readText().contains("six"), "the new project should record the dependency")
    }

    @Test
    fun `findProjectRoot returns null when nothing is found above it`() {
        val dir = createTempDirectory("tcl-find-root-none").toFile()

        assertTrue(
            findProjectRoot(dir) == null,
            "a fresh temp directory should not accidentally find an unrelated pyproject.toml above it",
        )
    }
}
