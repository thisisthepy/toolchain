package org.thisisthepy.python.multiplatform.toolchain.devagent

import java.io.File

/**
 * toolchain#162 (docs/SPEC.md §1.14; Decision (a), team lead 2026-11-27). Android refuses a socket, even on
 * loopback, to an app without `android.permission.INTERNET`, so the dev agent a debug staging asks for could
 * not listen (`[Errno 1] Operation not permitted`). python-multiplatform's AAR is one artifact for debug and
 * release, so it cannot declare a debug-only permission; this plugin knows which builds carry the agent, and
 * adds this manifest to those AGP variants only (`PythonPlugin`'s `attachDevAgentInternetPermission`).
 */
const val DEV_AGENT_ANDROID_MANIFEST_DIRECTORY = "pythonStaging/androidDevAgent"

/** The manifest merged into a variant that carries the dev agent: the permission and nothing else. */
const val DEV_AGENT_ANDROID_MANIFEST_XML =
    """<?xml version="1.0" encoding="utf-8"?>
<!-- Written by the toolchain plugin (toolchain#162): this build type carries python-multiplatform's dev agent,
     which listens on the device's loopback, and Android refuses a socket to an app without INTERNET. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
"""

/** Writes [DEV_AGENT_ANDROID_MANIFEST_XML] under [buildDir] (unchanged content is not rewritten) and returns it. */
fun devAgentAndroidManifest(buildDir: File): File {
    val file = File(buildDir, "$DEV_AGENT_ANDROID_MANIFEST_DIRECTORY/AndroidManifest.xml")
    if (!file.isFile || file.readText() != DEV_AGENT_ANDROID_MANIFEST_XML) {
        file.parentFile.mkdirs()
        file.writeText(DEV_AGENT_ANDROID_MANIFEST_XML)
    }
    return file
}
