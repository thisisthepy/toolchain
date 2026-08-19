package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Wires `python { sourceSets { commonMain { srcDirs(...) } } }` (`DSLBuild.kt`'s
 * `SourceSetConfig.srcDirs`) into [BuildPythonArtifactTask.packageDir] /
 * [org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python.InstallDependenciesTask.packageDir]
 * -- until now `PythonPlugin.apply`'s `afterEvaluate` only ever read `extension.localLibraryPath`,
 * so a consumer that declared `sourceSets { commonMain { srcDirs("src/commonMain/python") } }` and
 * never set `localLibraryPath` got a package directory of `null`, silently skipping both
 * `installPythonDependencies` and `buildPython` -- the same "compiles and does nothing" gap
 * `docs/ecosystem.md` names for `metaDirs`/`libDirs`/`buildFeatures`.
 *
 * [resolvePackageDir] is the pure function factored out of `PythonPlugin.apply` for this, the same
 * way [resolveActiveBuildType] and [collectInstallDependencies] were, so it can be exercised without
 * a Gradle [org.gradle.api.Project].
 *
 * `localLibraryPath` stays the explicit override it always was -- declaring both `localLibraryPath`
 * and `commonMain.srcDirs` is not a conflict, `localLibraryPath` simply wins, so every existing
 * consumer (`usage-example` sets `localLibraryPath` and no `sourceSets` block) keeps resolving
 * exactly as before.
 *
 * Before [resolvePackageDir] exists, this file fails to compile rather than failing an assertion --
 * the pre-implementation failure this repository's TDD rule asks to distinguish from a regression.
 */
class PythonPluginSourceSetTest {
    private val projectDir = File("/project")

    @Test
    fun `commonMain srcDirs is used as the package directory when localLibraryPath is unset`() {
        val commonMain =
            SourceSetConfig("commonMain").apply {
                srcDirs("src/commonMain/python")
            }

        val resolved = resolvePackageDir(projectDir, null, listOf(commonMain))

        assertEquals(File(projectDir, "src/commonMain/python"), resolved)
    }

    @Test
    fun `localLibraryPath wins over commonMain srcDirs when both are declared`() {
        val commonMain =
            SourceSetConfig("commonMain").apply {
                srcDirs("src/commonMain/python")
            }

        val resolved = resolvePackageDir(projectDir, "python", listOf(commonMain))

        assertEquals(File(projectDir, "python"), resolved)
    }

    @Test
    fun `no localLibraryPath and no declared srcDirs resolves to null`() {
        val commonMain = SourceSetConfig("commonMain")

        assertNull(resolvePackageDir(projectDir, null, listOf(commonMain)))
    }

    @Test
    fun `no commonMain source set at all resolves to null`() {
        assertNull(resolvePackageDir(projectDir, null, emptyList()))
    }

    @Test
    fun `only the first declared commonMain srcDir is used`() {
        val commonMain =
            SourceSetConfig("commonMain").apply {
                srcDirs("src/commonMain/python", "src/commonMain/otherPython")
            }

        val resolved = resolvePackageDir(projectDir, null, listOf(commonMain))

        assertEquals(File(projectDir, "src/commonMain/python"), resolved)
    }
}
