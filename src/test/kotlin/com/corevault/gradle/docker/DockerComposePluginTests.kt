package com.corevault.gradle.docker

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerComposePluginTests : AbstractPluginTest() {

    @Test
    fun `Generates docker-compose yml from template with version strings replaced`() {
        file("Dockerfile").writeText("Foo")
        file("docker-compose.yml.template").writeText(
            """
            service1:
              image: 'repository/service1:{{com.google.guava:guava}}'
            service2:
              image: 'repository/service2:{{org.slf4j:slf4j-api}}'
            current-service:
              image: '{{currentImageName}}'
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            repositories {
                mavenCentral()
            }
            dockerCompose {
                templateTokens(['currentImageName': 'snapshot.docker.registry/current-service:1.0.0-1-gabcabcd'])
            }
            dependencies {
                docker 'io.dropwizard:dropwizard-jackson:0.8.2'
                docker 'com.google.guava:guava:17.0'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("generateDockerCompose", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateDockerCompose")?.outcome)
        val dockerComposeText = file("docker-compose.yml").readText()
        assertTrue(dockerComposeText.contains("repository/service1:18.0"))
        assertTrue(dockerComposeText.contains("repository/service2:1.7.10"))
        assertTrue(dockerComposeText.contains("image: 'snapshot.docker.registry/current-service:1.0.0-1-gabcabcd'"))
    }

    @Test
    fun `Fails if docker-compose yml template has unmatched version tokens`() {
        file("Dockerfile").writeText("Foo")
        file("docker-compose.yml.template").writeText(
            """
            service1:
              image: 'repository/service1:{{foo:bar}}'
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                docker 'com.google.guava:guava:17.0'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("generateDockerCompose", "--stacktrace").buildAndFail()
        assertTrue(result.output.contains("Failed to resolve Docker dependencies declared in"))
        assertTrue(result.output.contains("{{foo:bar}}"))
    }

    @Test
    fun `docker-compose template and file can have custom locations`() {
        file("Dockerfile").writeText("Foo")
        file("templates/customTemplate.yml").writeText("nothing\n")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            repositories {
                mavenCentral()
            }
            dockerCompose {
                template 'templates/customTemplate.yml'
                dockerComposeFile 'compose-files/customDockerCompose.yml'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("generateDockerCompose", "--stacktrace").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateDockerCompose")?.outcome)
        assertTrue(file("compose-files/customDockerCompose.yml").exists())
    }

    @Test
    fun `Fails if template is configured but does not exist`() {
        file("Dockerfile").writeText("Foo")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            dockerCompose {
                template 'templates/customTemplate.yml'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("generateDockerCompose").buildAndFail()
        assertTrue(result.output.contains("Could not find specified template file"))
    }

    @Test
    fun `docker-compose is executed and fails on invalid file`() {
        file("docker-compose.yml").writeText("FOO")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerComposeUp", "--stacktrace").buildAndFail()
        // docker-compose v1 says "Top-level object must be a mapping";
        // docker compose v2 says "cannot unmarshal !!str 'FOO'" or similar yaml error
        assertTrue(
            result.output.contains("Top-level object must be a mapping") ||
                result.output.contains("unmarshal") ||
                result.output.contains("yaml"),
        )
    }

    @Test
    fun `docker-compose successfully creates docker container`() {
        file("docker-compose.yml").writeText(
            """
            version: "2"
            services:
              hello:
                container_name: "helloworld"
                image: "alpine"
                command: touch /test/foobarbaz
                volumes:
                  - ./:/test
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            """.trimIndent(),
        )
        gradleRunner("dockerComposeUp").build()
        assertTrue(file("foobarbaz").exists())
        execCond("docker", "stop", "helloworld")
        execCond("docker", "rm", "helloworld")
    }

    @Test
    fun `docker-compose successfully creates docker container from custom file`() {
        file("test-file.yml").writeText(
            """
            version: "2"
            services:
              hello:
                container_name: "helloworld2"
                image: "alpine"
                command: touch /test/qux
                volumes:
                  - ./:/test
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            dockerCompose {
                dockerComposeFile "test-file.yml"
            }
            """.trimIndent(),
        )
        gradleRunner("dockerComposeUp").build()
        assertTrue(file("qux").exists())
        execCond("docker", "stop", "helloworld2")
        execCond("docker", "rm", "helloworld2")
    }

    @Test
    fun `docker-compose stop successfully stops docker container`() {
        file("docker-compose.yml").writeText(
            """
            version: "2"
            services:
              hello:
                container_name: "unit-test-docker-compose-stop"
                image: "alpine"
                command: sh -c 'while sleep 3600; do :; done'
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-compose'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerComposeUp", "dockerComposeDown").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerComposeDown")?.outcome)
    }
}
