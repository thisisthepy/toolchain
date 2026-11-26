package org.thisisthepy.python.multiplatform.toolchain

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.manifmerger.ManifestMerger2
import com.android.manifmerger.MergingReport
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.thisisthepy.python.multiplatform.toolchain.devagent.devAgentAndroidManifest
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonExtension
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * toolchain#162 (docs/SPEC.md §1.14, Decision (a), team lead 2026-11-27): Android refuses a socket, even on
 * loopback, to an app without `android.permission.INTERNET`, so the dev agent a debug staging asks for cannot
 * listen. The plugin adds a manifest declaring it to the AGP variants of the active Python build type when that
 * build type enables hot reload (the one that stages the marker), and to no other variant. The test applies the
 * real Android Gradle plugin (the version `usage-example` uses) because the variant API is reached reflectively.
 */
class PythonPluginDevAgentManifestTest {
    private val internet = "android.permission.INTERNET"

    /** The manifests each AGP build type's variant merges, after the plugin and AGP have run. */
    private fun variantManifests(pythonBuildType: String? = null, hotReloadDebug: Boolean = true): Map<String, List<File>> {
        val dir = createTempDirectory("python-plugin-devagent-manifest").toFile()
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdk != null) File(dir, "local.properties").writeText("sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        val project = ProjectBuilder.builder().withProjectDir(dir).build() as ProjectInternal
        pythonBuildType?.let { project.extensions.extraProperties["python.buildType"] = it }
        project.pluginManager.apply("org.thisisthepy.python.multiplatform")
        project.pluginManager.apply("com.android.application")
        project.extensions.getByType(ApplicationExtension::class.java).apply {
            namespace = "org.example.devagent"
            compileSdk = 34
        }
        val variants = mutableListOf<ApplicationVariant>()
        project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).apply {
            onVariants(selector().all()) { variants += it }
        }
        project.extensions.getByType(PythonExtension::class.java).apply {
            packaging { hotReload { serverHost = "https://localhost:8080" } }
            buildTypes {
                getByName("debug") { enableHotReload = hotReloadDebug }
                getByName("release") { }
            }
        }
        project.evaluate()
        return variants.associate { variant -> variant.buildType!! to variant.sources.manifests!!.all.get().map { it.asFile } }
    }

    /**
     * AGP's own manifest merger. Its logger type (`com.android.utils`) is on the test runtime classpath through
     * AGP but not on the compile classpath, so the merger is made reflectively instead of adding a dependency.
     */
    private fun newMerger(app: File): ManifestMerger2.Invoker {
        val level = Class.forName("com.android.utils.StdLogger\$Level")
        val logger = Class.forName("com.android.utils.StdLogger").getConstructor(level)
            .newInstance(level.enumConstants.first { it.toString() == "WARNING" })
        return ManifestMerger2::class.java.methods.first { it.name == "newMerger" }
            .invoke(null, app, logger, ManifestMerger2.MergeType.APPLICATION) as ManifestMerger2.Invoker
    }

    private fun File.declaresInternet() = isFile && readText().contains("\"$internet\"")

    @Test
    fun `the debug variant of a hot-reload build merges a manifest declaring INTERNET, and release does not`() {
        val manifests = variantManifests()

        val debug = manifests.getValue("debug")
        assertTrue(debug.any { it.declaresInternet() }, "debug merges no manifest declaring $internet: $debug")
        val release = manifests.getValue("release")
        assertFalse(release.any { it.declaresInternet() }, "release merges a manifest declaring $internet: $release")
    }

    @Test
    fun `a build whose active build type does not enable hot reload adds the manifest to no variant`() {
        listOf(variantManifests(pythonBuildType = "release"), variantManifests(hotReloadDebug = false)).forEach { manifests ->
            manifests.forEach { (buildType, files) ->
                assertFalse(files.any { it.declaresInternet() }, "$buildType merges a manifest declaring $internet: $files")
            }
        }
    }

    @Test
    fun `the plugin's manifest merges into an app that already declares INTERNET, leaving one permission`() {
        val dir = createTempDirectory("python-plugin-devagent-merge").toFile()
        val overlay = devAgentAndroidManifest(dir)
        listOf(true, false).forEach { appDeclaresIt ->
            val app = File(dir, "app-$appDeclaresIt/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.devagent">
                    ${if (appDeclaresIt) "<uses-permission android:name=\"$internet\" />" else ""}
                        <application android:label="example" />
                    </manifest>
                    """.trimIndent(),
                )
            }
            val report = newMerger(app)
                .setNamespace("org.example.devagent")
                .addFlavorAndBuildTypeManifest(overlay)
                .merge()
            assertTrue(report.result.isSuccess, "merge failed (app declares it: $appDeclaresIt): ${report.javaClass.getMethod("getLoggingRecords").invoke(report)}")
            val merged = report.getMergedDocument(MergingReport.MergedManifestKind.MERGED)
            assertEquals(1, Regex(Regex.escape("\"$internet\"")).findAll(merged).count(), merged)
        }
    }
}
