package com.corevault.gradle.docker

import org.gradle.api.Project
import java.io.File

open class DockerComposeExtension(private val project: Project) {

    var template: File = project.file("docker-compose.yml.template")
    var dockerComposeFile: File = project.file("docker-compose.yml")
    val templateTokens: MutableMap<String, String> = mutableMapOf()

    // The Compose CLI to invoke. Defaults to the v1 standalone binary `docker-compose`.
    // Set to "docker compose" for the Compose v2 plugin; the value is split on whitespace,
    // so the first token becomes the executable and any remaining tokens are leading args.
    var composeCommand: String = "docker-compose"

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
