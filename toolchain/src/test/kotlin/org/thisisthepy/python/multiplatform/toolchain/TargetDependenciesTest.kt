package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.bundle.PythonStagingPlatform
import org.thisisthepy.python.multiplatform.toolchain.dsl.BuildTypesContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.PlatformsExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.ProjectFlavorsContainer
import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonVersion
import org.thisisthepy.python.multiplatform.toolchain.dsl.SourceSetConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.10, issue #16: `commonMain` dependencies go to every variant, `androidMain` only to
 * Android variants, `iosMain` only to iOS, `desktopMain` only to desktop, and `<flavor>Main` only to
 * that flavor's variants. The source-set names are `(플러그인예시)build.gradle.kts`'s.
 */
class TargetDependenciesTest {
    /** The example build file's source sets, plus one dependency per family and flavor to tell them apart. */
    private fun exampleSourceSets(): List<SourceSetConfig> =
        listOf(
            SourceSetConfig("commonMain").apply {
                dependencies {
                    implementation("pyzmq")
                    implementation("rpds-py==0.20.0")
                }
            },
            SourceSetConfig("androidMain").apply { dependencies { integration("pycomposeui") } },
            SourceSetConfig("iosMain").apply { dependencies { implementation("ios-only") } },
            SourceSetConfig("desktopMain").apply { dependencies { implementation("desktop-only") } },
            SourceSetConfig("freeMain").apply { dependencies { implementation("ads-sdk") } },
            SourceSetConfig("paidMain").apply { dependencies { implementation("billing-sdk") } },
        )

    @Test
    fun `a variant reads commonMain, its family's source set, and its flavor's`() {
        assertEquals(listOf("commonMain", "androidMain"), dependencySourceSetNames(PythonStagingPlatform.ANDROID, null))
        assertEquals(listOf("commonMain", "iosMain", "paidMain"), dependencySourceSetNames(PythonStagingPlatform.IOS, "paid"))
        assertEquals(listOf("commonMain", "desktopMain"), dependencySourceSetNames(PythonStagingPlatform.DESKTOP, null))
    }

    @Test
    fun `each family gets commonMain plus its own dependencies and no other family's`() {
        val sourceSets = exampleSourceSets()

        assertEquals(
            listOf("pyzmq", "rpds-py==0.20.0", "pycomposeui"),
            collectTargetDependencies(sourceSets, PythonStagingPlatform.ANDROID, null),
        )
        assertEquals(
            listOf("pyzmq", "rpds-py==0.20.0", "ios-only"),
            collectTargetDependencies(sourceSets, PythonStagingPlatform.IOS, null),
        )
        assertEquals(
            listOf("pyzmq", "rpds-py==0.20.0", "desktop-only"),
            collectTargetDependencies(sourceSets, PythonStagingPlatform.DESKTOP, null),
        )
    }

    @Test
    fun `a flavor's source set reaches only that flavor`() {
        val sourceSets = exampleSourceSets()

        assertEquals(
            listOf("pyzmq", "rpds-py==0.20.0", "pycomposeui", "ads-sdk"),
            collectTargetDependencies(sourceSets, PythonStagingPlatform.ANDROID, "free"),
        )
        assertEquals(
            listOf("pyzmq", "rpds-py==0.20.0", "ios-only", "billing-sdk"),
            collectTargetDependencies(sourceSets, PythonStagingPlatform.IOS, "paid"),
        )
    }

    @Test
    fun `resolved variants map their triples to the right family`() {
        val platforms = PlatformsExtension().apply {
            androidArm64()
            iosSimulatorArm64()
            mingwX64()
        }
        val flavors = ProjectFlavorsContainer().apply { create("free") }
        val sourceSets = exampleSourceSets()

        val byVariant =
            resolveVariants(platforms, BuildTypesContainer(), flavors).associate { variant ->
                variant.platformVariantName to
                    collectTargetDependencies(sourceSets, PythonStagingPlatform.forTarget(variant.target), variant.flavorName)
            }

        assertEquals(listOf("pyzmq", "rpds-py==0.20.0", "pycomposeui", "ads-sdk"), byVariant["androidArm64"])
        assertEquals(listOf("pyzmq", "rpds-py==0.20.0", "ios-only", "ads-sdk"), byVariant["iosSimulatorArm64"])
        assertEquals(listOf("pyzmq", "rpds-py==0.20.0", "desktop-only", "ads-sdk"), byVariant["mingwX64"])
    }

