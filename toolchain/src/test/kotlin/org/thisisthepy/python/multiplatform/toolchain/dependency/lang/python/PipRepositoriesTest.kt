package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import org.thisisthepy.python.multiplatform.toolchain.dsl.PipExtension
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `defaultConfig { pip { … } }` as `(플러그인예시)build.gradle.kts` writes it, turned into the `uv add`
 * options `installPythonDependencies` passes to pypackpack's uv backend:
 *
 * | DSL | uv option |
 * |---|---|
 * | `central { setUrl(a, b, …) }` | `--default-index a`, `--index "b …"` (uv splits on spaces) |
 * | `local { url = … }` | `--find-links <dir>` (a `file:` URI becomes a path) |
 * | `autoUpdate = true` | `--upgrade` |
 * | `jit { url; localRecipes { … } }` | rejected: pypackpack cannot build from recipes yet |
 */
class PipRepositoriesTest {
    @Test
    fun `nothing declared passes no options`() {
        assertEquals(emptyMap(), resolvePipArguments(PipExtension()))
    }

    @Test
    fun `central's first url is the default index and the rest are extra indexes`() {
        val pip = PipExtension().apply {
            repositories { central { setUrl("https://mirror.example/simple", "https://extra.example/simple", "https://more.example/simple") } }
        }

        assertEquals(
            mapOf(
                "default-index" to "https://mirror.example/simple",
                "index" to "https://extra.example/simple https://more.example/simple",
            ),
            resolvePipArguments(pip),
        )
    }

    @Test
    fun `the example's central, which repeats one url, is a default index only`() {
        val pip = PipExtension().apply {
            repositories { central { setUrl("https://pypi.org/simple", "https://pypi.org/simple") } }
        }

        assertEquals(mapOf("default-index" to "https://pypi.org/simple"), resolvePipArguments(pip))
    }

    @Test
    fun `a local repository given as a file uri is passed as a find-links directory`() {
        val dir = kotlin.io.path.createTempDirectory("pip-local-repo").toFile()
        val pip = PipExtension().apply {
            repositories { local { url = dir.toPath().toUri().toString() } }
        }

        assertEquals(mapOf("find-links" to dir.absolutePath), resolvePipArguments(pip))
    }

    @Test
    fun `autoUpdate upgrades dependencies that are not pinned`() {
        val pip = PipExtension().apply { autoUpdate = true }

        assertEquals(mapOf("upgrade" to ""), resolvePipArguments(pip))
    }

    @Test
    fun `a jit repository is rejected with the reason instead of being ignored`() {
        val pip = PipExtension().apply {
            repositories {
                jit {
                    url = "http://jitpack.io"
                    localRecipes { add("path/to/recipe") }
                }
            }
        }

        val error = assertFailsWith<IllegalArgumentException> { resolvePipArguments(pip) }
        assertTrue(error.message.orEmpty().contains("jit"), "was: ${error.message}")
        assertTrue(error.message.orEmpty().contains("recipe"), "was: ${error.message}")
    }

    @Test
    fun `the jit rejection is deferred to the install task rather than failing configuration`() {
        val pip = PipExtension().apply { repositories { jit { url = "http://jitpack.io" } } }

        val resolved = resolvePipSettings(pip)

        assertNull(resolved.arguments)
        assertTrue(resolved.rejection.orEmpty().contains("jit"))
    }

    @Test
    fun `the resolved options reach uv`() {
        val packageDir = kotlin.io.path.createTempDirectory("pip-options-reach-uv").toFile()
        File(packageDir, "pyproject.toml").writeText(
            """
            [project]
            name = "fixture-pip-options"
            version = "0.0.1"
            requires-python = ">=3.9"
            dependencies = []
            """.trimIndent(),
        )
        val deadIndex = File(packageDir, "no-such-index").toPath().toUri().toString()
        val pip = PipExtension().apply { repositories { central { setUrl(deadIndex) } } }

        val error = assertFailsWith<org.gradle.api.GradleException> {
            installWithPackpack(packageDir, listOf("iniconfig"), resolvePipArguments(pip))
        }
        assertTrue(
            error.message.orEmpty().contains("no-such-index"),
            "uv should have been sent to the declared index, was: ${error.message}",
        )
    }
}
