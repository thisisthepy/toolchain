package org.thisisthepy.python.multiplatform.toolchain

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Task
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.provider.ListProperty
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * toolchain#166 (docs/SPEC.md §1.x, Decision (a), team lead 2026-11-27): AGP's default `ignoreAssetsPatterns`
 * holds `<dir>_*`, so `merge*Assets` dropped every staged Python directory whose name starts with `_` (torch's
 * `_dynamo`, numpy's `_core`) and the app failed with "No module named". The plugin removes that one entry and
 * keeps every other pattern, the app's own included. Real AGP, as `PythonPluginDevAgentManifestTest`.
 */
class PythonPluginAndroidAssetsPatternTest {

    /** The patterns each `merge<Variant>Assets` task will apply, after the plugin and AGP have run. */
    private fun mergeAssetsPatterns(appPatterns: List<String>? = null): Map<String, List<String>> {
        val dir = createTempDirectory("python-plugin-assets-pattern").toFile()
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdk != null) File(dir, "local.properties").writeText("sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        val project = ProjectBuilder.builder().withProjectDir(dir).build() as ProjectInternal
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.pluginManager.apply("com.android.application")
        project.extensions.getByType(ApplicationExtension::class.java).apply {
            namespace = "org.example.assets"
            compileSdk = 34
            appPatterns?.let { androidResources.ignoreAssetsPatterns.apply { clear(); addAll(it) } }
        }
        project.evaluate()
        return listOf("Debug", "Release").associateWith { variant ->
            val task: Task = project.tasks.getByName("merge${variant}Assets")
            @Suppress("UNCHECKED_CAST")
            val patterns = task.javaClass.methods.first { it.name == "getIgnoreAssetsPatterns" && it.parameterCount == 0 }
                .invoke(task) as ListProperty<String>
            patterns.getOrElse(emptyList())
        }
    }

    @Test
    fun `merging assets keeps directories whose names start with an underscore`() {
        mergeAssetsPatterns().forEach { (variant, patterns) ->
            assertFalse("<dir>_*" in patterns, "merge${variant}Assets still drops `_` directories: $patterns")
            assertTrue(".*" in patterns && "!.git" in patterns, "merge${variant}Assets lost AGP's other defaults: $patterns")
        }
    }

    @Test
    fun `the app's own patterns are kept and only the underscore entry is removed`() {
        mergeAssetsPatterns(listOf("!secret.txt", "<dir>_*", "!*.bak")).forEach { (variant, patterns) ->
            assertTrue(patterns == listOf("!secret.txt", "!*.bak"), "merge${variant}Assets: $patterns")
        }
    }
}
