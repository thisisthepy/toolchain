package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * python-multiplatform #25, end to end (`docs/SPEC.md` §1.6, *native*): a package with one `@compiled`
 * function, bundled by a real Gradle build at `compileLevel = "native"` for the host, imports its
 * compiled extension with the free-threaded 3.15t interpreter and prints the interpreted result.
 *
 * Where this lives: team lead, 2026-11-21 21:15 (toolchain report, Decision 5 (a)): "the env-gated
 * opt-in step in the existing `consumer` job of toolchain test.yml (no new workflow)". The fixture
 * under `src/test/resources/native-e2e/` is the typedpython teammate's (2026-11-21).
 *
 * Opt-in with `TOOLCHAIN_NATIVE_E2E=1`, like pypackpack's `PPP_CARGO_REAL_BUILD`: it needs the
 * TypedPython gate in `mavenLocal()` (python-multiplatform's `:python-multiplatform-ksp:publishToMavenLocal`,
 * which the consumer job runs), a C compiler, `uv` and the network (the 3.15t runtime from
 * python-build-standalone). Without the variable it is skipped with that reason.
 *
 * The negative control builds the same fixture at `bytecode`: the module must then load from a `.pyc`,
 * so the extension-suffix assertion that passes at `native` fails there. If both passed, the test would
 * not be telling `native` apart from anything else.
 *
 * A bare python-build-standalone 3.15t has no `compiled` builtin (python-multiplatform's embed installs
 * it, its SPEC L-8; the typedpython wheel does it through a `.pth`), and the extension runs the module's
 * uncompiled source at import, so [IMPORT_AND_RUN] installs it first, the way L-8 does.
 */
class NativeLevelE2ETest {
    @BeforeTest
    fun optIn() {
        assumeTrue(
            "SKIPPED: set TOOLCHAIN_NATIVE_E2E=1 (needs the TypedPython gate in mavenLocal, a C compiler, uv and the network)",
            System.getenv("TOOLCHAIN_NATIVE_E2E") == "1",
        )
    }

    private val testKitHome = File("build/testkit-home").absoluteFile
    private val fixture = File("src/test/resources/native-e2e").absoluteFile

    private val projectsRoot = File("build/native-e2e-testkit").absoluteFile

    /**
     * The `.venv` both projects compile their remaining `.py` with. `native` byte-compiles every module
     * TypedPython made no extension for (the fixture's empty `demo/__init__.py`), as `bytecode` does,
     * and `pypackpack` finds that interpreter in a `.venv` at or above the package (`docs/SPEC.md` §1.6).
     * The fixture declares no dependency, so `installPythonDependencies` makes none; the user's other
     * way, `uv venv --python <X.Y>`, makes it here, once, above both level directories.
     */
    private fun ensureVenv() {
        if (File(projectsRoot, ".venv/pyvenv.cfg").isFile) return
        projectsRoot.mkdirs()
        val process = ProcessBuilder("uv", "venv", "--python", "3.15t", File(projectsRoot, ".venv").path)
            .directory(projectsRoot)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "uv venv did not finish")
        assertEquals(0, process.exitValue(), "uv venv --python 3.15t failed:\n$output")
    }

    private fun project(level: String): File {
        ensureVenv()
        val dir = File(projectsRoot, level)
        dir.deleteRecursively()
        fixture.copyRecursively(File(dir, "pkg"))
        File(dir, "pkg/expected.txt").delete()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"native-e2e-$level\"\n")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                id("org.thisisthepy.python.multiplatform")
            }

            // The TypedPython gate zip (DEFAULT_TYPEDPYTHON_GATE) comes from here.
            repositories {
                mavenLocal()
            }

            python {
                compileSdk = PY3_15_0
                localLibraryPath = "pkg"
                buildTypes {
                    getByName("release") { compileLevel = "$level" }
                }
            }
            """.trimIndent() + "\n",
        )
        return dir
    }

    private fun bundle(dir: File) {
        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withTestKitDir(testKitHome)
            .withPluginClasspath()
            .withArguments("buildPython", "-Ppython.buildType=release", "--stacktrace")
            .forwardOutput()
            .build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":typedpythonCheck")?.outcome, "the gate must run, not be skipped")
        assertEquals(TaskOutcome.SUCCESS, result.task(":buildPython")?.outcome)
    }

    /** The 3.15t interpreter `native` installed under `build/pythonRuntime/<host>/<version>`. */
    private fun interpreter(dir: File): File {
        val runtimes = File(dir, "build/pythonRuntime")
        val found = runtimes.walkTopDown()
            .filter { it.isFile && it.parentFile.name == "bin" && it.name == "python3.15t" && it.canExecute() }
            .firstOrNull()
        return assertNotNull(found, "no bin/python3.15t under $runtimes")
    }

    private fun run(python: File, bundle: File): Pair<Int, String> {
        val process = ProcessBuilder(python.path, "-c", IMPORT_AND_RUN)
            .redirectErrorStream(true)
            .apply {
                environment().remove("PYTHONHOME")
                environment()["PYTHONPATH"] = bundle.path
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the interpreter did not finish")
        return process.exitValue() to output
    }

    @Test
    fun `a compiled module bundled at native imports as an extension and gives the interpreted result`() {
        val native = project("native")
        bundle(native)
        val bundleDir = File(native, "build/pythonBundle/python")
        val extensions = File(bundleDir, "demo").listFiles { f -> f.name.startsWith("kernel.") && f.extension in setOf("so", "pyd") }
        assertTrue(!extensions.isNullOrEmpty(), "no compiled demo/kernel extension in $bundleDir")

        val python = interpreter(native)
        val (exit, output) = run(python, bundleDir)
        assertEquals(0, exit, output)
        assertEquals(File(fixture, "expected.txt").readText().trim(), output.trim())

        // Negative control: the same fixture at bytecode is not an extension, so the assertion fails.
        val bytecode = project("bytecode")
        bundle(bytecode)
        val (controlExit, controlOutput) = run(python, File(bytecode, "build/pythonBundle/python"))
        assertNotEquals(0, controlExit, "bytecode level imported demo.kernel as an extension:\n$controlOutput")
        assertTrue("AssertionError" in controlOutput, controlOutput)
    }

    private companion object {
        /** The typedpython teammate's command (typedpython report, "For toolchain: #25 fixture"). */
        const val IMPORT_AND_RUN =
            "import builtins; builtins.compiled = (lambda f=None, /, **k: (lambda g: g) if f is None else f); " +
                "import importlib.machinery as m, demo.kernel as k; " +
                "assert k.__file__.endswith(tuple(m.EXTENSION_SUFFIXES)), k.__file__; k.main()"
    }
}
