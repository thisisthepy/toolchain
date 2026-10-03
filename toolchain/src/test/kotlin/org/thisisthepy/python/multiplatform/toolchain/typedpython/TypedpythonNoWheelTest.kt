package org.thisisthepy.python.multiplatform.toolchain.typedpython

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Without `-Ptypedpython.wheelDir` the check is skipped with a loud warning and never reaches an index
 * (`typedpython` is not ours on PyPI: a bare-name install is a package-squatting hole). Needs no
 * wheel, no `uv` and no network, so it runs everywhere, CI included. `docs/SPEC.md` section 1.18.
 */
class TypedpythonNoWheelTest {
    @Test
    fun `a project with Python and no wheel directory skips the check with a loud warning, and installs nothing`() {
        val dir = File("build/typedpython-testkit/nowheel").absoluteFile
        dir.deleteRecursively()
        File(dir, "pkg/src/main/demo").mkdirs()
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"nowheel\"\n")
        File(dir, "build.gradle.kts").writeText(
            "plugins {\n    id(\"org.thisisthepy.python.multiplatform\")\n}\n\npython {\n    localLibraryPath = \"pkg\"\n}\n",
        )
        File(dir, "pkg/pyproject.toml").writeText("[project]\nname = \"demo\"\nversion = \"0.0.1\"\n")
        File(dir, "pkg/src/main/demo/ok.py").writeText("def g(x: int) -> int:\n    return x\n")

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withTestKitDir(File("build/testkit-home").absoluteFile)
            .withPluginClasspath()
            .withArguments("typedpythonCheck", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":typedpythonCheck")?.outcome, result.output)
        assertTrue("typedpythonCheck SKIPPED" in result.output && "NOT type-checked" in result.output, result.output)
        assertTrue("-Ptypedpython.wheelDir" in result.output, result.output)
        assertFalse(File(dir, "build/typedpython/venv").exists(), "nothing was installed, from anywhere")
    }
}
