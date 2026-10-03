package org.thisisthepy.python.multiplatform.toolchain.example

import platform.Foundation.NSProcessInfo
import platform.posix.exit
import python.multiplatform.ffi.Python3

/**
 * `xcrun simctl launch --console-pty <device> <bundle-id> --python-smoke` runs [runPythonSmokeIfRequested]
 * and exits before any UI, so CI can check the installed app's Python on a simulator (toolchain#22).
 *
 * It checks both halves of the .app: python-multiplatform's (the stdlib it copies into
 * `python-multiplatform-home/` and Python.framework) and toolchain's (`python/`: the app's `example_py`
 * and the dependency `iniconfig`). The report is printed to stdout, which `--console-pty` shows.
 */
const val PYTHON_SMOKE_ARGUMENT = "--python-smoke"

private const val PY_EVAL_INPUT = 258

fun runPythonSmokeIfRequested() {
    if (PYTHON_SMOKE_ARGUMENT !in NSProcessInfo.processInfo.arguments.map { it.toString() }) return
    val code =
        try {
            Python3.initialize()
            Python3.exec("import os, sys, example_py, iniconfig")
            val globals = Python3.import("__main__").dict
            val report =
                Python3.eval(
                    "f'{example_py.VERSION} {iniconfig.__name__} {sys.version.split()[0]}\\nPYTHON_SMOKE_STDLIB {os.path.realpath(os.__file__)}'",
                    PY_EVAL_INPUT,
                    globals,
                    globals,
                )
            println("PYTHON_SMOKE_OK $report")
            0
        } catch (t: Throwable) {
            println("PYTHON_SMOKE_FAILED: $t")
            1
        }
    exit(code)
}
