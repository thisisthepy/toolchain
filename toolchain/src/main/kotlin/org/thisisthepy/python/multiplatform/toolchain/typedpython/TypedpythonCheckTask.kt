package org.thisisthepy.python.multiplatform.toolchain.typedpython

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException

/** `docs/SPEC.md` §1.18. The gate's version until a newer one is pinned here. */
const val DEFAULT_TYPEDPYTHON_VERSION = "0.1.0"
const val DEFAULT_TYPEDPYTHON_MODE = "checked"
val TYPEDPYTHON_MODES = listOf("checked", "compiled")

/** The `typedpython` 0.1.0 wheel's own `Requires-Python`. The gate's venv is created for it. */
const val TYPEDPYTHON_PYTHON_REQUEST = ">=3.13"

/** Project property naming a directory holding the wheel, until `typedpython` is on PyPI. */
const val TYPEDPYTHON_WHEEL_DIR_PROPERTY = "typedpython.wheelDir"

const val TYPEDPYTHON_NO_STUBS_NOTICE =
    "typedpythonCheck: the typedpythonStubs configuration is empty; checking without stubs."

/**
 * Statically checks the project's own Python with the TypedPython gate before it is bundled
 * (issue `toolchain#23`, `docs/SPEC.md` §1.18).
 *
 * The gate is installed into [venvDir] (`build/typedpython/venv`) by calling `uv` directly, **not**
 * through `pypackpack`'s UV backend (AGENTS.md §13 would prefer the backend). Two reasons, both
 * observed on 2026-10-03:
 *
 * 1. The backend cannot run inside a consumer build. `packpack` is compiled with Kotlin 2.3, and a
 *    Kotlin DSL build pins the plugin classpath's `kotlin-stdlib` to Gradle's embedded one
 *    (`{strictly 1.9.23}` on Gradle 8.9 -- `:usage-example:buildEnvironment`). Its suspend
 *    `createVirtualEnvironment` then fails in `usage-example` with
 *    `NoClassDefFoundError: kotlin/coroutines/jvm/internal/SpillingKt`. TestKit's
 *    `withPluginClasspath()` injects the classes without that pin, so the TestKit tests passed over it.
 * 2. The backend interface has no "install these requirements into that venv" operation:
 *    `installDependenciesToTarget` always reads `-r pyproject.toml`.
 *
 * Both are `pypackpack` changes -- another repository, proposed rather than made here. Once they
 * land, [ensureTypedpythonGate] is the one place to switch back.
 */
abstract class TypedpythonCheckTask : DefaultTask() {
    /** The `.py`/`.pyi` files of the package directory -- see [typedpythonSourceExcluded]. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Where [sources] come from. Location only: its Python content is fingerprinted by [sources]. */
    @get:Internal
    var packageDir: File? = null

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    abstract val gateVersion: Property<String>

    /** `-Ptypedpython.wheelDir`, resolved against the project directory. */
    @get:Input
    @get:Optional
    abstract val wheelDir: Property<String>

    /** The `typedpythonStubs` configuration's files, each passed as `--search-path`, in order. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stubDirs: ConfigurableFileCollection

    /** Where the gate is installed. Never the project's `.venv`. */
    @get:Internal
    abstract val venvDir: DirectoryProperty

