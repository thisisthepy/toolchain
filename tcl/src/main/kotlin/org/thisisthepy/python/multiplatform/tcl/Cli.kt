package org.thisisthepy.python.multiplatform.tcl

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "Usage: tcl install <package>"

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
    out: (String) -> Unit = ::println,
    err: (String) -> Unit = System.err::println,
): Int {
    if (args.isEmpty()) {
        err(USAGE)
        return 1
    }

    return when (args[0]) {
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
        else -> {
            err("tcl: unknown command '${args[0]}'\n$USAGE")
            1
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(execute(args, File(System.getProperty("user.dir"))))
}
