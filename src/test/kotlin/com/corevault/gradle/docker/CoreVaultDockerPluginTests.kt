package com.corevault.gradle.docker

import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class CoreVaultDockerPluginTests : AbstractPluginTest() {

    // -------------------------------------------------------------------------
    // Unit test: computeName (no Docker required)
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "computeName({0}, {1}) == {2}")
    @MethodSource("computeNameCases")
    fun `computeName replaces name correctly`(name: String, tag: String, expected: String) {
        assertEquals(expected, CoreVaultDockerPlugin.computeName(name, tag))
    }

    companion object {
        @JvmStatic
        fun computeNameCases(): Stream<Arguments> =
            Stream.of(
                Arguments.of("v1", "latest", "v1:latest"),
                Arguments.of("v1:1", "latest", "v1:latest"),
                Arguments.of("host/v1", "latest", "host/v1:latest"),
                Arguments.of("host/v1:1", "latest", "host/v1:latest"),
                Arguments.of("host:port/v1", "latest", "host:port/v1:latest"),
                Arguments.of("host:port/v1:1", "latest", "host:port/v1:latest"),
                Arguments.of("v1", "name@latest", "v1:latest"),
                Arguments.of("v1:1", "name@latest", "v1:latest"),
                Arguments.of("host/v1", "name@latest", "host/v1:latest"),
                Arguments.of("host/v1:1", "name@latest", "host/v1:latest"),
                Arguments.of("host:port/v1", "name@latest", "host:port/v1:latest"),
                Arguments.of("host:port/v1:1", "name@latest", "host:port/v1:latest"),
                Arguments.of("v1", "name@v2:latest", "v2:latest"),
                Arguments.of("v1:1", "name@v2:latest", "v2:latest"),
                Arguments.of("host/v1", "name@v2:latest", "v2:latest"),
                Arguments.of("host/v1:1", "name@v2:latest", "v2:latest"),
                Arguments.of("host:port/v1", "name@v2:latest", "v2:latest"),
                Arguments.of("host:port/v1:1", "name@v2:latest", "v2:latest"),
                Arguments.of("v1", "name@host/v2", "host/v2"),
                Arguments.of("v1:1", "name@host/v2", "host/v2"),
                Arguments.of("host/v1", "name@host/v2", "host/v2"),
                Arguments.of("host/v1:1", "name@host/v2", "host/v2"),
                Arguments.of("host:port/v1", "name@host/v2", "host/v2"),
                Arguments.of("host:port/v1:1", "name@host/v2", "host/v2"),
                Arguments.of("v1", "name@host/v2:2", "host/v2:2"),
                Arguments.of("v1:1", "name@host/v2:2", "host/v2:2"),
                Arguments.of("host/v1", "name@host/v2:2", "host/v2:2"),
                Arguments.of("host/v1:1", "name@host/v2:2", "host/v2:2"),
                Arguments.of("host:port/v1", "name@host/v2:2", "host/v2:2"),
                Arguments.of("host:port/v1:1", "name@host/v2:2", "host/v2:2"),
                Arguments.of("v1", "name@host:port/v2:2", "host:port/v2:2"),
                Arguments.of("v1:1", "name@host:port/v2:2", "host:port/v2:2"),
                Arguments.of("host/v1", "name@host:port/v2:2", "host:port/v2:2"),
                Arguments.of("host/v1:1", "name@host:port/v2:2", "host:port/v2:2"),
                Arguments.of("host:port/v1", "name@host:port/v2:2", "host:port/v2:2"),
                Arguments.of("host:port/v1:1", "name@host:port/v2:2", "host:port/v2:2"),
            )
    }

    // -------------------------------------------------------------------------
    // Configuration-error tests (no Docker required)
    // -------------------------------------------------------------------------

    @Test
    fun `fail when missing docker configuration`() {
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").buildAndFail()
        assertTrue(result.output.contains("imageName is a required docker configuration item."))
    }

    @Test
    fun `fail with empty container name`() {
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
            }
            docker {
                imageName = ''
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").buildAndFail()
        assertTrue(result.output.contains("imageName is a required docker configuration item."))
    }

    @Test
    fun `fail with bad label key character`() {
        file("Dockerfile").writeText("FROM alpine:3.2\n")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
            }
            docker {
                imageName = 'test-bad-labels'
                labels['test_label'] = 'test_value'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").buildAndFail()
        assertTrue(
            result.output.contains(
                "Docker label 'test_label' contains illegal characters. " +
                    "Label keys must only contain lowercase alphanumeric, `.`, or `-` characters " +
                    "(must match ^[a-z0-9.-]*\$).",
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Task graph test (no Docker required)
    // -------------------------------------------------------------------------

    @Test
    fun `tag and push tasks created for each tag`() {
        val id = "id5"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\n")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
            }
            docker {
                imageName = '$id'
                tags 'latest', 'another', 'withTaskName@2.0', 'newImageName@${id}-new:latest'
                tag 'withTaskNameByTag', '${id}:new-latest'
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
        assertTrue(result.output.contains("dockerPushAnother"))
        assertTrue(result.output.contains("dockerPushWithTaskName"))
        assertTrue(result.output.contains("dockerPushNewImageName"))
        assertTrue(result.output.contains("dockerPushWithTaskNameByTag"))
    }

    @Test
    fun `can apply both docker and docker-compose plugins`() {
        file("Dockerfile").writeText("Foo")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
                id 'com.corevault.docker-compose'
            }
            docker {
                imageName = 'foo'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("tasks").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":tasks")?.outcome)
    }

    // -------------------------------------------------------------------------
    // Integration tests — require a Docker daemon
    // -------------------------------------------------------------------------

    @Test
    fun `check plugin creates a docker container with default configuration`() {
        val id = "id1"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker { imageName = '$id' }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertTrue(exec("docker", "inspect", "--format", "{{.Author}}", id).trim() == id)
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `check plugin creates a docker container with non-standard Dockerfile name`() {
        val id = "id2"
        file("foo").writeText("FROM alpine:3.2\nMAINTAINER $id\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                dockerfile project.file("foo")
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
    fun `check files are correctly added to docker context`() {
        val id = "id3"
        val filename = "foo.txt"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\nADD $filename /tmp/\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                files "$filename"
            }
            """.trimIndent(),
        )
        File(projectDir, filename).createNewFile()
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `check multiarch`() {
        val id = "id4"
        val filename = "foo.txt"
        file("Dockerfile").writeText("FROM alpine\nMAINTAINER $id\nADD $filename /tmp/\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                files "$filename"
                buildx true
                load true
                platform 'linux/arm64'
            }
            """.trimIndent(),
        )
        File(projectDir, filename).createNewFile()
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `does not throw if name is configured after evaluation phase`() {
        val id = "id6"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                tags 'latest', 'another', 'withTaskName@2.0', 'newImageName@${id}-new:latest'
                tag 'withTaskNameByTag', '${id}:new-latest'
            }
            afterEvaluate {
                docker.imageName = '$id'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerTag").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerTag")?.outcome)
        execCond("docker", "rmi", "-f", id)
        execCond("docker", "rmi", "-f", "$id:another")
        execCond("docker", "rmi", "-f", "$id:latest")
        execCond("docker", "rmi", "-f", "$id:2.0")
        execCond("docker", "rmi", "-f", "$id-new:latest")
        execCond("docker", "rmi", "-f", "$id:new-latest")
    }

    @Test
    fun `running tag task creates images with specified tags`() {
        val id = "id6"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = 'fake-service-name'
                tags 'latest', 'another', 'withTaskName@2.0', 'newImageName@${id}-new:latest'
                tag 'withTaskNameByTag', '${id}:new-latest'
            }
            afterEvaluate {
                docker.imageName = '$id'
            }
            task printInfo {
                doLast {
                    println "LATEST: ${'$'}{tasks.dockerTagLatest.commandLine}"
                    println "ANOTHER: ${'$'}{tasks.dockerTagAnother.commandLine}"
                    println "WITH_TASK_NAME: ${'$'}{tasks.dockerTagWithTaskName.commandLine}"
                    println "NEW_IMAGE_NAME: ${'$'}{tasks.dockerTagNewImageName.commandLine}"
                    println "WITH_TASK_NAME_BY_TAG: ${'$'}{tasks.dockerTagWithTaskNameByTag.commandLine}"
                }
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerTag", "printInfo").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerTag")?.outcome)
        execCond("docker", "rmi", "-f", id)
        execCond("docker", "rmi", "-f", "$id:latest")
        execCond("docker", "rmi", "-f", "$id:another")
        execCond("docker", "rmi", "-f", "$id:2.0")
        execCond("docker", "rmi", "-f", "$id-new:latest")
        execCond("docker", "rmi", "-f", "$id:new-latest")
    }

    @Test
    fun `build args are correctly processed`() {
        val id = "id7"
        file("Dockerfile").writeText(
            """
            FROM alpine:3.2
            ARG BUILD_ARG_NO_DEFAULT
            ARG BUILD_ARG_WITH_DEFAULT=defaultBuildArg
            ENV ENV_BUILD_ARG_NO_DEFAULT ${'$'}BUILD_ARG_NO_DEFAULT
            ENV ENV_BUILD_ARG_WITH_DEFAULT ${'$'}BUILD_ARG_WITH_DEFAULT
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                buildArgs([BUILD_ARG_NO_DEFAULT: 'gradleBuildArg', BUILD_ARG_WITH_DEFAULT: 'gradleOverrideBuildArg'])
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertTrue(exec("docker", "inspect", "--format", "{{.Config.Env}}", id).contains("ENV_BUILD_ARG_NO_DEFAULT=gradleBuildArg"))
        assertTrue(exec("docker", "inspect", "--format", "{{.Config.Env}}", id).contains("BUILD_ARG_WITH_DEFAULT=gradleOverrideBuildArg"))
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `rebuilding an image does it from scratch when noCache parameter is set`() {
        val id = "id66"
        val filename = "bar.txt"
        file("Dockerfile").writeText("FROM alpine:3.2\nADD $filename /tmp/\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                files "$filename"
                noCache = true
            }
            """.trimIndent(),
        )
        createFile(filename)
        val result1 = gradleRunner("--info", "docker").build()
        val imageId1 = exec("docker", "inspect", "--format={{.Id}}", id)
        val result2 = gradleRunner("--info", "docker").build()
        val imageId2 = exec("docker", "inspect", "--format={{.Id}}", id)
        assertEquals(TaskOutcome.SUCCESS, result1.task(":docker")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result2.task(":docker")?.outcome)
        assertTrue(imageId1 != imageId2)
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `base image is pulled when pull parameter is set`() {
        val id = "id8"
        file("Dockerfile").writeText("FROM alpine:3.2\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                pull true
            }
            """.trimIndent(),
        )
        execCond("docker", "pull", "alpine:3.2")
        val result = gradleRunner("-i", "docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertTrue(result.output.contains("load metadata for docker.io/library/alpine"))
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `can build docker with network mode configured`() {
        val id = "id11"
        file("Dockerfile").writeText("FROM alpine:3.2\nRUN curl localhost:404\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                network 'foobar'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("-i", "docker").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":docker")?.outcome)
        assertTrue(
            result.output.contains("network foobar not found") ||
                result.output.contains("No such network: foobar") ||
                result.output.contains("network mode \"foobar\" not supported by buildkit"),
        )
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `can add files from project directory to build context`() {
        val id = "id9"
        val filename = "bar.txt"
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\nADD $filename /tmp/\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                files "bar.txt"
            }
            """.trimIndent(),
        )
        createFile(filename)
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `when adding a project-dir file and a Tar file they both end up unzipped in docker image`() {
        val id = "id10"
        createFile("from_project")
        createFile("from_tgz")
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER id\nADD foo.tgz /tmp/\nADD from_project /tmp/\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            task myTgz(type: Tar) {
                destinationDirectory = project.buildDir
                archiveBaseName = 'foo'
                archiveExtension = 'tgz'
                compression = Compression.GZIP
                into('.') {
                    from 'from_tgz'
                }
            }
            docker {
                imageName = '$id'
                files tasks.myTgz.outputs, 'from_project'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":myTgz")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `check labels are correctly applied to image`() {
        val id = "id10"
        file("Dockerfile").writeText("FROM alpine:3.2\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                labels['test-label'] = 'test-value'
                labels['another.label'] = 'another.value'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertTrue(exec("docker", "inspect", "--format", "{{.Config.Labels}}", id).contains("test-label"))
        execCond("docker", "rmi", "-f", id)
    }

    @Test
    fun `can add entire directories via copyspec`() {
        val id = "id1"
        createFile("myDir/bar")
        file("Dockerfile").writeText("FROM alpine:3.2\nMAINTAINER $id\nADD myDir /myDir/\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevault.docker' }
            docker {
                imageName = '$id'
                copySpec.from("myDir").into("myDir")
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerPrepare")?.outcome)
        assertTrue(file("build/docker/myDir/bar").exists())
        execCond("docker", "rmi", "-f", id)
    }
}
