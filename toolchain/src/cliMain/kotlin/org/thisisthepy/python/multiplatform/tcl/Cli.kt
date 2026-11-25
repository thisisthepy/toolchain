package org.thisisthepy.python.multiplatform.tcl

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "Usage: tcl install <package> | tcl kernel install <connection file> [--name N] | tcl connect <connection file>"

/**
 * Parses and runs a `tcl` invocation against [workingDir], returning a process exit code.
 * Factored out of [main] (as `execute`, not `run` -- that name collides with Kotlin's stdlib
 * `T.run { }` scope function badly enough that the compiler reports the call site as ambiguous
 * rather than as a missing symbol) so tests can drive every branch without an actual
 * `exitProcess` call killing the test JVM, and without [install] touching the network -- see
 * `CliArgsTest`, which passes a canned `install` lambda for exactly that reason.
 */
fun execute(
    args: Array<String>,
    workingDir: File,
    install: (String, File) -> Result<String> = { pkg, dir -> installBlocking(pkg, dir) },
    python: (List<String>) -> Int = ::runToolchainLitePython,
    out: (String) -> Unit = ::println,
    err: (String) -> Unit = System.err::println,
): Int {
    if (args.isEmpty()) {
        err(USAGE)
        return 1
    }

    return when (args[0]) {
        "--help", "-h" -> {
            out(USAGE)
            0
        }
        "--version" -> {
            out("tcl ${BuildInfo.version}")
            0
        }
        "install" -> {
            val packageName = args.getOrNull(1)
            if (packageName.isNullOrBlank()) {
                err("tcl install: missing <package> argument\n$USAGE")
                return 1
            }
            install(packageName, workingDir).fold(
                onSuccess = { output ->
                    out("Installed $packageName\n$output")
                    0
                },
                onFailure = { error ->
                    err("tcl install $packageName failed: ${error.message}")
                    1
                },
            )
        }
        // The live connection (SPEC §2.4, docs/design/live-connection.md M1): the proxy kernel and the REPL are
        // the Python package beside this binary (`toolchain_lite.kernel`), which Jupyter runs anyway.
        "kernel" -> {
            if (args.getOrNull(1) != "install" || args.getOrNull(2).isNullOrBlank()) {
                err("tcl kernel: usage: tcl kernel install <connection file> [--name N] [--display-name T]")
                return 1
            }
            python(listOf("install") + args.drop(2))
        }
        "connect" -> {
            val file = args.getOrNull(1)
            if (file.isNullOrBlank()) {
                err("tcl connect: missing <connection file> (build/pythonDevAgent/<task>.json)")
                return 1
            }
            python(listOf("console", file))
        }
        else -> {
            err("tcl: unknown command '${args[0]}'\n$USAGE")
            1
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(execute(args, File(System.getProperty("user.dir"))))
}

/**
 * Runs `<python> -m toolchain_lite.kernel <args>` with the interpreter `uv tool install toolchain-lite`
 * put beside this binary (its tool environment's `bin/` or `Scripts\`), else `TCL_PYTHON`, else `python3`.
 */
fun runToolchainLitePython(args: List<String>): Int {
    val self = ProcessHandle.current().info().command().map { File(it).absoluteFile.parentFile }.orElse(null)
    val beside = listOf("python", "python3", "python.exe").mapNotNull { name -> self?.let { File(it, name) } }.firstOrNull { it.isFile }
    val interpreter = beside?.path ?: System.getenv("TCL_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
    return ProcessBuilder(listOf(interpreter, "-m", "toolchain_lite.kernel") + args).inheritIO().start().waitFor()
}
