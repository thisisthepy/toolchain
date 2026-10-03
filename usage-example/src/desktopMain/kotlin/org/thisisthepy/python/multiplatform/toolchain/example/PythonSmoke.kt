package org.thisisthepy.python.multiplatform.toolchain.example

import python.multiplatform.ffi.Python3

/**
 * `ToolchainExample --python-smoke` runs [runPythonSmoke] and exits instead of opening the window, so
 * CI can launch the *packaged* desktop app (`createDistributable`) headless and see whether Python
 * works there (toolchain#22).
 *
 * It checks the two halves of a packaged app at once:
 * - python-multiplatform's: CPython starts with no `PYTHONHOME`, from the stdlib packaged under
 *   `python-multiplatform-home/` (its SPEC L-9);
 * - toolchain's: the `python/` payload at the jar root is on `sys.path` -- the app's own `example_py`
 *   and the dependency `iniconfig` that toolchain installed for this target and bundled.
 */
const val PYTHON_SMOKE_FLAG = "--python-smoke"

/** The line the CI job looks for. Anything else, or a non-zero exit, is a failure. */
const val PYTHON_SMOKE_MARKER = "PYTHON_SMOKE_OK"

private val SMOKE_SCRIPT =
    """
    import sys
    import example_py
    import iniconfig
    print("$PYTHON_SMOKE_MARKER", example_py.VERSION, iniconfig.__name__, sys.version.split()[0], flush=True)
    """.trimIndent()

/** 0 when the script ran; 1, with the cause on stderr, otherwise. */
fun runPythonSmoke(): Int =
    try {
        Python3.initialize()
        Python3.exec(SMOKE_SCRIPT)
        0
    } catch (t: Throwable) {
        System.err.println("PYTHON_SMOKE_FAILED: $t")
        t.printStackTrace()
        1
    }
