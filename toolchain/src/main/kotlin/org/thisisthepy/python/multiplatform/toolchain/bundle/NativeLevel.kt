package org.thisisthepy.python.multiplatform.toolchain.bundle

import groovy.json.JsonSlurper
import kotlinx.coroutines.runBlocking
import org.gradle.api.GradleException
import org.thisisthepy.python.multiplatform.packpack.compile.extension.ExtensionCompileOptions
import org.thisisthepy.python.multiplatform.packpack.compile.extension.ExtensionModuleSource
import org.thisisthepy.python.multiplatform.packpack.compile.extension.NotNativeModule
import org.thisisthepy.python.multiplatform.packpack.compile.extension.PythonAbi
import org.thisisthepy.python.multiplatform.packpack.compile.extension.SkippedItem
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import java.io.File

/**
 * `compileLevel = "native"` (`docs/SPEC.md` §1.6, python-multiplatform#25): what toolchain hands
 * `pypackpack`'s `ResourceBundler` beside `buildLevel = "native"`. The C comes from TypedPython's
 * `emit-c` ([readTypedpythonEmitCManifest]); the target's CPython headers, `EXT_SUFFIX` and, where the
 * target links it, `libpython` come from the CPython `pypackpack` installs for that target
 * ([nativeRuntimeInputs]). Compiling is `pypackpack`'s work (AGENTS.md §13); this only gathers inputs.
 */
data class NativeLevelInputs(
    val pythonAbi: PythonAbi,
    val nativeModules: List<ExtensionModuleSource>,
    val notNativeModules: List<NotNativeModule>,
    val cpythonIncludeDir: File,
    val extensionSuffix: String,
    val cpythonLibDir: File? = null,
    val cpythonLibraryName: String? = null,
    /** Request-wide flags: the TypedPython runtime headers (`runtimeInclude`, `tp_runtime.h`) for every module. */
    val nativeOptions: ExtensionCompileOptions = ExtensionCompileOptions(),
)

/** `typedpython-emit-c` manifest identity (python-multiplatform `docs/design/typedpython.md` §4.3.2a). */
const val TYPEDPYTHON_EMIT_C_FORMAT = "typedpython-emit-c"
const val TYPEDPYTHON_EMIT_C_VERSION = 1

/** The modules TypedPython produced C for, and those it did not, from an `emit-c` manifest. */
data class TypedpythonEmitCManifest(
    val projectRoot: File,
    /** The gate wheel's `typedpython/runtime` directory; the generated C includes `tp_runtime.h` from it. */
    val runtimeInclude: File,
    val nativeModules: List<ExtensionModuleSource>,
    val notNativeModules: List<NotNativeModule>,
)

/**
 * Parses a `typedpython-emit-c` v1 manifest (python-multiplatform `docs/design/typedpython.md` §4.3.2a)
 * straight into `pypackpack`'s types. Unknown keys are ignored (adding keys does not bump the
 * version); another `format`, `version` or `kind` is refused.
 */
