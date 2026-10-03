package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `compileLevel = "bytecode"` (Issue #15), end to end through `pypackpack`'s `ResourceBundler`, plus
 * the interpreter check toolchain runs before it ([bytecodeInterpreterRejection]).
 *
 * `ResourceBundler` compiles with the interpreter at `<dir>/.venv/{bin/python3,bin/python,Scripts/python.exe}`,
 * found by walking up from the package directory. The fixture links the host's `python3` into
 * `<package>/.venv/bin/python3`, the way pypackpack's own `ResourceBundlerTest.installVenvPython`
 * does, so the real lookup and a real `compileall` run. Needs `python3` on `PATH`.
 */
class BytecodeLevelTest {
    private fun fixturePackageDir(): File {
        val root = kotlin.io.path.createTempDirectory("packpack-bytecode-fixture").toFile()
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

    private fun hostPython3(): File {
        val process = ProcessBuilder("which", "python3").redirectErrorStream(true).start()
        val path = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        require(exit == 0 && path.isNotEmpty()) { "No 'python3' on PATH; cannot exercise the 'bytecode' level." }
        return File(path)
    }

    private fun installVenvPython(packageDir: File): File {
        val bin = File(packageDir, ".venv/bin").apply { mkdirs() }
        val link = File(bin, "python3")
        Files.createSymbolicLink(link.toPath(), hostPython3().toPath())
        return link
    }

    private fun writePyvenvCfg(packageDir: File, content: String) {
        File(packageDir, ".venv").mkdirs()
        File(packageDir, ".venv/pyvenv.cfg").writeText(content)
    }

    private fun filesUnder(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    @Test
    fun `bytecode for debug keeps each py and adds a sibling pyc`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bytecode-debug").toFile()

        val result = bundleWithPackpack(packageDir, "macos", "debug", outputDir, buildLevel = "bytecode")

        val python = result.outputDir.resolve("python")
        assertEquals(
            listOf(
                "fixture_package/__init__.py",
                "fixture_package/__init__.pyc",
                "fixture_package/core.py",
                "fixture_package/core.pyc",
            ),
            filesUnder(python),
        )
        assertTrue(result.manifestFile.readText().contains("\"buildLevel\": \"bytecode\""))
        assertTrue(result.manifestFile.readText().contains("\"path\": \"python/fixture_package/core.pyc\""))
    }

    @Test
    fun `bytecode for release ships only pyc, no py anywhere in the payload`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bytecode-release").toFile()

        val result = bundleWithPackpack(packageDir, "macos", "release", outputDir, buildLevel = "bytecode")

        val files = filesUnder(result.outputDir.resolve("python"))
        assertEquals(listOf("fixture_package/__init__.pyc", "fixture_package/core.pyc"), files)
        assertFalse(files.any { it.endsWith(".py") }, "release bytecode must drop every .py: $files")
        assertFalse(result.manifestFile.readText().contains(".py\""), "manifest must list no .py source")
    }

    @Test
    fun `a missing venv is refused by toolchain, naming the directory and how to create one`() {
        val packageDir = fixturePackageDir()

        val rejection = assertNotNull(bytecodeInterpreterRejection(packageDir, compileSdk = null))

        assertTrue(rejection.contains(".venv"), rejection)
        assertTrue(rejection.contains(packageDir.canonicalPath), rejection)
        assertTrue(rejection.contains("uv venv"), rejection)
    }

    @Test
    fun `a venv found further up the tree is accepted, as pypackpack's locator walks upward`() {
        val project = fixturePackageDir()
        installVenvPython(project)
        val nested = File(project, "packages/inner").apply { mkdirs() }

        assertNotNull(locateVenvInterpreter(nested))
        assertNull(bytecodeInterpreterRejection(nested, compileSdk = null))
    }

    @Test
    fun `a venv whose minor version differs from compileSdk is refused, since pyc magic numbers differ`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)
        writePyvenvCfg(packageDir, "home = /usr/bin\nversion = 3.12.4\n")

        val rejection = assertNotNull(bytecodeInterpreterRejection(packageDir, PythonVersion.parse("3.13.0")))

        assertTrue(rejection.contains("3.12"), rejection)
        assertTrue(rejection.contains("3.13"), rejection)
    }

    @Test
    fun `a venv matching compileSdk's minor version is accepted, whatever the micro`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)
        writePyvenvCfg(packageDir, "home = /usr/bin\nimplementation = CPython\nversion_info = 3.14.0\n")

        assertNull(bytecodeInterpreterRejection(packageDir, PythonVersion.parse("3.14.7")))
    }

    @Test
    fun `pyvenv cfg is read in both the stdlib venv and the uv form`() {
        val stdlib = fixturePackageDir().also { installVenvPython(it); writePyvenvCfg(it, "version = 3.13.0\n") }
        val uv = fixturePackageDir().also { installVenvPython(it); writePyvenvCfg(it, "version_info = 3.14.7\n") }
        val virtualenv = fixturePackageDir().also { installVenvPython(it); writePyvenvCfg(it, "version_info = 3.12.1.final.0\n") }

        assertEquals(3 to 13, venvPythonVersion(locateVenvInterpreter(stdlib)!!))
        assertEquals(3 to 14, venvPythonVersion(locateVenvInterpreter(uv)!!))
        assertEquals(3 to 12, venvPythonVersion(locateVenvInterpreter(virtualenv)!!))
    }

    @Test
    fun `a venv with no pyvenv cfg cannot be compared, so it is not refused`() {
        val packageDir = fixturePackageDir()
        installVenvPython(packageDir)

        assertNull(venvPythonVersion(locateVenvInterpreter(packageDir)!!))
        assertNull(bytecodeInterpreterRejection(packageDir, PythonVersion.parse("3.13.0")))
    }
}
