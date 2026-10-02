package org.thisisthepy.python.multiplatform.toolchain.dsl

import org.gradle.api.model.ObjectFactory
import javax.inject.Inject


open class PythonExtension @Inject constructor(objects: ObjectFactory) {
    var compileSdk: String = ""
    var localLibraryPath: String? = null

    val defaultConfig: DefaultConfig = DefaultConfig()
    val packaging: PackagingExtension = PackagingExtension()
    val buildTypes: BuildTypesContainer = BuildTypesContainer()
    val buildFeatures: BuildFeaturesExtension = BuildFeaturesExtension()
    val sourceSets: SourceSetsExtension = objects.newInstance(SourceSetsExtension::class.java)
    val platforms: PlatformsExtension = PlatformsExtension()

    fun defaultConfig(action: DefaultConfig.() -> Unit) = defaultConfig.apply(action)
    fun packaging(action: PackagingExtension.() -> Unit) = packaging.apply(action)
    fun buildTypes(action: BuildTypesContainer.() -> Unit) = buildTypes.apply(action)
    fun buildFeatures(action: BuildFeaturesExtension.() -> Unit) = buildFeatures.apply(action)
    fun sourceSets(action: SourceSetsExtension.() -> Unit) = sourceSets.apply(action)

    // Platforms are declared directly inside `python { }`, as the example build file writes them
    // (see [PlatformsExtension]); there is no `platforms { }` block.
    fun android(name: String = "android", action: AndroidPlatformExtension.() -> Unit = {}) = platforms.android(name, action)
    fun ios(action: IosPlatformExtension.() -> Unit = {}) = platforms.ios(action)
    fun desktop(action: DesktopPlatformExtension.() -> Unit = {}) = platforms.desktop(action)

    fun androidArm64() = platforms.androidArm64()
    fun androidX64() = platforms.androidX64()

    @Deprecated(PlatformsExtension.UNSUPPORTED_ANDROID_VARIANT, level = DeprecationLevel.ERROR)
    fun androidArm32(): AndroidVariant = @Suppress("DEPRECATION_ERROR") platforms.androidArm32()

    @Deprecated(PlatformsExtension.UNSUPPORTED_ANDROID_VARIANT, level = DeprecationLevel.ERROR)
    fun androidX86(): AndroidVariant = @Suppress("DEPRECATION_ERROR") platforms.androidX86()

    fun iosArm64() = platforms.iosArm64()
    fun iosX64() = platforms.iosX64()
    fun iosSimulatorArm64() = platforms.iosSimulatorArm64()

    fun macosX64() = platforms.macosX64()
    fun macosArm64() = platforms.macosArm64()
    fun linuxX64() = platforms.linuxX64()
    fun linuxArm64() = platforms.linuxArm64()
    fun mingwX64() = platforms.mingwX64()
}

open class DefaultConfig {
    var versionCode: Int = 1
    var versionName: String = "1.0.0"
    val pip: PipExtension = PipExtension()

    fun pip(action: PipExtension.() -> Unit) = pip.apply(action)
}

open class PipExtension {
    var autoUpdateImplicitDependencies: Boolean = false
    val repositories: PipRepositories = PipRepositories()

    fun repositories(action: PipRepositories.() -> Unit) = repositories.apply(action)
}

open class PipRepositories {
    var pipCentral: RepositoryConfig? = null
    var pipLocal: RepositoryConfig? = null
    var pipJit: RepositoryConfig? = null

    fun pipCentral(action: RepositoryConfig.() -> Unit) {
        pipCentral = RepositoryConfig().apply(action)
    }
    fun pipLocal(action: RepositoryConfig.() -> Unit) {
        pipLocal = RepositoryConfig().apply(action)
    }
    fun pipJit(action: RepositoryConfig.() -> Unit) {
        pipJit = RepositoryConfig().apply(action)
    }
}

open class RepositoryConfig {
    val urls: MutableList<String> = mutableListOf()

    fun setUrl(vararg urls: String) {
        this.urls.addAll(urls)
    }
}
