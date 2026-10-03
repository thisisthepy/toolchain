package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wires `python { sourceSets { commonMain { dependencies { integration(...) } } } }`
 * (`DSLBuild.kt`'s `DependenciesExtension.integrations`) into the same install list
 * `implementation()` already reaches -- until now `PythonPlugin.apply`'s `afterEvaluate` only read
 * `sourceSet.dependencies.implementations`, so an `integration()` declaration compiled but had zero
 * effect: it was never passed to `InstallDependenciesTask.dependenciesList`, so `installWithPackpack`
 * never saw it and no `uv add` for it ever ran.
 *
 * [collectInstallDependencies] is the pure function factored out of `PythonPlugin.apply` for this,
 * the same way `resolveActiveBuildType` was factored out for `buildTypes`, so it can be exercised
 * without a Gradle `Project`.
 *
 * This only makes an `integration()` dependency install like a normal one -- `pypackpack`'s `uv`
 * backend (`installWithPackpack` / `DependencyBackend.addDependencies`) has no concept of an
 * "integration" dependency type at all (it takes one flat `List<String>` with no type parameter), and
 * grepping `pypackpack` for `KLIBDEPENS` returns nothing. The DSL comment in
 * `(플러그인예시)build.gradle.kts` describing `integration()` ("kotlin dependent python package -
 * requires KLIBDEPENS file in whl dist directory ... KLIBDEPENS 파일 없으면 install을 그냥 쓰라고 워닝
 * 표시") is not implemented here: there is no wheel dist-info inspection anywhere in this repository
 * or `pypackpack`, so a check that warns when the installed wheel lacks a `KLIBDEPENS` file cannot be
 * wired without inventing that behavior from a single code comment. This test file only pins the part
 * that is real: the dependency reaches installation instead of being silently dropped.
 *
 * Before [collectInstallDependencies] exists, this file fails to compile rather than failing an
 * assertion -- the pre-implementation failure this repository's TDD rule asks to distinguish from a
 * regression.
 */
class PythonPluginDependencyTest {
    @Test
    fun `integration dependencies flow into the install list alongside implementation ones`() {
        val commonMain =
            SourceSetConfig("commonMain").apply {
                dependencies {
                    implementation("pyzmq")
                    integration("pycomposeui")
                }
            }

        val deps = collectInstallDependencies(listOf(commonMain))

        assertEquals(listOf("pyzmq", "pycomposeui"), deps)
    }

    @Test
    fun `integration dependencies from multiple source sets are all collected`() {
        val commonMain =
            SourceSetConfig("commonMain").apply {
                dependencies { implementation("pyzmq") }
            }
        val androidMain =
            SourceSetConfig("androidMain").apply {
                dependencies { integration("pycomposeui") }
            }

        val deps = collectInstallDependencies(listOf(commonMain, androidMain))

        assertEquals(listOf("pyzmq", "pycomposeui"), deps)
    }

    @Test
    fun `no declared dependencies produces an empty list`() {
        val commonMain = SourceSetConfig("commonMain")

        assertEquals(emptyList(), collectInstallDependencies(listOf(commonMain)))
    }
}
