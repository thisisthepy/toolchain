package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.thisisthepy.python.multiplatform.packpack.compile.extension.ExtensionModuleSource
import org.thisisthepy.python.multiplatform.packpack.compile.extension.NotNativeModule
import org.thisisthepy.python.multiplatform.packpack.compile.extension.PythonAbi
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `compileLevel = "native"` (python-multiplatform#25, `docs/SPEC.md` §1.6): the inputs toolchain gathers
 * for `pypackpack`'s native slot. The emit-c manifest is python-multiplatform `docs/design/typedpython.md`
 * §4.3.2a v1; the runtime trees are the layouts `pypackpack`'s `installPython` produces.
 */
class NativeLevelTest {
    private val dir: File = Files.createTempDirectory("toolchain-native-").toFile().canonicalFile

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun write(relative: String, text: String = "") =
        File(dir, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    private val manifestJson = """
        {"format": "typedpython-emit-c", "version": 1, "compilerVersion": "0.1.0", "projectRoot": "/p/src/main",
         "runtimeInclude": "/p/rt", "extra": "ignored",
         "nativeModules": [{"moduleName": "app.physics", "file": "app/physics.py", "sources": ["/c/app_physics.c"],
                            "includeDirs": ["/p/rt"], "defines": {"TP_X": "1", "TP_Y": null}, "status": "compiled",
                            "skippedItems": []}],
         "notNativeModules": [{"module": "app.ui", "kind": "NOT_MARKED"},
                              {"module": "app.io", "kind": "NOTHING_COMPILED",
                               "items": [{"name": "read", "file": "app/io.py", "line": 3, "message": "uses open"}]}]}
    """.trimIndent()

    @Test
    fun `an emit-c manifest maps onto pypackpack's nativeModules and notNativeModules`() {
        val manifest = readTypedpythonEmitCManifest(manifestJson)

        assertEquals(File("/p/src/main"), manifest.projectRoot)
        assertEquals(File("/p/rt"), manifest.runtimeInclude)
        assertEquals(
            listOf(ExtensionModuleSource("app.physics", listOf(File("/c/app_physics.c")), listOf(File("/p/rt")), mapOf("TP_X" to "1", "TP_Y" to null))),
            manifest.nativeModules,
        )
        assertEquals(listOf("app.ui", "app.io"), manifest.notNativeModules.map { it.module })
        assertEquals(NotNativeModule.Kind.NOTHING_COMPILED, manifest.notNativeModules[1].kind)
        assertEquals(3, manifest.notNativeModules[1].items.single().line)
        assertEquals("uses open", manifest.notNativeModules[1].items.single().message)
    }

    @Test
    fun `another format, version or kind is refused`() {
        listOf(
            manifestJson.replace("\"typedpython-emit-c\"", "\"other\"") to "format",
            manifestJson.replace("\"version\": 1", "\"version\": 2") to "version",
            manifestJson.replace("NOT_MARKED", "SKIPPED") to "kind",
            "[]" to "JSON object",
        ).forEach { (json, word) ->
            val error = assertFailsWith<IllegalArgumentException> { readTypedpythonEmitCManifest(json) }
            assertTrue(word in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `a compiled module that is not a py under the import root is refused`() {
        val root = File(dir, "src/main")
        write("src/main/app/physics.py")
        val manifest = readTypedpythonEmitCManifest(manifestJson)
        assertNull(nativeModulesOutsideRootRejection(manifest, root))

        File(root, "app/physics.py").delete()
        val message = nativeModulesOutsideRootRejection(manifest, root).orEmpty()
        assertTrue("app.physics" in message && root.path in message, message)
    }

    @Test
    fun `the emit-c command passes files, root, cache and manifest`() {
        val cmd = typedpythonEmitCCommand(File("/v/bin/typedpython"), listOf(File("/r/a.py")), File("/r"), File("/w/c"), File("/w/m.json"))
        assertEquals(
            listOf("/v/bin/typedpython", "emit-c", File("/r/a.py").absolutePath, "--root", File("/r").absolutePath,
                "--cache", File("/w/c").absolutePath, "--manifest", File("/w/m.json").absolutePath),
            cmd,
        )
    }

    @Test
    fun `the ABI is compileSdk's major dot minor, and the runtime goes under build pythonRuntime by canonical triple`() {
        assertEquals(PythonAbi("3.15"), nativePythonAbi(PythonVersion.parse("3.15.0t")))
        assertEquals(
            File(dir, "pythonRuntime/aarch64-linux-android/3.15.0t"),
            nativeRuntimeDir(dir, "aarch64-linux-android", "3.15.0t"),
        )
    }

    @Test
    fun `a python-build-standalone tree gives its headers and EXT_SUFFIX, and links nothing`() {
        write("include/python3.15t/Python.h")
        write("lib/python3.15t/_sysconfigdata_t_darwin_darwin.py", "build_time_vars = {'EXT_SUFFIX': '.cpython-315t-darwin.so', 'SOABI': 'x'}\n")

        val (include, suffix, lib) = nativeRuntimeInputs(dir, "aarch64-apple-darwin", PythonAbi("3.15"))

        assertEquals(File(dir, "include/python3.15t"), include)
        assertEquals(".cpython-315t-darwin.so", suffix)
        assertNull(lib)
    }

    @Test
    fun `an Android prefix tree also gives libpython`() {
        write("prefix/include/python3.15t/Python.h")
        write("prefix/lib/python3.15t/_sysconfigdata_t_android_aarch64-linux-android.py", "build_time_vars = {'EXT_SUFFIX': '.cpython-315t-aarch64-linux-android.so', 'LDLIBRARY': 'libpython3.15t.so'}\n")
        write("prefix/lib/libpython3.15t.so")

        val (include, suffix, lib) = nativeRuntimeInputs(dir, "aarch64-linux-android", PythonAbi("3.15"))

        assertEquals(File(dir, "prefix/include/python3.15t"), include)
        assertEquals(".cpython-315t-aarch64-linux-android.so", suffix)
        assertEquals<Pair<File, String?>?>(File(dir, "prefix/lib") to "libpython3.15t.so", lib)
    }

    /** Windows and iOS link too: `python3XYt.lib` and the XCframework slice (pypackpack#155's slot needs `libDir`). */
    @Test
    fun `Windows gives python3XYt lib and iOS its framework slice`() {
        write("win/python/python.exe")
        write("win/python/include/Python.h")
        write("win/python/libs/python315t.lib")
        val (winInclude, winSuffix, winLib) = nativeRuntimeInputs(File(dir, "win"), "x86_64-pc-windows-msvc", PythonAbi("3.15"))
        assertEquals(File(dir, "win/python/include").canonicalFile, winInclude.canonicalFile)
        assertEquals(".cp315t-win_amd64.pyd", winSuffix)
        assertEquals("python315t.lib", winLib?.second)

        val slice = "ios/Python.xcframework/ios-arm64_x86_64-simulator"
        write("$slice/Python.framework/Headers/Python.h")
        write("$slice/lib-arm64/python3.15t/_sysconfigdata_t_ios.py", "build_time_vars = {'EXT_SUFFIX': '.cpython-315t-iphonesimulator.so'}\n")
        val (_, iosSuffix, iosLib) = nativeRuntimeInputs(File(dir, "ios"), "arm64-apple-ios-simulator", PythonAbi("3.15"))
        assertEquals(".cpython-315t-iphonesimulator.so", iosSuffix)
        assertEquals(File(dir, slice).canonicalFile, iosLib?.first?.canonicalFile)
        assertNull(iosLib?.second)
    }

    @Test
    fun `missing headers, EXT_SUFFIX or Android libpython are refused, naming them`() {
        val noHeaders = assertFailsWith<IllegalStateException> { nativeRuntimeInputs(dir, "aarch64-apple-darwin", PythonAbi("3.15")) }
        assertTrue("Python.h" in noHeaders.message.orEmpty(), noHeaders.message)

        write("prefix/include/python3.15t/Python.h")
        val noSuffix = assertFailsWith<IllegalStateException> { nativeRuntimeInputs(dir, "aarch64-linux-android", PythonAbi("3.15")) }
        assertTrue("EXT_SUFFIX" in noSuffix.message.orEmpty(), noSuffix.message)

        write("prefix/lib/python3.15t/_sysconfigdata_t_android.py", "build_time_vars = {'EXT_SUFFIX': '.so', 'LDLIBRARY': 'libpython3.15t.so'}\n")
        val noLib = assertFailsWith<IllegalStateException> { nativeRuntimeInputs(dir, "aarch64-linux-android", PythonAbi("3.15")) }
        assertTrue("libpython3.15t.so" in noLib.message.orEmpty(), noLib.message)
    }

    @Test
    fun `bundleRequestFor forwards the native inputs`() {
        val native = NativeLevelInputs(
            pythonAbi = PythonAbi("3.15"),
            nativeModules = readTypedpythonEmitCManifest(manifestJson).nativeModules,
            notNativeModules = readTypedpythonEmitCManifest(manifestJson).notNativeModules,
            cpythonIncludeDir = File(dir, "include/python3.15t"),
            extensionSuffix = ".cpython-315t-aarch64-linux-android.so",
            cpythonLibDir = File(dir, "prefix/lib"),
            cpythonLibraryName = "libpython3.15t.so",
            nativeOptions = org.thisisthepy.python.multiplatform.packpack.compile.extension.ExtensionCompileOptions(includeDirs = listOf(File("/p/rt"))),
        )
        val request = bundleRequestFor(dir, "aarch64-linux-android", "release", File(dir, "out"), buildLevel = "native", native = native)

        assertEquals("native", request.buildLevel)
        assertEquals(PythonAbi("3.15"), request.pythonAbi)
        assertEquals(native.nativeModules, request.nativeModules)
        assertEquals(native.notNativeModules, request.notNativeModules)
        assertEquals(native.cpythonIncludeDir, request.cpythonIncludeDir)
        assertEquals(native.extensionSuffix, request.extensionSuffix)
        assertEquals(native.cpythonLibDir, request.cpythonLibDir)
        assertEquals("libpython3.15t.so", request.cpythonLibraryName)
        assertEquals(listOf(File("/p/rt")), request.nativeOptions.includeDirs)
    }

    @Test
    fun `the refusals name compileSdk and the gate`() {
        assertTrue("compileSdk" in nativeWithoutCompileSdkRejection())
        val gate = nativeWithoutGateRejection(File("/b/typedpython/venv/bin/typedpython"))
        assertTrue("typedpythonCheck" in gate && "/b/typedpython/venv/bin/typedpython" in gate, gate)
    }
}