fun readTypedpythonEmitCManifest(json: String): TypedpythonEmitCManifest {
    val root = runCatching { JsonSlurper().parseText(json) }.getOrNull() as? Map<*, *>
        ?: throw IllegalArgumentException("The typedpython emit-c manifest is not a JSON object.")
    require(root["format"] == TYPEDPYTHON_EMIT_C_FORMAT) {
        "The typedpython emit-c manifest has format '${root["format"]}'; expected '$TYPEDPYTHON_EMIT_C_FORMAT'."
    }
    require((root["version"] as? Number)?.toInt() == TYPEDPYTHON_EMIT_C_VERSION) {
        "The typedpython emit-c manifest has version '${root["version"]}'; toolchain reads version $TYPEDPYTHON_EMIT_C_VERSION."
    }

    fun string(map: Map<*, *>, key: String, owner: String): String =
        (map[key] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("The typedpython emit-c manifest: '$owner.$key' is missing or empty.")

    fun list(map: Map<*, *>, key: String, owner: String): List<*> =
        map[key] as? List<*> ?: throw IllegalArgumentException("The typedpython emit-c manifest: '$owner.$key' is not a list.")

    fun items(raw: List<*>, owner: String): List<SkippedItem> =
        raw.mapIndexed { i, e ->
            val item = e as? Map<*, *> ?: throw IllegalArgumentException("The typedpython emit-c manifest: '$owner[$i]' is not an object.")
            SkippedItem(
                name = string(item, "name", "$owner[$i]"),
                file = string(item, "file", "$owner[$i]"),
                line = (item["line"] as? Number)?.toInt(),
                message = item["message"] as? String ?: "",
            )
        }

    val native = list(root, "nativeModules", "root").mapIndexed { i, e ->
        val owner = "nativeModules[$i]"
        val m = e as? Map<*, *> ?: throw IllegalArgumentException("The typedpython emit-c manifest: '$owner' is not an object.")
        ExtensionModuleSource(
            moduleName = string(m, "moduleName", owner),
            sources = list(m, "sources", owner).map { File(it as? String ?: throw IllegalArgumentException("'$owner.sources' holds a non-string.")) },
            includeDirs = list(m, "includeDirs", owner).map { File(it as? String ?: throw IllegalArgumentException("'$owner.includeDirs' holds a non-string.")) },
            defines = (m["defines"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to v?.toString() },
        )
    }
    val notNative = list(root, "notNativeModules", "root").mapIndexed { i, e ->
        val owner = "notNativeModules[$i]"
        val m = e as? Map<*, *> ?: throw IllegalArgumentException("The typedpython emit-c manifest: '$owner' is not an object.")
        val kind = string(m, "kind", owner)
        NotNativeModule(
            module = string(m, "module", owner),
            kind = NotNativeModule.Kind.values().firstOrNull { it.name == kind }
                ?: throw IllegalArgumentException("The typedpython emit-c manifest: '$owner.kind' is '$kind', not one of ${NotNativeModule.Kind.values().joinToString()}."),
            items = items((m["items"] as? List<*>).orEmpty(), "$owner.items"),
        )
    }
    return TypedpythonEmitCManifest(
        File(string(root, "projectRoot", "root")),
        File(string(root, "runtimeInclude", "root")),
        native,
        notNative,
    )
}

/**
 * Why the manifest's compiled modules would not replace the bundle's own `.py` files, or `null`. The
 * bundler places `a.b.c` at `a/b/c<EXT_SUFFIX>` and drops `a/b/c.py` from the payload, so every module
 * must name a `.py` under [importRoot], the root `emit-c` derived module names from.
 */
fun nativeModulesOutsideRootRejection(manifest: TypedpythonEmitCManifest, importRoot: File): String? {
    val missing = manifest.nativeModules.map { it.moduleName }
        .filterNot { File(importRoot, it.replace('.', '/') + ".py").isFile }
    return if (missing.isEmpty()) {
        null
    } else {
        "compileLevel 'native': TypedPython compiled ${missing.joinToString()}, which ${if (missing.size == 1) "is" else "are"} " +
            "not a module under $importRoot, the package's import root; the extension would not replace its .py."
    }
}

/** `typedpython emit-c <files> --root <root> --cache <cache> --manifest <manifest>` (§4.3.2a). */
fun typedpythonEmitCCommand(executable: File, files: List<File>, root: File, cache: File, manifest: File): List<String> =
    buildList {
        addAll(listOf(executable.path, "emit-c"))
        files.forEach { add(it.absolutePath) }
        addAll(listOf("--root", root.absolutePath, "--cache", cache.absolutePath, "--manifest", manifest.absolutePath))
    }

/** `PythonAbi` (`major.minor`) of a resolved `compileSdk`; the native slot is free-threaded only. */
fun nativePythonAbi(compileSdk: PythonVersion): PythonAbi = PythonAbi("${compileSdk.major}.${compileSdk.minor}")

/**
 * Where `pypackpack`'s `installPython` puts [target]'s CPython for the native slot:
 * `build/pythonRuntime/<canonical triple>/<version>`, the explicit `installDir` a daemon-hosted caller
 * passes (pypackpack AGENTS.md §13).
 */
fun nativeRuntimeDir(buildDir: File, target: String, wheelVersion: String): File =
    File(buildDir, "pythonRuntime/${Platforms.normalizeTarget(target) ?: target}/$wheelVersion")

/** Targets whose extensions link `libpython` (python-multiplatform `CPythonIncludeLayout.isLinkRequired`). */
private fun linksLibpython(target: String): Boolean =
    Platforms.normalizeTarget(target)?.endsWith("-linux-android") == true

/**
 * The native slot's CPython inputs read from an installed [runtime] tree for [target] and [abi]:
 * the directory that holds `Python.h` (`include/python<X.Y>t`), `EXT_SUFFIX` from the
 * `_sysconfigdata_*.py` beside the stdlib, and on Android `libpython<X.Y>t.so`. Works for both layouts
 * `pypackpack` installs: python-build-standalone (`include/`, `lib/`) and the Android prefix
 * (`prefix/include/`, `prefix/lib/`). Throws naming what is missing.
 */
fun nativeRuntimeInputs(runtime: File, target: String, abi: PythonAbi): Triple<File, String, Pair<File, String>?> {
    val tag = "python${abi.version}t"
    val dirs = listOf(runtime, File(runtime, "prefix"))
    val include = dirs.map { File(it, "include/$tag") }.firstOrNull { File(it, "Python.h").isFile }
        ?: throw IllegalStateException(
            "compileLevel 'native': no include/$tag/Python.h in the CPython for $target at $runtime.",
        )
    val sysconfig = dirs.map { File(it, "lib/$tag") }.filter { it.isDirectory }
        .flatMap { it.listFiles().orEmpty().toList() }
        .filter { it.isFile && it.name.startsWith("_sysconfigdata") && it.name.endsWith(".py") }
        .minByOrNull { it.name }
    val suffix = sysconfig?.readText()?.let { Regex("""'EXT_SUFFIX':\s*'([^']+)'""").find(it)?.groupValues?.get(1) }
        ?: throw IllegalStateException(
            "compileLevel 'native': no EXT_SUFFIX in a _sysconfigdata_*.py under lib/$tag of the CPython for $target at $runtime.",
        )
    val lib = if (linksLibpython(target)) {
        val name = "lib$tag.so"
        val dir = dirs.map { File(it, "lib") }.firstOrNull { File(it, name).isFile }
            ?: throw IllegalStateException("compileLevel 'native': $target links $name, and the CPython at $runtime has none under lib/.")
        dir to name
    } else {
        null
    }
    return Triple(include, suffix, lib)
}

/**
 * Installs [target]'s CPython [wheelVersion] into [runtimeDir] through `pypackpack`'s backend, unless
 * a previous run left headers there.
 */
fun ensureNativeRuntime(runtimeDir: File, target: String, wheelVersion: String) {
    val tag = Regex("""^(\d+\.\d+)""").find(wheelVersion)?.groupValues?.get(1)?.let { "python${it}t" }
    if (tag != null && listOf(runtimeDir, File(runtimeDir, "prefix")).any { File(it, "include/$tag/Python.h").isFile }) return
    val installed = runBlocking {
        BackendInterface.create(BackendType.UV).installPython(wheelVersion, target, runtimeDir)
    }
    installed.onFailure {
        throw GradleException("compileLevel 'native': could not install CPython $wheelVersion for $target into $runtimeDir: ${it.message}", it)
    }
}

/**
 * Runs `typedpython emit-c` with the gate [executable] over the package's [files] and reads the
 * manifest it writes under [workDir]. Exit 1 (a gate error) or 2 (the gate could not run) fails with
 * its output.
 */
fun runTypedpythonEmitC(executable: File, files: List<File>, importRoot: File, workDir: File): TypedpythonEmitCManifest {
    val manifest = File(workDir, "emit-c-manifest.json").apply { delete() }
    val cache = File(workDir, "c-cache").apply { mkdirs() }
    val tmp = File(workDir, "tmp").apply { mkdirs() }
    val out = File(workDir, "emit-c.log")
    val exit = ProcessBuilder(typedpythonEmitCCommand(executable, files, importRoot, cache, manifest))
        .directory(importRoot)
        .redirectErrorStream(true)
        .redirectOutput(out)
        .apply { environment()["TMPDIR"] = tmp.absolutePath }
        .start()
        .waitFor()
    if (exit != 0 || !manifest.isFile) {
        throw GradleException("compileLevel 'native': typedpython emit-c failed (exit $exit):\n${out.readText().trim().ifEmpty { "(no output)" }}")
    }
    return try {
        readTypedpythonEmitCManifest(manifest.readText())
    } catch (e: IllegalArgumentException) {
        throw GradleException("compileLevel 'native': $manifest: ${e.message}", e)
    }
}
