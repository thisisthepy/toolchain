package org.thisisthepy.python.multiplatform.toolchain.example

import androidx.compose.ui.window.ComposeUIViewController
import python.multiplatform.ffi.Python3

fun MainViewController() =
    run {
        // Headless check of the installed app's Python (toolchain#22); see PythonSmoke.ios.kt.
        runPythonSmokeIfRequested()
        // The app's Python starts with the app, as on desktop and Android: python-multiplatform starts its dev
        // agent from Python3.initialize(), and a debug build's payload marker carries the agent's port and token
        // (live connection M3, toolchain#150). A failure is printed rather than ending the app.
        runCatching { Python3.initialize() }.onFailure { println("usage-example: Python did not start: $it") }
        ComposeUIViewController { App() }
    }
