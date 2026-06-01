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
package com.corevaultsolutions.gradle.docker

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Mirrors a representative subset of the Groovy-DSL plugin tests, but applies and configures the
 * plugins from a Kotlin DSL `build.gradle.kts` script. This proves the plugins (and their type-safe
 * extension accessors) work from Kotlin build scripts as well as Groovy ones.
 */
class CoreVaultDockerPluginKotlinDslTests : AbstractPluginTest() {

    /** The Kotlin DSL build script for the test project (instead of the Groovy `build.gradle`). */
    private val buildFileKts: java.io.File
        get() = file("build.gradle.kts")

    // -------------------------------------------------------------------------
    // docker plugin (no Docker daemon required)
    // -------------------------------------------------------------------------

    @Test
    fun `fail when missing docker configuration (kotlin dsl)`() {
        buildFileKts.writeText(
            """
            plugins {
                id("com.corevaultsolutions.docker")
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").buildAndFail()
        assertTrue(result.output.contains("imageName is a required docker configuration item."))
    }

    @Test
    fun `tag and push tasks created for each tag (kotlin dsl)`() {
        val id = "kts5"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\n")
        buildFileKts.writeText(
            """
            plugins {
                id("com.corevaultsolutions.docker")
            }
            docker {
                imageName = "$id"
                tags = setOf("latest", "another", "withTaskName@2.0", "newImageName@$id-new:latest")
                tag("withTaskNameByTag", "$id:new-latest")
            }
            """.trimIndent(),
        )
        val result = gradleRunner("tasks").build()
        assertTrue(result.output.contains("dockerTagLatest"))
        assertTrue(result.output.contains("dockerTagAnother"))
        assertTrue(result.output.contains("dockerTagWithTaskName"))
        assertTrue(result.output.contains("dockerTagNewImageName"))
        assertTrue(result.output.contains("dockerTagWithTaskNameByTag"))
        assertTrue(result.output.contains("dockerPushLatest"))
        assertTrue(result.output.contains("dockerPushWithTaskNameByTag"))
    }

    @Test
    fun `target stage is forwarded to docker build command (kotlin dsl)`() {
        file("Dockerfile").writeText("FROM alpine:3.2 AS runtime\n")
        buildFileKts.writeText(
            """
            plugins {
                id("com.corevaultsolutions.docker")
            }
            docker {
                imageName = "target-image-kts"
                target = "runtime"
            }
            tasks.register("printInfo") {
                doLast {
                    println("DOCKER: ${'$'}{tasks.named("docker").get().property("commandLine")}")
                }
            }
            """.trimIndent(),
        )
        val result = gradleRunner("printInfo").build()
        assertTrue(result.output.contains("DOCKER: [docker, build, --target, runtime, -t, target-image-kts, .]"))
    }

    // -------------------------------------------------------------------------
    // docker plugin (Docker daemon required)
    // -------------------------------------------------------------------------

    @Test
    fun `creates a docker container with default configuration (kotlin dsl)`() {
        assumeDockerAvailable()
        val id = "kts1"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\n")
        buildFileKts.writeText(
            """
            plugins {
                id("com.corevaultsolutions.docker")
            }
            docker {
                imageName = "$id"
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertTrue(exec("docker", "inspect", "--format", "{{.Author}}", id).trim() == id)
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `labels and build args applied via kotlin dsl`() {
        assumeDockerAvailable()
        val id = "kts7"
        file("Dockerfile").writeText(
            """
            FROM alpine:3.2
            ARG BUILD_ARG_NO_DEFAULT
            ENV ENV_BUILD_ARG_NO_DEFAULT ${'$'}BUILD_ARG_NO_DEFAULT
            """.trimIndent(),
        )
        buildFileKts.writeText(
            """
            plugins {
                id("com.corevaultsolutions.docker")
            }
            docker {
                imageName = "$id"
                labels["test-label"] = "test-value"
                buildArgs = mapOf("BUILD_ARG_NO_DEFAULT" to "gradleBuildArg")
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertTrue(exec("docker", "inspect", "--format", "{{.Config.Labels}}", id).contains("test-label:test-value"))
        assertTrue(exec("docker", "inspect", "--format", "{{.Config.Env}}", id).contains("ENV_BUILD_ARG_NO_DEFAULT=gradleBuildArg"))
        execCond("docker", "rmi", "-f", id)
    }

    // -------------------------------------------------------------------------
    // docker-compose plugin (no daemon required; resolves deps from Maven Central)
    // -------------------------------------------------------------------------

    @Test
    fun `docker-compose generates from template (kotlin dsl)`() {
        file("docker-compose.yml.template").writeText(
            """
            service1:
              image: 'repository/service1:{{com.google.guava:guava}}'
            current-service:
              image: '{{currentImageName}}'
            """.trimIndent(),
        )
        buildFileKts.writeText(
            """
            plugins {
                id("com.corevaultsolutions.docker-compose")
            }
            repositories {
                mavenCentral()
            }
            dockerCompose {
                templateTokens(mapOf("currentImageName" to "snapshot.docker.registry/current-service:1.0.0"))
            }
            dependencies {
                "docker"("com.google.guava:guava:17.0")
            }
            """.trimIndent(),
        )
        val result = gradleRunner("generateDockerCompose", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateDockerCompose")?.outcome)
        val composeText = file("docker-compose.yml").readText()
        assertTrue(composeText.contains("repository/service1:17.0"))
        assertTrue(composeText.contains("image: 'snapshot.docker.registry/current-service:1.0.0'"))
    }

    // -------------------------------------------------------------------------
    // docker-run plugin (Docker daemon required)
    // -------------------------------------------------------------------------

    @Test
    fun `can run, status, and stop a container (kotlin dsl)`() {
        assumeDockerAvailable()
        buildFileKts.writeText(
            """
            plugins {
                id("com.corevaultsolutions.docker-run")
            }
            dockerRun {
                name = "bar-kts"
                image = "alpine:3.2"
                ports("8080")
                command("sleep", "1000")
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerRemoveContainer", "dockerRun", "dockerRunStatus", "dockerStop").build()
        val offline = gradleRunner("dockerRunStatus", "dockerRemoveContainer").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'bar-kts' is RUNNING."))
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerStop")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, offline.task(":dockerRunStatus")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, offline.task(":dockerRemoveContainer")?.outcome)
        assertTrue(offline.output.contains("Docker container 'bar-kts' is STOPPED."))
    }
}
