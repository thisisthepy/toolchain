package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import org.gradle.api.GradleException
import org.thisisthepy.python.multiplatform.packpack.compile.extension.CompileProcessResult
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.CrossSdistBuilder
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.CrossTargetInstaller
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import org.thisisthepy.python.multiplatform.toolchain.targetInstallArguments
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.10: [installDependenciesForTarget] -- what `InstallTargetDependenciesTask` runs --
 * makes a real `uv pip install --target <dir> --python-platform <triple>` through `pypackpack`'s
 * `UVBackend.installDependenciesToTarget`, from a requirement list rather than the package's
 * `pyproject.toml`.
 *
 * **Needs network access and `uv`**, like `InstallDependenciesTaskTest`: `six==1.17.0` comes from
 * PyPI. It is pure Python (`py3-none-any`), so the test does not depend on which platform wheels
 * PyPI publishes; `pypackpack`'s `UVBackendRealInstallTest` covers native Android/iOS wheels.
 */
class InstallTargetDependenciesTaskTest {
    @Test
    fun `six is installed for aarch64-linux-android into the install directory and nothing else lands there`() {
        val root = createTempDirectory("target-deps").toFile()
        try {
            val installDir = File(root, "build/pythonDeps/androidArm64")
            val requirementsDir = File(root, "build/tmp/installPythonDependenciesAndroidArm64")
            // A package dropped from the DSL must leave the bundle: the directory is cleared first.
            installDir.mkdirs()
            File(installDir, "stale.py").writeText("")

            installDependenciesForTarget(
                requirements = listOf("six==1.17.0"),
                pythonPlatform = "aarch64-linux-android",
                installDir = installDir,
                requirementsDir = requirementsDir,
                extraArgs = targetInstallArguments(PythonVersion.parse("3.14.7"), emptyMap()),
            )

            assertTrue(File(installDir, "six.py").isFile, "six.py missing from ${installDir.listFiles()?.map { it.name }}")
            val wheel = File(installDir, "six-1.17.0.dist-info/WHEEL")
            assertTrue(wheel.readText().contains("Tag: py3-none-any"), wheel.readText())
            assertFalse(File(installDir, "stale.py").exists(), "the install directory was not cleared first")
            // The list goes to uv as arguments (pypackpack#36): no generated pyproject anywhere.
            assertFalse(File(installDir, "pyproject.toml").exists(), "a pyproject.toml would be bundled")
            assertFalse(File(requirementsDir, "pyproject.toml").exists(), "the requirements are passed as a list now")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `no requirements leaves an empty install directory without calling uv`() {
        val root = createTempDirectory("target-deps-empty").toFile()
        try {
            val installDir = File(root, "pythonDeps/host")
            installDir.mkdirs()
            File(installDir, "stale.py").writeText("")

            assertEquals("", installDependenciesForTarget(emptyList(), "aarch64-apple-darwin", installDir, File(root, "tmp")))

            assertTrue(installDir.isDirectory)
            assertEquals(emptyList(), installDir.listFiles().orEmpty().map { it.name })
            assertFalse(File(root, "tmp").exists(), "no requirements file is written when there is nothing to install")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * #152: with `pip { jit { localRecipes } }` the requirements go to pypackpack's `CrossTargetInstaller`. It pins
     * them, installs the pins with `--only-binary :all:` into the install directory, and builds a pin without a wheel
     * in the cross environment with the recipe directories. Here `uv` is played by a fake, so nothing is downloaded.
     */
    @Test
    fun `with jit the requirements go through pypackpack's cross target installer with the recipe directories`() {
        val root = createTempDirectory("target-deps-jit").toFile()
        try {
            val installDir = File(root, "build/pythonDeps/androidArm64")
            val requirementsDir = File(root, "build/tmp/installPythonDependenciesAndroidArm64")
            val crossDir = File(root, "build/pythonCross")
            val recipes = File(root, "recipes").apply { mkdirs() }
            val commands = mutableListOf<List<String>>()
            val builds = mutableListOf<Triple<File, String, List<File>>>()
            var installs = 0
            val builder = object : CrossSdistBuilder() {
                override fun buildFromPyPI(packageDir: File, canonicalTarget: String, packageSpec: String, recipeDirs: List<File>): Result<File> {
                    builds += Triple(packageDir, packageSpec, recipeDirs)
                    return Result.success(File(wheelsDir(packageDir, canonicalTarget).apply { mkdirs() }, "regex.whl"))
                }
            }
            val installer = CrossTargetInstaller(builder) { command, _, _ ->
                commands += command
                if (command[2] == "compile") {
                    File(command[command.indexOf("--output-file") + 1]).writeText("regex==2026.11.23\n")
                    CompileProcessResult(0, "")
                } else if (++installs == 1) {
                    CompileProcessResult(1, "Because regex==2026.11.23 has no usable wheels and you require regex==2026.11.23, we can conclude")
                } else {
                    CompileProcessResult(0, "Installed 1 package")
                }
            }

            installDependenciesForTarget(
                requirements = listOf("regex"),
                pythonPlatform = "aarch64-linux-android",
                installDir = installDir,
                requirementsDir = requirementsDir,
                extraArgs = targetInstallArguments(PythonVersion.parse("3.15.0t"), emptyMap()),
                jit = JitBuild(listOf(recipes), crossDir),
                crossInstaller = installer,
            )

            assertEquals(listOf("uv", "pip", "compile"), commands[0].take(3))
            assertEquals(listOf(Triple(crossDir, "regex==2026.11.23", listOf(recipes))), builds)
            val install = commands.last()
            assertTrue(install.windowed(2).any { it == listOf("--target", installDir.absolutePath) }, install.toString())
            assertTrue(install.windowed(2).any { it == listOf("--only-binary", ":all:") }, install.toString())
            assertTrue(install.windowed(2).any { it == listOf("--python", "3.15t") }, "the free-threaded interpreter is kept: $install")

            val missing = assertFailsWith<GradleException> {
                installDependenciesForTarget(listOf("regex"), "aarch64-linux-android", installDir, requirementsDir, jit = JitBuild(listOf(File(root, "nope")), crossDir), crossInstaller = installer)
            }
            assertTrue("nope" in missing.message.orEmpty() && "localRecipes" in missing.message.orEmpty(), missing.message)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a requirements directory inside the install directory is refused`() {
        val root = createTempDirectory("target-deps-nested").toFile()
        try {
            val installDir = File(root, "pythonDeps/host")
            assertFailsWith<IllegalArgumentException> {
                installDependenciesForTarget(listOf("six"), "aarch64-apple-darwin", installDir, File(installDir, "req"))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
