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

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Exec

class DockerComposePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("dockerCompose", DockerComposeExtension::class.java, project)
        val dockerConfiguration = project.configurations.maybeCreate("docker")

        // sls-packaging adds a 'productDependencies' configuration with inferred lower bounds of products.
        // Wire it up automatically so users don't need to add the dependency manually.
        project.subprojects { subproject ->
            subproject.plugins.withId("com.palantir.product-dependency-introspection") {
                dockerConfiguration.dependencies.add(
                    subproject.dependencies.project(
                        mapOf("path" to subproject.path, "configuration" to "productDependencies"),
                    ),
                )
            }
        }
        project.plugins.withId("com.palantir.product-dependency-introspection") {
            dockerConfiguration.extendsFrom(project.configurations.getByName("productDependencies"))
        }

        // Lazily resolve the docker configuration's artifacts into {{group:name}} -> version tokens.
        // Using the artifact-view provider keeps resolution out of configuration time and out of the
        // task's serialized state (configuration-cache safe — no Configuration held by the task).
        val moduleVersionTokens = dockerConfiguration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            artifacts.asSequence()
                .map { it.id.componentIdentifier }
                .filterIsInstance<ModuleComponentIdentifier>()
                .associate { "{{${it.group}:${it.module}}}" to it.version }
        }

        project.tasks.register("generateDockerCompose", GenerateDockerCompose::class.java) { t ->
            t.moduleVersionTokens.set(moduleVersionTokens)
            t.extraTemplateTokens.set(project.provider { ext.templateTokens })
            t.templateFile.set(project.layout.file(project.provider { ext.template }))
            t.dockerComposeFile.set(project.layout.file(project.provider { ext.dockerComposeFile }))
        }

        val dockerComposeUp = project.tasks.register("dockerComposeUp", Exec::class.java) { t ->
            t.group = "Docker"
            t.description = "Executes docker-compose up"
            // Route subprocess output through Gradle logging so it appears in build output/result.output
            t.logging.captureStandardOutput(LogLevel.INFO)
            t.logging.captureStandardError(LogLevel.ERROR)
        }

        val dockerComposeDown = project.tasks.register("dockerComposeDown", Exec::class.java) { t ->
            t.group = "Docker"
            t.description = "Executes docker-compose down"
            t.logging.captureStandardOutput(LogLevel.INFO)
            t.logging.captureStandardError(LogLevel.ERROR)
        }

        project.afterEvaluate {
            val ext = project.extensions.findByType(DockerComposeExtension::class.java)!!

            // Split the configured compose command (e.g. "docker-compose" or "docker compose"):
            // first token is the executable, remaining tokens are leading args.
            val composeTokens = ext.composeCommand.trim().split(Regex("\\s+"))
            val executable = composeTokens.first()
            val leadingArgs: List<Any> = composeTokens.drop(1)

            // Configure the exec tasks now that the extension is fully evaluated
            dockerComposeUp.get().let { up ->
                up.executable(executable)
                up.args(*(leadingArgs + listOf<Any>("-f", ext.dockerComposeFile, "up", "-d")).toTypedArray())
            }

            dockerComposeDown.get().let { down ->
                down.executable(executable)
                down.args(*(leadingArgs + listOf<Any>("-f", ext.dockerComposeFile, "down")).toTypedArray())
            }
        }
    }
}
