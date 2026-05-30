package com.corevault.gradle.docker

import org.gradle.api.Project
import java.io.File

open class DockerComposeExtension(private val project: Project) {

    var template: File = project.file("docker-compose.yml.template")
    var dockerComposeFile: File = project.file("docker-compose.yml")
    val templateTokens: MutableMap<String, String> = mutableMapOf()

    // Groovy DSL: template 'path/to/file'
    fun template(path: Any) {
        template = project.file(path)
    }

    // Groovy DSL: dockerComposeFile 'path/to/file'
    fun dockerComposeFile(path: Any) {
        dockerComposeFile = project.file(path)
    }

    // Groovy DSL: templateTokens([key: 'value'])
    fun templateTokens(tokens: Map<String, String>) {
        templateTokens.clear()
        templateTokens.putAll(tokens)
    }

    fun templateToken(key: String, value: String) {
        templateTokens[key] = value
    }
}
