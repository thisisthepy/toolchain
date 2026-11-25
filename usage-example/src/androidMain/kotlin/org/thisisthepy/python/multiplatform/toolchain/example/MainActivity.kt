package org.thisisthepy.python.multiplatform.toolchain.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import python.multiplatform.env.PythonBootstrap


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Headless check of the installed APK's Python (toolchain#22); see PythonSmoke.android.kt.
        if (intent?.getBooleanExtra(PYTHON_SMOKE_EXTRA, false) == true) {
            runPythonSmoke(this)
            finish()
            return
        }

        // The app's Python starts with the app, as on desktop: python-multiplatform starts its dev agent from
        // Python3.initialize() (here through PythonBootstrap), and a debug build's payload marker carries the
        // agent's port and token (live connection M2, toolchain#150).
        PythonBootstrap.initialize(this)

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
