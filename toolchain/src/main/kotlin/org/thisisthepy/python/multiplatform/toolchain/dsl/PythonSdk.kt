package org.thisisthepy.python.multiplatform.toolchain.dsl

import org.gradle.api.SupportsKotlinAssignmentOverloading

/**
 * The CPython versions python-multiplatform actually provides a runtime for: its `gradle.properties`
 * `pythonVersion`, 3.15.0t. The runtime is CPython 3.15.0t, free-threaded only across desktop, Android
 * and iOS (python-multiplatform#158, #163, #328, toolchain#76, #77), so the temporary GIL exception
 * is gone and 3.15.0t is the published runtime.
 *
 * Add a version here and a constant to [PythonSdkVersion] when python-multiplatform pins one.
 */
object PythonReleaseServer {
    val PUBLISHED: List<String> = listOf("3.15.0t")
}

/** `compileSdk = PY3_15_0`: a version python-multiplatform provides. Only those exist as constants. */
enum class PythonSdkVersion(val version: String) {
    PY3_15_0("3.15.0t"),
}

/**
 * `compileSdk`, in either form `(플러그인예시)build.gradle.kts` writes: `compileSdk = "3.11.9-alpha"`
 * or `compileSdk = PY3_11_9_ALPHA`. A Kotlin property has one type, so this is a value object whose
 * `=` Gradle's Kotlin DSL turns into [assign] (assignment overloading); plain Kotlin calls [assign].
 *
 * The forms differ in meaning (INTENT §4.1): a string may name a version the server does not
 * publish, to be built automatically; a constant can only name a published one.
 */
@SupportsKotlinAssignmentOverloading
class PythonSdk {
    var requested: String = ""
        private set
    var constant: PythonSdkVersion? = null
        private set

    fun assign(version: String) {
        requested = version
        constant = null
    }

    fun assign(version: PythonSdkVersion) {
        requested = version.version
        constant = version
    }

    override fun toString(): String = requested
}

/**
 * A resolved `compileSdk`. [rejection] is set when the version cannot be provided: it is carried to
 * the bundling task and fails it there, not the configuration (AGENTS.md §14).
 */
data class ResolvedPythonSdk(
    val version: PythonVersion,
    val fromConstant: Boolean,
    val rejection: String?,
)

/**
 * `null` when nothing is assigned. A malformed string throws (a configuration mistake, as before).
 * `X.Y` matches the newest published `X.Y.*`.
 */
fun resolvePythonSdk(sdk: PythonSdk): ResolvedPythonSdk? {
    if (sdk.requested.isBlank()) return null
    val parsed = PythonVersion.parse(sdk.requested)
    val explicitMicro = sdk.requested.trim().count { it == '.' } >= 2

    val published =
        PythonReleaseServer.PUBLISHED
            .map { PythonVersion.parse(it) }
            .filter { it.major == parsed.major && it.minor == parsed.minor }
            .filter { !explicitMicro || it.micro == parsed.micro }
            .filter { it.channel == parsed.channel }
            .maxByOrNull { it.micro }

    if (parsed.major == 3 && parsed.minor < 15) {
        return ResolvedPythonSdk(
            version = parsed,
            fromConstant = false,
            rejection =
                "Python compileSdk '${sdk.requested}' is a GIL-only CPython. The runtime is CPython 3.15t, " +
                    "free-threaded only (python-multiplatform #158): use compileSdk = \"3.15t\" " +
                    "(published: ${PythonReleaseServer.PUBLISHED.joinToString()}).",
        )
    }

    return if (published != null) {
        ResolvedPythonSdk(version = published, fromConstant = sdk.constant != null, rejection = null)
    } else {
        ResolvedPythonSdk(
            version = parsed,
            fromConstant = false,
            rejection =
                "Python compileSdk '${sdk.requested}' is not one python-multiplatform " +
                    "provides a runtime for (published: ${PythonReleaseServer.PUBLISHED.joinToString()}). " +
                    "Building an unpublished CPython automatically is not available yet; use a " +
                    "published version.",
        )
    }
}
