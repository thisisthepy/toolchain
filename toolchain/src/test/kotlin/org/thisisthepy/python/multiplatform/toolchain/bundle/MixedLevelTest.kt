package org.thisisthepy.python.multiplatform.toolchain.bundle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `compileLevel = "mixed"` (toolchain#128), end to end through `pypackpack`'s `ResourceBundler`:
 * developer modules ship as bytecode, a library's prebuilt extension (a `libDirs` file) ships as it is.
 * Real `compileall` through a `.venv` linked to the host's `python3`, as in [BytecodeLevelTest].
 */
class MixedLevelTest {
    private fun fixturePackageDir(): File {
        val root = kotlin.io.path.createTempDirectory("packpack-mixed-fixture").toFile()
        File(root, "pyproject.toml").writeText(
            """
            [project]
            name = "fixture-package"
            version = "0.0.1"
            """.trimIndent(),
        )
        File(root, "src/main/fixture_package").apply { mkdirs() }.resolve("__init__.py").writeText("VALUE = 41 + 1\n")
        File(root, "src/main/fixture_package/core.py").writeText("def ping():\n    return 'pong'\n")
        return root
    }

    private fun installVenvPython(packageDir: File) {
        val process = ProcessBuilder("which", "python3").redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0 && path.isNotEmpty()) { "No 'python3' on PATH; cannot exercise 'mixed'." }
        val bin = File(packageDir, ".venv/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(bin, "python3").toPath(), File(path).toPath())
    }

    private fun libDirWithExtension(): File {
        val lib = kotlin.io.path.createTempDirectory("packpack-mixed-lib").toFile()
        File(lib, "foo").mkdirs()
        File(lib, "foo/__init__.py").writeText("")
        File(lib, "foo/foo.cpython-315t-darwin.so").writeBytes(byteArrayOf(1, 2, 3, 4))
        return lib
    }

    private fun filesUnder(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    @Test
    fun `mixed release ships pyc only for developer modules`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)
        val out = kotlin.io.path.createTempDirectory("packpack-mixed-release").toFile()

        val result = bundleWithPackpack(packageDir, "macos", "release", out, buildLevel = "mixed")

        val files = filesUnder(result.outputDir.resolve("python"))
        assertEquals(listOf("fixture_package/__init__.pyc", "fixture_package/core.pyc"), files)
        assertTrue(result.manifestFile.readText().contains("\"buildLevel\": \"mixed\""))
    }

    @Test
    fun `mixed carries a libDirs extension as is`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)
        val lib = libDirWithExtension()
        val out = kotlin.io.path.createTempDirectory("packpack-mixed-lib-out").toFile()

        val result = bundleWithPackpack(packageDir, "macos", "release", out, buildLevel = "mixed", libDirs = listOf(lib))

        val python = result.outputDir.resolve("python")
        val so = python.walkTopDown().firstOrNull { it.name == "foo.cpython-315t-darwin.so" }
        assertNotNull(so, "the library extension must be in the bundle: ${filesUnder(python)}")
        assertEquals(listOf<Byte>(1, 2, 3, 4), so.readBytes().toList())
        assertTrue(result.manifestFile.readText().contains("foo.cpython-315t-darwin.so"))
        assertFalse(filesUnder(python).any { it.endsWith("core.py") }, "developer .py must be gone in release")
    }

    @Test
    fun `mixed debug keeps py beside pyc`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)
        val out = kotlin.io.path.createTempDirectory("packpack-mixed-debug").toFile()

        val result = bundleWithPackpack(packageDir, "macos", "debug", out, buildLevel = "mixed")

        assertEquals(
            listOf(
                "fixture_package/__init__.py",
                "fixture_package/__init__.pyc",
                "fixture_package/core.py",
                "fixture_package/core.pyc",
            ),
            filesUnder(result.outputDir.resolve("python")),
        )
    }

    @Test
    fun `a mixed variant without a venv is refused naming compileLevel mixed`() {
        val packageDir = fixturePackageDir()

        val rejection = assertNotNull(bytecodeInterpreterRejection(packageDir, compileSdk = null, buildLevel = "mixed"))

        assertTrue(rejection.contains("compileLevel 'mixed'"), rejection)
        assertFalse(rejection.contains("'bytecode'"), rejection)
        assertTrue(rejection.contains("uv venv"), rejection)
    }
}
