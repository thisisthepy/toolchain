package org.thisisthepy.python.multiplatform.toolchain

import org.gradle.api.GradleException
import org.thisisthepy.python.multiplatform.toolchain.bundle.BUNDLE_RUNTIME_ROOT
import org.thisisthepy.python.multiplatform.toolchain.bundle.PypackpackInterpreterInstaller
import org.thisisthepy.python.multiplatform.toolchain.bundle.RUNTIME_MANIFEST_FILE_NAME
import org.thisisthepy.python.multiplatform.toolchain.bundle.carryInterpreterIntoBundle
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonSdk
import org.thisisthepy.python.multiplatform.toolchain.dsl.resolvePythonSdk
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.2 and §1.12, issue #18: [planInterpreter] decides, per variant, what the resolved
 * embedLevel and compileSdk mean for the interpreter (AGENTS.md §14). No Gradle project, no network.
 */
class InterpreterPlanTest {
    private fun sdk(requested: String) = resolvePythonSdk(PythonSdk().apply { assign(requested) })

    private val android = "aarch64-linux-android"

    @Test
    fun `level 0 acquires and records nothing`() {
        val plan = planInterpreter(0, android, sdk("3.14.7"))
        assertEquals(InterpreterPlan.None, plan)
        assertNull(plan.recordedVersion)
    }

    @Test
    fun `level 1 records the compileSdk release and bundles nothing`() {
        assertEquals(InterpreterPlan.External("3.14.7"), planInterpreter(1, "aarch64-apple-darwin", sdk("3.14")))
        assertEquals(InterpreterPlan.External(null), planInterpreter(1, "aarch64-apple-darwin", null))
        assertEquals("3.13.0", planInterpreter(1, "aarch64-apple-darwin", sdk("3.13.0")).recordedVersion)
    }

    @Test
    fun `level 2 embeds the compileSdk release for the variant's own triple`() {
        val plan = assertIs<InterpreterPlan.Embed>(planInterpreter(2, android, sdk("3.14")))
        assertEquals("3.14.7", plan.version)
        assertEquals(android, plan.target)
        assertEquals("3.14.7", plan.recordedVersion)
        assertEquals("acquirePythonInterpreterAarch64LinuxAndroidPy3_14_7", plan.taskName)
        assertEquals(File("/b/pythonRuntime/aarch64-linux-android/3.14.7"), plan.runtimeDir(File("/b")))
    }

    @Test
    fun `the same pair gives the same task, a different pair a different one`() {
        val debug = planInterpreter(2, android, sdk("3.14.7")) as InterpreterPlan.Embed
        val release = planInterpreter(2, android, sdk("3.14.7")) as InterpreterPlan.Embed
        val ios = planInterpreter(2, "arm64-apple-ios", sdk("3.14.7")) as InterpreterPlan.Embed
        val older = planInterpreter(2, android, sdk("3.13.0")) as InterpreterPlan.Embed
        assertEquals(debug.taskName, release.taskName)
        assertEquals(setOf(debug.taskName, ios.taskName, older.taskName).size, 3)
    }

    @Test
    fun `level 2 without a providable compileSdk is refused with a reason`() {
        val missing = assertIs<InterpreterPlan.Refused>(planInterpreter(2, android, null))
        assertTrue("compileSdk" in missing.reason && android in missing.reason, missing.reason)

        val unprovided = assertIs<InterpreterPlan.Refused>(planInterpreter(2, android, sdk("3.11.9-alpha")))
        assertTrue("3.11.9-alpha" in unprovided.reason, unprovided.reason)
    }

    @Test
    fun `the embed record carries the interpreter version and whether it is bundled`() {
        val embedded = embedRecordJson(2, "android", null, "3.14.7", interpreterBundled = true)
        assertTrue(embedded.contains("\"interpreterVersion\": \"3.14.7\""), embedded)
        assertTrue(embedded.contains("\"interpreterBundled\": true"), embedded)

        val external = embedRecordJson(1, "macos", null, "3.14.7", interpreterBundled = false)
        assertTrue(external.contains("\"interpreterVersion\": \"3.14.7\""), external)
        assertTrue(external.contains("\"interpreterBundled\": false"), external)

        assertTrue(embedRecordJson(0, "linux", null).contains("\"interpreterVersion\": null"))
    }

    @Test
    fun `pypackpack's refusal of an unsupported pair is the failure, and nothing is written`() {
        val destination = createTempDirectory("runtime-unsupported").toFile()

        val error = PypackpackInterpreterInstaller.install("3.13.0", android, destination).exceptionOrNull()

        val message = error?.message.orEmpty()
        // PythonDistributions.resolve's own words for a known pair with no pinned digest.
        assertTrue("3.13.0" in message && android in message && "no pinned SHA-256" in message, message)
        assertTrue(destination.listFiles().isNullOrEmpty())
    }

    @Test
    fun `the acquired tree is carried into runtime beside python, links kept, with a manifest`() {
        val runtime = createTempDirectory("runtime-tree").toFile()
        File(runtime, "bin/python3.14").apply { parentFile.mkdirs() }.writeText("#!fake\n")
        Files.createSymbolicLink(File(runtime, "bin/python3").toPath(), File("python3.14").toPath())
        File(runtime, "lib/python3.14/os.py").apply { parentFile.mkdirs() }.writeText("")
        val bundle = createTempDirectory("bundle").toFile()
        File(bundle, "python/app/main.py").apply { parentFile.mkdirs() }.writeText("")
        File(bundle, "$BUNDLE_RUNTIME_ROOT/stale.txt").apply { parentFile.mkdirs() }.writeText("old")

        val copied = carryInterpreterIntoBundle(runtime, bundle, "3.14.7", android)

        assertEquals(3, copied)
        assertTrue(File(bundle, "python/app/main.py").isFile)
        assertTrue(File(bundle, "runtime/lib/python3.14/os.py").isFile)
        assertTrue(Files.isSymbolicLink(File(bundle, "runtime/bin/python3").toPath()))
        assertTrue(!File(bundle, "runtime/stale.txt").exists())
        val manifest = File(bundle, RUNTIME_MANIFEST_FILE_NAME).readText()
        assertTrue("\"pythonVersion\": \"3.14.7\"" in manifest && "\"root\": \"runtime\"" in manifest, manifest)
    }

    @Test
    fun `an interpreter that was not acquired fails carrying it`() {
        val bundle = createTempDirectory("bundle-missing").toFile()
        val error = assertFailsWith<GradleException> {
            carryInterpreterIntoBundle(File(bundle, "absent"), bundle, "3.14.7", android)
        }
        assertTrue("3.14.7" in error.message.orEmpty(), error.message)
    }
}
