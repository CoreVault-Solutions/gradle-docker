package com.corevault.gradle.docker

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DockerRunPluginTests : AbstractPluginTest() {

    private val sep = System.lineSeparator()

    @Test
    fun `can run, status, and stop a container made by the docker plugin`() {
        file("Dockerfile").writeText("FROM alpine:3.2\nCMD sleep 1000\n")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
                id 'com.corevault.docker-run'
            }
            docker {
                imageName = 'foo-image:latest'
            }
            dockerRun {
                name 'foo'
                image 'foo-image:latest'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker", "dockerRemoveContainer", "dockerRun", "dockerRunStatus", "dockerStop").build()
        val offline = gradleRunner("dockerRunStatus", "dockerRemoveContainer").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":docker")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'foo' is RUNNING."))
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerStop")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, offline.task(":dockerRunStatus")?.outcome)
        assertTrue(offline.output.contains("Docker container 'foo' is STOPPED."))
        execCond("docker rmi -f foo-image")
    }

    @Test
    fun `can run, status, and stop a container`() {
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-run'
            }
            dockerRun {
                name 'bar'
                image 'alpine:3.2'
                ports '8080'
                command 'sleep', '1000'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerRemoveContainer", "dockerRun", "dockerRunStatus", "dockerStop").build()
        val offline = gradleRunner("dockerRunStatus", "dockerRemoveContainer").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'bar' is RUNNING."))
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerStop")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, offline.task(":dockerRunStatus")?.outcome)
        assertTrue(offline.output.contains("Docker container 'bar' is STOPPED."))
    }

    @Test
    fun `can run container with configured network`() {
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-run'
            }
            dockerRun {
                name 'bar-hostnetwork'
                image 'alpine:3.2'
                network 'host'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerRemoveContainer", "dockerRun", "dockerNetworkModeStatus").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertTrue(result.output.contains("Docker container 'bar-hostnetwork' is configured to run with 'host' network mode."))
    }

    @Test
    fun `can optionally not daemonize`() {
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-run'
            }
            dockerRun {
                name 'bar-nodaemonize'
                image 'alpine:3.2'
                ports '8080'
                command 'echo', '"hello world"'
                daemonize = false
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerRemoveContainer", "dockerRun", "dockerRunStatus").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'bar-nodaemonize' is STOPPED."))
    }

    @Test
    fun `can optionally ignore exit code`() {
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker-run'
            }
            dockerRun {
                name 'bar-ignore-exit-code'
                image 'alpine:3.2'
                ports '8080'
                command 'exit', '100'
                ignoreExitValue = true
            }
            """.trimIndent(),
        )
        val result = gradleRunner("dockerRun").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
    }

    @Test
    fun `can set additional arguments`() {
        file("Dockerfile").writeText("FROM alpine:3.2\nRUN mkdir /test\n")
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
                id 'com.corevault.docker-run'
            }
            docker {
                imageName = 'foo-image:latest'
            }
            dockerRun {
                name 'foo'
                image 'foo-image:latest'
                arguments '-w=/test'
                daemonize = false
                command 'pwd'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker", "dockerRemoveContainer", "dockerRun", "dockerRunStatus").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertTrue(result.output.contains("/test"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'foo' is STOPPED."))
    }

    @Test
    fun `can mount volumes`() {
        if (isCi()) return

        val testFolder = directory("test")
        file("Dockerfile").writeText(
            """
            FROM alpine:3.2
            RUN mkdir /test
            VOLUME /test
            CMD cat /test/testfile
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
                id 'com.corevault.docker-run'
            }
            docker {
                imageName = 'foo-image:latest'
            }
            dockerRun {
                name 'foo'
                image 'foo-image:latest'
                volumes "test": "/test"
                daemonize = false
            }
            """.trimIndent(),
        )
        File(testFolder, "testfile").writeText("HELLO WORLD$sep")
        val result = gradleRunner("docker", "dockerRemoveContainer", "dockerRun", "dockerRunStatus").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertTrue(result.output.contains("HELLO WORLD"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'foo' is STOPPED."))
    }

    @Test
    fun `can mount volumes specified with an absolute path`() {
        if (isCi()) return

        val testFolder = directory("test")
        file("Dockerfile").writeText(
            """
            FROM alpine:3.2
            RUN mkdir /test
            VOLUME /test
            CMD cat /test/testfile
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
                id 'com.corevault.docker-run'
            }
            docker {
                imageName = 'foo-image:latest'
            }
            dockerRun {
                name 'foo'
                image 'foo-image:latest'
                volumes "${escapePath(testFolder.absolutePath)}": "/test"
                daemonize = false
            }
            """.trimIndent(),
        )
        File(testFolder, "testfile").writeText("HELLO WORLD$sep")
        val result = gradleRunner("docker", "dockerRemoveContainer", "dockerRun", "dockerRunStatus").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertTrue(result.output.contains("HELLO WORLD"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'foo' is STOPPED."))
    }

    @Test
    fun `can run with environment variables`() {
        file("Dockerfile").writeText(
            """
            FROM alpine:3.2
            RUN mkdir /test
            VOLUME /test
            ENV MYVAR1 QUUW
            ENV MYVAR2 QUUX
            ENV MYVAR3 QUUY
            ENV MYVAR4 QUUZ
            CMD echo "${'$'}MYVAR1 = ${'$'}MYVAR2 = ${'$'}MYVAR3 = ${'$'}MYVAR4"
            """.trimIndent(),
        )
        buildFile.writeText(
            """
            plugins {
                id 'com.corevault.docker'
                id 'com.corevault.docker-run'
            }
            docker {
                imageName = 'foo-image:latest'
            }
            dockerRun {
                name 'foo-envvars'
                image 'foo-image:latest'
                env 'MYVAR1': 'FOO', 'MYVAR2': 'BAR', 'MYVAR4': 'ZIP'
                daemonize = false
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker", "dockerRemoveContainer", "dockerRun", "dockerRunStatus").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRemoveContainer")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRun")?.outcome)
        assertTrue(result.output.contains("FOO = BAR = QUUY = ZIP"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":dockerRunStatus")?.outcome)
        assertTrue(result.output.contains("Docker container 'foo-envvars' is STOPPED."))
    }

    private fun isCi(): Boolean = System.getenv("CI") == "true"
}
