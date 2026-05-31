/*
 * (c) Copyright 2015-2021 Palantir Technologies Inc. All rights reserved.
 * Modifications and additions (c) Copyright 2025-2026 CoreVault Solutions.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file is part of the CoreVault gradle-docker plugin, a Kotlin port and
 * derivative of the Palantir gradle-docker plugin, with changes by CoreVault Solutions.
 */
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
