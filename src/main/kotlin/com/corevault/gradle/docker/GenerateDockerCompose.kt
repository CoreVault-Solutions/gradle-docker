package com.corevault.gradle.docker

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

open class GenerateDockerCompose : DefaultTask() {

    @get:Internal
    var configuration: Configuration? = null

    // Cached result to avoid resolving the configuration twice (once for @Input, once in @TaskAction)
    private var _moduleDependencies: Set<ModuleVersionIdentifier>? = null

    init {
        group = "Docker"
    }

    @get:Input
    val moduleDependencies: Set<ModuleVersionIdentifier>
        get() = _moduleDependencies ?: run {
            logger.info("Resolving Docker template dependencies from configuration ${configuration!!.name}...")
            configuration!!.resolvedConfiguration
                .resolvedArtifacts
                .map { it.moduleVersion.id }
                .toSet()
                .also { _moduleDependencies = it }
        }

    @get:Input
    val extraTemplateTokens: Map<String, String>
        get() = dockerComposeExtension.templateTokens

    @get:InputFiles
    val template: File
        get() = dockerComposeExtension.template

    @get:OutputFile
    val dockerComposeFile: File
        get() = dockerComposeExtension.dockerComposeFile

    @get:Internal
    val dockerComposeExtension: DockerComposeExtension
        get() = project.extensions.findByType(DockerComposeExtension::class.java)!!

    @TaskAction
    fun run() {
        val templateFile = template
        if (!templateFile.exists()) {
            throw IllegalStateException("Could not find specified template file ${templateFile}")
        }

        val tokenMap = mutableMapOf<String, String>()
        moduleDependencies.forEach { id ->
            tokenMap["{{${id.group}:${id.name}}}"] = id.version
        }
        extraTemplateTokens.forEach { (key, value) ->
            tokenMap["{{$key}}"] = value
        }

        dockerComposeFile.printWriter().use { writer ->
            templateFile.forEachLine { line ->
                writer.println(replaceAll(line, tokenMap))
            }
        }
    }

    internal fun replaceAll(line: String, tokenMap: Map<String, String>): String {
        var result = line
        tokenMap.forEach { (token, value) -> result = result.replace(token, value) }
        val unmatchedTokens = Regex("""\{\{.*?\}\}""").findAll(result).map { it.value }.toList()
        if (unmatchedTokens.isNotEmpty()) {
            throw GradleException(
                "Failed to resolve Docker dependencies declared in $template: $unmatchedTokens. " +
                    "Known dependencies: $tokenMap",
            )
        }
        return result
    }
}
