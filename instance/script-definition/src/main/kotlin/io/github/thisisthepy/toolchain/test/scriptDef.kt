/**
 * Python Build Tool-Chain Script Definition
 */

package io.github.thisisthepy.toolchain.test

import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.*
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm


/**
 * Superclass for all scripts that use the Python build tool-chain.
 */
abstract class ScriptWithPythonBuildToolchain


// @KotlinScript annotation marks a script definition class
@KotlinScript(
    // File extension for the script type
    fileExtension = "gradle.kts",
    // Compilation configuration for the script type
    compilationConfiguration = ScriptWithPythonBuildToolchainConfiguration::class
)


object ScriptWithPythonBuildToolchainConfiguration: ScriptCompilationConfiguration({
    // Implicit imports for all scripts of this type
    defaultImports(DependsOn::class, Repository::class)

    jvm {
        // Extract the whole classpath from context classloader and use it as dependencies
        dependenciesFromCurrentContext(wholeClasspath = true)
    }

    // Callbacks
    refineConfiguration {
        // Process specified annotations with the provided handler
        onAnnotations(DependsOn::class, Repository::class, handler = ::configurePythonBuildToolchainOnAnnotations)
    }
})


// Handler that reconfigures the compilation on the fly
fun configurePythonBuildToolchainOnAnnotations(
    context: ScriptConfigurationRefinementContext
): ResultWithDiagnostics<ScriptCompilationConfiguration> {
    val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)?.takeIf { it.isNotEmpty() }
        ?: return context.compilationConfiguration.asSuccess()
    return runBlocking {
        resolver.resolveFromScriptSourceAnnotations(annotations)
    }.onSuccess {
        context.compilationConfiguration.with {
            dependencies.append(JvmDependency(it))
        }.asSuccess()
    }
}


private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