    /** Written only by a passing run, so an unchanged rerun is `UP-TO-DATE`. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val checkMode = mode.get()
        validateTypedpythonMode(checkMode)
        val reportFile = report.get().asFile
        val dir = packageDir
        val files = dir?.let { collectTypedpythonSources(it) }.orEmpty()
        if (dir == null || files.isEmpty()) {
            logger.lifecycle("typedpythonCheck: no .py file in the Python package directory; nothing to check.")
            reportFile.parentFile.mkdirs()
            reportFile.writeText("no files checked\n")
            return
        }

        val venv = venvDir.get().asFile
        val executable = ensureTypedpythonGate(venv, gateVersion.get(), wheelDir.orNull)

        val stubs = stubDirs.files.toList()
        if (stubs.isEmpty()) logger.lifecycle(TYPEDPYTHON_NO_STUBS_NOTICE)
        val command = typedpythonCheckCommand(executable, checkMode, listOf(typedpythonImportRoot(dir)) + stubs, files)
        logger.info("typedpythonCheck: ${command.joinToString(" ")}")

        val work = venv.parentFile
        // The gate writes a Pyrefly config into a temporary directory; keep it under build/.
        val tmp = File(work, "tmp").apply { mkdirs() }
        val stdoutFile = File(work, "check.stdout")
        val stderrFile = File(work, "check.stderr")
        val exitCode = ProcessBuilder(command)
            .directory(project.projectDir)
            .redirectOutput(stdoutFile)
            .redirectError(stderrFile)
            .apply { environment()["TMPDIR"] = tmp.absolutePath }
            .start()
            .waitFor()

        when (val result = interpretTypedpythonResult(exitCode, stdoutFile.readText(), stderrFile.readText())) {
            is TypedpythonResult.Passed -> {
                result.warnings.forEach { logger.warn(it) }
                reportFile.writeText(
                    "typedpython $checkMode: ${files.size} file(s), 0 errors, ${result.warnings.size} warning(s)\n" +
                        result.warnings.joinToString("") { "$it\n" },
                )
            }
            is TypedpythonResult.TypeErrors -> {
                result.warnings.forEach { logger.warn(it) }
                throw GradleException(
                    "typedpython ($checkMode) found ${result.errors.size} error(s):\n" +
                        result.errors.joinToString("\n"),
                )
            }
            is TypedpythonResult.ToolFailure ->
                throw GradleException("typedpython could not check the package (exit $exitCode):\n${result.message}")
        }
    }
}

/**
 * Installs `typedpython==<version>` into [venv] unless the same request is already installed
 * there, and returns its console script.
 */
fun ensureTypedpythonGate(venv: File, version: String, wheelDir: String?): File {
    val executable = typedpythonExecutable(venv)
    val marker = File(venv, ".typedpython-request")
    val request = "typedpython==$version find-links=${wheelDir.orEmpty()}"
    if (executable.isFile && marker.isFile && marker.readText() == request) return executable

    val work = venv.parentFile.apply { mkdirs() }
    if (!File(venv, "pyvenv.cfg").isFile) {
        runUv(typedpythonVenvCommand(venv), work).let { (exit, output) ->
            if (exit != 0) throw GradleException("Could not create the typedpython venv at $venv (uv exit $exit):\n$output")
        }
    }
    runUv(typedpythonInstallCommand(venv, version, wheelDir), work).let { (exit, output) ->
        if (exit != 0) {
            throw GradleException(
                "Could not install typedpython==$version into $venv (uv exit $exit):\n$output\n" +
                    (if (wheelDir == null) "typedpython is not on PyPI yet: " else "") +
                    "set -P$TYPEDPYTHON_WHEEL_DIR_PROPERTY=<directory holding the typedpython-$version wheel>.",
            )
        }
    }
    if (!executable.isFile) throw GradleException("typedpython was installed into $venv but $executable does not exist")
    marker.writeText(request)
    return executable
}

/** `uv venv` for the gate: the wheel's Python, and never the user's `pyproject.toml` project. */
fun typedpythonVenvCommand(venv: File): List<String> =
    listOf("uv", "venv", "--no-project", "--clear", "--python", TYPEDPYTHON_PYTHON_REQUEST, venv.path)

fun typedpythonInstallCommand(venv: File, version: String, wheelDir: String?): List<String> =
    buildList {
        addAll(listOf("uv", "pip", "install", "--python", venv.path))
        wheelDir?.let { addAll(listOf("--find-links", it)) }
        add("typedpython==$version")
    }

private fun runUv(command: List<String>, workingDir: File): Pair<Int, String> {
    val process = try {
        ProcessBuilder(command).directory(workingDir).redirectErrorStream(true).start()
    } catch (e: IOException) {
        throw GradleException("typedpythonCheck needs uv on PATH to install the gate: ${e.message}", e)
    }
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() to output
}

private fun typedpythonExecutable(venv: File): File =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        File(venv, "Scripts/typedpython.exe")
    } else {
        File(venv, "bin/typedpython")
    }

