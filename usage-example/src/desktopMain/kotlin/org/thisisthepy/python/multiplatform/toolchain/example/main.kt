package org.thisisthepy.python.multiplatform.toolchain.example

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import python.multiplatform.ffi.Python3
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    // Headless check of the packaged app's Python (toolchain#22); see PythonSmoke.kt.
    if (PYTHON_SMOKE_FLAG in args) exitProcess(runPythonSmoke())

    // The app's Python starts with the app, not on first use. python-multiplatform starts its dev
    // agent from Python3.initialize() (a debug launch with PYTHON_MULTIPLATFORM_DEV_AGENT set, its
    // SPEC L-12), so an app that never initialised Python could not be reached by a notebook
    // (live connection M1, docs/design/live-connection.md).
    Python3.initialize()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Toolchain",
        ) {
            App()
        }
    }
}
