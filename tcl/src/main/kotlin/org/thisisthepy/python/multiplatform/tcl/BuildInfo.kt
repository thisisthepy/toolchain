package org.thisisthepy.python.multiplatform.tcl

import java.util.Properties

/**
 * Facts the build writes into tcl's resources (`generateBuildInfo` in `tcl/build.gradle.kts`).
 *
 * [version] is tcl's version, and so the toolchain-lite wheel's: `publish-pypi.yml` refuses to
 * publish unless the installed binary prints the version in `pyproject.toml` (docs/SPEC.md 2.2).
 */
internal object BuildInfo {
    val version: String by lazy {
        BuildInfo::class.java.getResourceAsStream("build-info.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?: error("build-info.properties is missing from tcl's resources")
    }
}
