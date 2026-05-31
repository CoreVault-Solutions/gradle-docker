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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Generates a docker-compose file from a template, substituting `{{group:name}}` tokens with the
 * resolved dependency versions and `{{key}}` tokens with user-supplied values.
 *
 * All inputs are wired as lazy task properties (resolved dependency versions, template tokens, the
 * template/output files) rather than read from the project at execution time, so the task is
 * compatible with the Gradle configuration cache (it never holds a `Configuration` or `Project`).
 */
abstract class GenerateDockerCompose : DefaultTask() {

    /** `{{group:name}}` -> version, derived from the resolved `docker` configuration at wiring time. */
    @get:Input
    abstract val moduleVersionTokens: MapProperty<String, String>

    /** Raw token key -> value (the `{{` `}}` is added during substitution). */
    @get:Input
    abstract val extraTemplateTokens: MapProperty<String, String>

    // @InputFiles (not @InputFile) so a configured-but-missing template doesn't fail snapshotting;
    // the friendly "Could not find" error is raised in the task action instead.
    @get:InputFiles
    abstract val templateFile: RegularFileProperty

    @get:OutputFile
    abstract val dockerComposeFile: RegularFileProperty

    init {
        group = "Docker"
    }

    @TaskAction
    fun run() {
        val template = templateFile.get().asFile
        check(template.exists()) { "Could not find specified template file $template" }

        val tokens = LinkedHashMap<String, String>()
        moduleVersionTokens.get().forEach { (token, version) -> tokens[token] = version }
        extraTemplateTokens.get().forEach { (key, value) -> tokens["{{$key}}"] = value }

        dockerComposeFile.get().asFile.printWriter().use { writer ->
            template.forEachLine { line -> writer.println(replaceAll(template, line, tokens)) }
        }
    }

    internal fun replaceAll(template: java.io.File, line: String, tokenMap: Map<String, String>): String {
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
