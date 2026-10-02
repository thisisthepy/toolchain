package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import org.thisisthepy.python.multiplatform.toolchain.bundle.locateVenvInterpreter
import org.thisisthepy.python.multiplatform.toolchain.bundle.venvPythonVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives one more DSL-to-`pypackpack` delegation all the way through, the way
 * `BuildPythonArtifactTaskTest` does for `bundleWithPackpack`: [installWithPackpack] (the function
 * [InstallDependenciesTask] calls from its `@TaskAction`) must actually invoke `pypackpack`'s `uv`
 * dependency backend (`org.thisisthepy.python.multiplatform.packpack.dependency.backend.BaseInterface.create(BackendType.UV)`)
 * and produce its real on-disk effect -- a genuine `uv add`, not a toolchain stand-in.
 *
 * Before this task's implementation exists, this test fails to compile (no `installWithPackpack`
 * function on the classpath) rather than failing an assertion -- that is the pre-implementation
 * failure this file is meant to record, per this repository's TDD rule. Confirmed failing to
 * compile against the pre-delegation `InstallDependenciesTask.kt` (no such function existed) before
 * `installWithPackpack` was added.
 *
 * This test needs real network access (PyPI, through `uv add`) -- `iniconfig` is chosen because it
 * is small and dependency-free, but a flaky network still fails this test. That is a genuine
 * property of delegating to `uv`, not a test artifact; `pypackpack`'s own `UVInterfaceTest` avoids
 * this by recording commands instead of executing them, which was considered here but rejected: it
 * would only prove toolchain calls the right Kotlin function, not that the delegation produces real
 * `uv` output, which is exactly what `BuildPythonArtifactTaskTest`'s bar for this repository is.
 */
class InstallDependenciesTaskTest {
    private fun fixturePackageDir(): File {
        val root = kotlin.io.path.createTempDirectory("packpack-deps-fixture").toFile()
        File(root, "pyproject.toml").writeText(
            """
            [project]
            name = "fixture-deps-package"
            version = "0.0.1"
            requires-python = ">=3.13"
            dependencies = []
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `installWithPackpack delegates to packpack's uv backend and records a real dependency`() {
        val packageDir = fixturePackageDir()

        val output = installWithPackpack(packageDir, listOf("iniconfig"))

        assertTrue(output.contains("iniconfig", ignoreCase = true), "uv's own output should mention the package it added: $output")
        val pyproject = File(packageDir, "pyproject.toml").readText()
        assertTrue(
            pyproject.contains("iniconfig"),
            "pyproject.toml should record the dependency packpack's uv backend actually added, not a toolchain stand-in",
        )
    }

    /**
     * The `.venv` `uv add` leaves in the package directory is the one `compileLevel = "bytecode"`
     * compiles with (Issue #15): pypackpack's `ResourceBundler` looks for `<dir>/.venv/bin/python3`
     * walking up from the package, and toolchain's `bytecodeInterpreterRejection` reads its
     * `pyvenv.cfg` to compare minor versions with `compileSdk`. This pins both against real `uv`.
     */
    @Test
    fun `the venv uv add creates is one the bytecode level can find and read`() {
        val packageDir = fixturePackageDir()

        installWithPackpack(packageDir, listOf("iniconfig"))

        val interpreter = assertNotNull(locateVenvInterpreter(packageDir), "uv add should create <package>/.venv")
        // The venv directory, not the interpreter, is canonicalised: on Linux `.venv/bin/python3` is a
        // symlink to the system interpreter, which canonicalPath would follow out of the venv.
        assertEquals(File(packageDir, ".venv").canonicalFile, interpreter.absoluteFile.parentFile.parentFile.canonicalFile, interpreter.path)
        val (major, minor) = assertNotNull(venvPythonVersion(interpreter), "uv's pyvenv.cfg should carry version_info")
        assertTrue(major == 3 && minor >= 13, "requires-python >=3.13, got $major.$minor")
    }

    @Test
    fun `installWithPackpack is a no-op for an empty dependency list`() {
        val packageDir = fixturePackageDir()

        val output = installWithPackpack(packageDir, emptyList())

        assertTrue(output.isEmpty())
        assertTrue(!File(packageDir, "pyproject.toml").readText().contains("dependencies = [\n"))
    }
}