/** Directory names under the package directory whose contents are never user code. */
fun typedpythonSourceExcluded(directoryName: String): Boolean =
    directoryName.startsWith(".") || directoryName == "__pycache__" || directoryName == "build"

/** The `.py` files the gate is given, sorted. Empty when [packageDir] does not exist. */
fun collectTypedpythonSources(packageDir: File): List<File> {
    if (!packageDir.isDirectory) return emptyList()
    return packageDir.walkTopDown()
        .onEnter { it == packageDir || !typedpythonSourceExcluded(it.name) }
        .filter { it.isFile && it.extension == "py" }
        .sortedBy { it.relativeTo(packageDir).invariantSeparatorsPath }
        .toList()
}

/**
 * The package's own import root: `src/main` in the `pypackpack` layout, else the package directory.
 * The 0.1.0 gate otherwise takes its temporary config directory as the import root, and the
 * package's modules cannot import one another.
 */
fun typedpythonImportRoot(packageDir: File): File =
    File(packageDir, "src/main").takeIf { it.isDirectory } ?: packageDir

fun validateTypedpythonMode(mode: String) {
    require(mode in TYPEDPYTHON_MODES) {
        "typedpythonCheck mode '$mode' is not a TypedPython mode; use one of ${TYPEDPYTHON_MODES.joinToString()}."
    }
}

/** Files, not directories: the 0.1.0 gate crashes (`IsADirectoryError`) on a directory argument. */
fun typedpythonCheckCommand(executable: File, mode: String, searchPaths: List<File>, files: List<File>): List<String> =
    buildList {
        addAll(listOf(executable.path, "check", "--mode", mode))
        searchPaths.forEach { addAll(listOf("--search-path", it.path)) }
        files.forEach { add(it.path) }
    }

sealed interface TypedpythonResult {
    data class Passed(val warnings: List<String>) : TypedpythonResult
    data class TypeErrors(val errors: List<String>, val warnings: List<String>) : TypedpythonResult
    data class ToolFailure(val message: String) : TypedpythonResult
}

/**
 * Exit 0 clean, 1 errors, 2 the gate could not run. An exit 1 with no `ERROR` line is how an
 * uncaught Python exception exits, so it is a tool failure too.
 */
fun interpretTypedpythonResult(exitCode: Int, stdout: String, stderr: String): TypedpythonResult {
    // A diagnostic is its `ERROR `/`WARNING ` line plus the indented lines after it: a multi-line
    // Pyrefly message puts its `[rule]` on the last of them.
    val diagnostics = mutableListOf<String>()
    stdout.lines().forEach { line ->
        when {
            line.startsWith("ERROR ") || line.startsWith("WARNING ") -> diagnostics.add(line)
            diagnostics.isNotEmpty() && line.isNotBlank() -> diagnostics[diagnostics.lastIndex] += "\n$line"
        }
    }
    val errors = diagnostics.filter { it.startsWith("ERROR ") }
    val warnings = diagnostics.filter { it.startsWith("WARNING ") }
    return when {
        exitCode == 0 -> TypedpythonResult.Passed(warnings)
        exitCode == 1 && errors.isNotEmpty() -> TypedpythonResult.TypeErrors(errors, warnings)
        else -> TypedpythonResult.ToolFailure(
            listOf(stderr.trim(), stdout.trim()).filter { it.isNotEmpty() }.joinToString("\n")
                .ifEmpty { "(no output)" },
        )
    }
}
