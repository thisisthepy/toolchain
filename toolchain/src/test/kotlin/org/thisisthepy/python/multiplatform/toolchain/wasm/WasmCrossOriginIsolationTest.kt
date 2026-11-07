package org.thisisthepy.python.multiplatform.toolchain.wasm

import org.gradle.api.tasks.Copy
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `docs/SPEC.md` section 1.20, issue `toolchain#81`: WASM apps ship cross-origin isolation by default
 * (dev-server headers, `_headers`, `coi-serviceworker.js` fallback, and `coi-check.html` diagnostic page).
 */
class WasmCrossOriginIsolationTest {
    private fun tmp(): File = createTempDirectory("wasm-coi-test").toFile()

    @Test
    fun `default headers use COOP same-origin, COEP require-corp and CORP same-origin`() {
        val policy = resolveWasmCoepPolicy(null)
        assertEquals(DEFAULT_WASM_COEP_POLICY, policy)
        assertEquals(
            mapOf(
                "Cross-Origin-Opener-Policy" to "same-origin",
                "Cross-Origin-Embedder-Policy" to "require-corp",
                "Cross-Origin-Resource-Policy" to "same-origin",
            ),
            wasmCrossOriginHeaders(policy),
        )
    }

    @Test
    fun `credentialless COEP is accepted and invalid values are rejected naming both options`() {
        assertEquals("credentialless", resolveWasmCoepPolicy("credentialless"))
        assertEquals("credentialless", wasmCrossOriginHeaders("credentialless")["Cross-Origin-Embedder-Policy"])

        val error = assertFailsWith<IllegalArgumentException> {
            resolveWasmCoepPolicy("unsafe-none")
        }
        assertTrue("require-corp" in error.message.orEmpty(), error.message)
        assertTrue("credentialless" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `renderWasmHeadersFile emits Cloudflare Pages and Netlify _headers format`() {
        val text = renderWasmHeadersFile("require-corp")
        assertTrue(text.contains("/*\n"), text)
        assertTrue(text.contains("  Cross-Origin-Opener-Policy: same-origin\n"), text)
        assertTrue(text.contains("  Cross-Origin-Embedder-Policy: require-corp\n"), text)
        assertTrue(text.contains("  Cross-Origin-Resource-Policy: same-origin\n"), text)
    }

    @Test
    fun `renderWasmCoiServiceWorkerScript re-serves with isolation headers and states both costs`() {
        val script = renderWasmCoiServiceWorkerScript("require-corp")
        assertTrue("one reload on the first visit" in script, script)
        assertTrue("no effect when service workers are unavailable" in script, script)
        assertTrue("self.crossOriginIsolated" in script, script)
        assertTrue("Cross-Origin-Opener-Policy" in script && "same-origin" in script, script)
        assertTrue("Cross-Origin-Embedder-Policy" in script && "require-corp" in script, script)
        assertTrue("Cross-Origin-Resource-Policy" in script, script)
        assertTrue("navigator.serviceWorker.register" in script, script)
    }

    @Test
    fun `renderWasmCoiCheckPage loads the fallback worker and fails loudly when not isolated`() {
        val page = renderWasmCoiCheckPage()
        assertTrue("<script src=\"$WASM_COI_SERVICE_WORKER_FILE\"></script>" in page, page)
        assertTrue("self.crossOriginIsolated" in page, page)
        assertTrue("SharedArrayBuffer" in page, page)
        assertTrue("cannot start the threaded CPython 3.15t runtime" in page, page)
    }

    @Test
    fun `patchWasmWebpackConfigDirectory writes webpack config d script setting devServer headers`() {
        val packageDir = tmp()
        val file = patchWasmWebpackConfigDirectory(packageDir, "credentialless")
        assertEquals(File(packageDir, "webpack.config.d/$WASM_WEBPACK_CONFIG_FILE"), file)
        val text = file.readText()
        assertTrue("config.devServer" in text, text)
        assertTrue("\"Cross-Origin-Opener-Policy\": \"same-origin\"" in text, text)
        assertTrue("\"Cross-Origin-Embedder-Policy\": \"credentialless\"" in text, text)
        assertTrue("\"Cross-Origin-Resource-Policy\": \"same-origin\"" in text, text)
    }

    @Test
    fun `writeWasmCrossOriginIsolationAssets writes _headers, service worker and check page`() {
        val dir = tmp()
        writeWasmCrossOriginIsolationAssets(dir, "require-corp")
        assertTrue(File(dir, WASM_HEADERS_FILE).isFile)
        assertTrue(File(dir, WASM_COI_SERVICE_WORKER_FILE).isFile)
        assertTrue(File(dir, WASM_COI_CHECK_PAGE_FILE).isFile)
    }

    @Test
    fun `plugin registers stageWasmCrossOriginIsolation and wires it into wasmJsProcessResources`() {
        val project = ProjectBuilder.builder().withProjectDir(tmp()).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).wasmJs {
            browser()
        }

        val stageTask = project.tasks.getByName(STAGE_WASM_ISOLATION_TASK) as StageWasmCrossOriginIsolationTask
        val processResources = project.tasks.getByName("wasmJsProcessResources") as Copy

        assertTrue(
            stageTask in processResources.taskDependencies.getDependencies(processResources),
            "wasmJsProcessResources does not depend on $STAGE_WASM_ISOLATION_TASK",
        )

        stageTask.stage()
        val outDir = stageTask.destinationDir.get().asFile
        val headersFile = File(outDir, WASM_HEADERS_FILE)
        val workerFile = File(outDir, WASM_COI_SERVICE_WORKER_FILE)
        val checkPage = File(outDir, WASM_COI_CHECK_PAGE_FILE)
        assertTrue(headersFile.isFile, "_headers was not generated in $outDir")
        assertTrue(workerFile.isFile, "coi-serviceworker.js was not generated in $outDir")
        assertTrue(checkPage.isFile, "coi-check.html was not generated in $outDir")
        assertTrue(
            headersFile.canonicalFile in processResources.source.files.map { it.canonicalFile },
            "wasmJsProcessResources does not include $outDir: ${processResources.source.files}",
        )
    }
}
