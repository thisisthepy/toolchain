package org.thisisthepy.python.multiplatform.toolchain.dsl

import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.NamedDomainObjectContainer
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl
import org.thisisthepy.python.multiplatform.toolchain.PythonMultiplatformPlugin


fun Project.python(configure: Action<PyToolChainExtension>) {
    configure.execute(extensions.getByType(PyToolChainExtension::class.java))

    this.apply {
        PythonMultiplatformPlugin::class.java
    }
}


@OptIn(ExperimentalKotlinGradlePluginApi::class)
abstract class PyToolChainExtension(project: Project):
    KotlinProjectExtension(project),
    KotlinTargetContainerWithPresetFunctions,
    KotlinTargetContainerWithJsPresetFunctions,
    KotlinTargetContainerWithWasmPresetFunctions,
    KotlinHierarchyDsl,
    HasConfigurableKotlinCompilerOptions<KotlinCommonCompilerOptions>,
    KotlinMultiplatformSourceSetConventions by KotlinMultiplatformSourceSetConventionsImpl
{
    var version: String = ""
    var pythonVersion: String = ""
    var pythonPackages: List<String> = emptyList()

    var pipCentral: List<String> = emptyList()
    var pipLocal: List<String> = emptyList()
    var pipJit: List<String> = emptyList()

    fun execute() {
        println("Python version: $pythonVersion")
        println("Python packages: $pythonPackages")
    }
}


@OptIn(ExperimentalKotlinGradlePluginApi::class)
internal object KotlinMultiplatformSourceSetConventionsImpl : KotlinMultiplatformSourceSetConventions {
    override val NamedDomainObjectContainer<KotlinSourceSet>.commonMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.commonTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.nativeMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.nativeTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.appleMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.appleTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.mingwMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.mingwTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jvmMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jvmTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jsMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.jsTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidMain by KotlinSourceSetConvention

    // KGP 2.1.0 widened `KotlinMultiplatformSourceSetConventions` with per-target (not just
    // per-family) accessors plus Android unit/instrumented test conventions -- none of this existed
    // in 2.0.0, the version this object used to be compiled against. Kept in the same alphabetical-
    // by-family grouping the interface itself uses, purely mechanical `by KotlinSourceSetConvention`
    // delegates like every entry above (the delegate resolves the backing source set from the
    // property name itself, so there is nothing target-specific to configure here).
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidUnitTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidInstrumentedTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeArm32Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeArm32Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeX64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeX64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeX86Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.androidNativeX86Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosSimulatorArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosSimulatorArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosX64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.iosX64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxArm32HfpMain by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxArm32HfpTest by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxX64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.linuxX64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosX64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.macosX64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.mingwX64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.mingwX64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosSimulatorArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosSimulatorArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosX64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.tvosX64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosArm32Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosArm32Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosDeviceArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosDeviceArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosSimulatorArm64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosSimulatorArm64Test by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosX64Main by KotlinSourceSetConvention
    override val NamedDomainObjectContainer<KotlinSourceSet>.watchosX64Test by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmJsMain by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmJsTest by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmWasiMain by KotlinSourceSetConvention

    @ExperimentalWasmDsl
    override val NamedDomainObjectContainer<KotlinSourceSet>.wasmWasiTest by KotlinSourceSetConvention
}
