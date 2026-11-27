package org.thisisthepy.python.multiplatform.toolchain.hotreload

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * docs/SPEC.md §1.14 (toolchain#150 M2; Decision (b), team lead 2026-11-27): `hotReloadPython` pushes the staged
 * `.py` files whose SHA-256 differs from the app's copy, and nothing else.
 */
class HotReloadPlanTest {
    @Test
    fun `an edited or new file is pushed and an unchanged one is not`() {
        val local = mapOf("app/main.py" to "new", "dep/lib.py" to "same", "app/added.py" to "x")
        val device = mapOf("app/main.py" to "old", "dep/lib.py" to "same", "device_only.py" to "y")

        assertEquals(listOf("app/added.py", "app/main.py"), hotReloadPushPlan(local, device))
    }

    @Test
    fun `the staged python files are keyed by their path relative to the payload, other files are left out`() {
        val payload = createTempDirectory("hot-reload-payload").toFile()
        payload.resolve("app").mkdirs()
        payload.resolve("app/main.py").writeText("X = 1\n")
        payload.resolve("app/data.json").writeText("{}")
        payload.resolve("python_multiplatform_devagent.enable").writeText("")

        val sources = stagedPythonSources(payload)

        assertEquals(setOf("app/main.py"), sources.keys)
        assertEquals(sha256Hex("X = 1\n".toByteArray()), sources.getValue("app/main.py"))
    }
}
