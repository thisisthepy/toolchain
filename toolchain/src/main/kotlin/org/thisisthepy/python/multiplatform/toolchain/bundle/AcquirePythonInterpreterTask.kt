package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import kotlinx.coroutines.runBlocking
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendType
import org.thisisthepy.python.multiplatform.packpack.dependency.backend.BackendInterface as DependencyBackend
import org.thisisthepy.python.multiplatform.packpack.utils.Platforms
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Puts the interpreter for ([version], [target]) into [destination]. A seam: the plugin uses
 * [PypackpackInterpreterInstaller]; tests substitute a fake so nothing touches the network.
 */
fun interface PythonInterpreterInstaller {
    /** A success message, or a failure whose message is shown as the task's failure. */
    fun install(version: String, target: String, destination: File): Result<String>
}

/**
 * The installer that goes through pypackpack's backend layer (AGENTS.md §13):
 * `installPython(version, target, installDir = destination)` (pypackpack#37). With an explicit
 * `installDir`, pypackpack resolves the pair against its pinned table (an unsupported pair, such as
 * 3.13.0 for Android with no pinned SHA-256, is refused before any download), verifies the archive,
 * and extracts it straight into [destination] -- never into a project found from the daemon's shared
 * `user.dir`.
 */
object PypackpackInterpreterInstaller : PythonInterpreterInstaller {
    override fun install(version: String, target: String, destination: File): Result<String> {
        val canonical = Platforms.normalizeTarget(target)
            ?: return Result.failure(IllegalArgumentException("Unsupported target platform: $target"))
        val backend = DependencyBackend.create(BackendType.UV)
        backend.initialize()
        return runBlocking { backend.installPython(version, canonical, installDir = destination) }
    }
}

/**
 * Acquires the interpreter one (compileSdk version, triple) pair needs, into
 * `build/pythonRuntime/<triple>/<version>/` (docs/SPEC.md §1.12, issue #18). Registered once per
 * pair and shared by every variant with that pair, so `androidArm64-debug` and
 * `androidArm64-release` acquire it once.
 *
 * A pair pypackpack does not provide fails this task's action, so only the variants that need it
 * fail and `--continue` builds the rest (AGENTS.md §14).
 */
open class AcquirePythonInterpreterTask : DefaultTask() {
    /** The `X.Y.Z` release `resolvePythonSdk` chose for `compileSdk`. */
    @get:Input
    var pythonVersion: String = ""

    /** The canonical pypackpack triple. */
    @get:Input
    var target: String = ""

    /** `build/pythonRuntime/<target>/<pythonVersion>/`. */
    @get:OutputDirectory
    lateinit var runtimeDir: File

    /** How the interpreter is fetched; not an input, the pair above is what decides the output. */
    @get:Internal
    var installer: PythonInterpreterInstaller = PypackpackInterpreterInstaller

    @TaskAction
    fun acquire() {
        val message = installer.install(pythonVersion, target, runtimeDir).getOrElse { error ->
            throw GradleException(
                "Cannot bundle Python $pythonVersion for $target (embedLevel 2): ${error.message}",
                error,
            )
        }
        logger.lifecycle(message)
    }
}

/** The bundle directory the interpreter is carried into: `<bundle>/runtime/`, beside `python/`. */
const val BUNDLE_RUNTIME_ROOT = "runtime"

/** Beside `resource-manifest.json`: which interpreter `runtime/` holds. Written by toolchain, not pypackpack. */
const val RUNTIME_MANIFEST_FILE_NAME = "runtime-manifest.json"

/**
 * Copies the acquired interpreter tree [runtimeDir] into `<bundleDir>/runtime/` (replacing what was
 * there) and writes `<bundleDir>/runtime-manifest.json`. Symbolic links are copied as links, because
 * an interpreter tree uses them (`bin/python3 -> python3.14`). Returns the number of files copied.
 *
 * Runs after pypackpack's `ResourceBundler`, which clears the bundle directory first.
 */
fun carryInterpreterIntoBundle(runtimeDir: File, bundleDir: File, version: String, target: String): Int {
    if (!runtimeDir.isDirectory || runtimeDir.listFiles().isNullOrEmpty()) {
        throw GradleException(
            "The Python $version interpreter for $target was not acquired: $runtimeDir is missing or empty.",
        )
    }
    val destination = File(bundleDir, BUNDLE_RUNTIME_ROOT)
    if (destination.exists() && !destination.deleteRecursively()) {
        throw GradleException("could not clear $destination before carrying the interpreter into it")
    }
    var files = 0
    val sourceRoot = runtimeDir.toPath()
    Files.walk(sourceRoot).use { paths ->
        paths.forEach { source ->
            val copied = destination.toPath().resolve(sourceRoot.relativize(source).toString())
            when {
                Files.isSymbolicLink(source) -> {
                    Files.createDirectories(copied.parent)
                    Files.copy(source, copied, LinkOption.NOFOLLOW_LINKS)
                    files++
                }
                Files.isDirectory(source) -> Files.createDirectories(copied)
                else -> {
                    Files.createDirectories(copied.parent)
                    Files.copy(source, copied, StandardCopyOption.COPY_ATTRIBUTES)
                    files++
                }
            }
        }
    }
    File(bundleDir, RUNTIME_MANIFEST_FILE_NAME).writeText(runtimeManifestJson(version, target))
    return files
}

/** `{"pythonVersion": ..., "target": ..., "root": "runtime"}`, deterministic, no timestamp. */
fun runtimeManifestJson(version: String, target: String): String =
    """
    {
      "pythonVersion": "$version",
      "target": "$target",
      "root": "$BUNDLE_RUNTIME_ROOT"
    }
    """.trimIndent() + "\n"