    @Test
    fun `a requirement repeated across source sets is installed once`() {
        val sourceSets =
            listOf(
                SourceSetConfig("commonMain").apply { dependencies { implementation("six") } },
                SourceSetConfig("androidMain").apply { dependencies { implementation("six") } },
            )

        assertEquals(listOf("six"), collectTargetDependencies(sourceSets, PythonStagingPlatform.ANDROID, null))
    }

    @Test
    fun `missing source sets contribute nothing`() {
        assertEquals(emptyList(), collectTargetDependencies(emptyList(), PythonStagingPlatform.IOS, "free"))
    }

    @Test
    fun `the dependency set drops the build type, so debug and release share one install`() {
        val buildTypes = BuildTypesContainer().apply {
            getByName("debug") {}
            getByName("release") {}
        }
        val flavors = ProjectFlavorsContainer().apply { create("free") }
        val variants = resolveVariants(PlatformsExtension().apply { androidArm64() }, buildTypes, flavors)

        assertEquals(listOf("androidArm64-free"), variants.map { it.dependencySetName }.distinct())
        assertEquals(listOf("AndroidArm64Free"), variants.map { it.dependencyTaskSuffix }.distinct())

        val unflavored = resolveVariants(PlatformsExtension().apply { iosArm64() }, buildTypes)
        assertEquals(listOf("iosArm64"), unflavored.map { it.dependencySetName }.distinct())
        assertEquals(listOf("IosArm64"), unflavored.map { it.dependencyTaskSuffix }.distinct())
    }

    @Test
    fun `dependencies in a source set no variant reads are refused by name`() {
        val sourceSets = exampleSourceSets() + SourceSetConfig("androidArm64Main").apply {
            dependencies { implementation("numpy") }
        }

        val rejection = assertNotNull(unreachableSourceSetRejection(sourceSets, listOf("free", "paid")))
        assertTrue("androidArm64Main" in rejection, rejection)
        assertFalse("'freeMain'" in rejection.substringBefore("which no variant"), rejection)
    }

    @Test
    fun `a flavor source set without that flavor is refused, and an empty unknown source set is not`() {
        val freeMain = SourceSetConfig("freeMain").apply { dependencies { implementation("ads-sdk") } }

        assertNotNull(unreachableSourceSetRejection(listOf(freeMain), flavorNames = emptyList()))
        assertNull(unreachableSourceSetRejection(listOf(freeMain), flavorNames = listOf("free")))
        assertNull(unreachableSourceSetRejection(listOf(SourceSetConfig("fooMain")), flavorNames = emptyList()))
        assertNull(unreachableSourceSetRejection(exampleSourceSets(), flavorNames = listOf("free", "paid")))
    }

    @Test
    fun `install options pin the runtime's major-minor and refuse sdists, keeping the pip repositories`() {
        val pip = mapOf("default-index" to "https://pypi.org/simple", "find-links" to "/wheels")

        val args = targetInstallArguments(PythonVersion.parse("3.14.7"), pip)

        assertEquals("3.14", args["python-version"])
        assertEquals(":all:", args["only-binary"])
        assertEquals("https://pypi.org/simple", args["default-index"])
        assertEquals("/wheels", args["find-links"])
        assertEquals("3.11", targetInstallArguments(PythonVersion.parse("3.11.9-alpha"), emptyMap())["python-version"])
    }

    @Test
    fun `without compileSdk there is no python-version, and only-binary is still set`() {
        val args = targetInstallArguments(null, emptyMap())

        assertFalse("python-version" in args)
        assertEquals(mapOf("only-binary" to ":all:"), args)
    }

    @Test
    fun `the install directory is build pythonDeps and the dependency set`() {
        val buildDir = File("/project/build")

        assertEquals(File("/project/build/pythonDeps/androidArm64-free"), targetDependenciesDir(buildDir, "androidArm64-free"))
        assertEquals(File("/project/build/pythonDeps/host"), targetDependenciesDir(buildDir, HOST_DEPENDENCY_SET))
    }
}
