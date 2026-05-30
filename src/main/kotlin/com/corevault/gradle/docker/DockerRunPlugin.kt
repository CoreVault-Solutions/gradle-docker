package com.corevault.gradle.docker

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import java.io.ByteArrayOutputStream

class DockerRunPlugin : Plugin<Project> {

    private companion object {
        const val GROUP = "Docker Run"
    }

    override fun apply(project: Project) {
        val ext = project.extensions.create("dockerRun", DockerRunExtension::class.java)

        // Register tasks lazily inside afterEvaluate so ext properties (name, image, etc.) are resolved.
        project.afterEvaluate {
            // Resolve a non-null container name once. All docker-run tasks require it, and keeping it
            // non-null avoids passing a nullable String into Exec.commandLine(...).
            val containerName = ext.name?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("dockerRun.name is required and must be non-empty.")

            val statusOutput = ByteArrayOutputStream()
            val dockerRunStatus = project.tasks.register("dockerRunStatus", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Checks the run status of the container"
                t.standardOutput = statusOutput
                t.commandLine("docker", "inspect", "--format={{.State.Running}}", containerName)
            }
            dockerRunStatus.get().doLast {
                val running = statusOutput.toString().trim() == "true"
                println("Docker container '$containerName' is ${if (running) "RUNNING" else "STOPPED"}.")
            }

            val networkOutput = ByteArrayOutputStream()
            val dockerNetworkModeStatus = project.tasks.register("dockerNetworkModeStatus", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Checks the network configuration of the container"
                t.standardOutput = networkOutput
                t.commandLine("docker", "inspect", "--format={{.HostConfig.NetworkMode}}", containerName)
            }
            dockerNetworkModeStatus.get().doLast {
                println(networkModeMessage(ext, containerName, networkOutput.toString().trim()))
            }

            val runArgs = buildRunArgs(project, ext, containerName)
            val dockerRun = project.tasks.register("dockerRun", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Runs the specified container with port mappings"
                t.isIgnoreExitValue = ext.ignoreExitValue
                t.commandLine(runArgs)
            }
            if (!ext.clean) {
                dockerRun.get().finalizedBy(dockerRunStatus)
            }

            project.tasks.register("dockerStop", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Stops the named container if it is running"
                t.isIgnoreExitValue = true
                t.commandLine("docker", "stop", containerName)
            }

            project.tasks.register("dockerRemoveContainer", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Removes the persistent container associated with the Docker Run tasks"
                t.isIgnoreExitValue = true
                t.commandLine("docker", "rm", containerName)
            }
        }
    }

    private fun networkModeMessage(ext: DockerRunExtension, containerName: String, networkMode: String): String = when {
        networkMode == "default" ->
            "Docker container '$containerName' has default network configuration (bridge)."
        networkMode == ext.network ->
            "Docker container '$containerName' is configured to run with '${ext.network}' network mode."
        else ->
            "Docker container '$containerName' runs with '$networkMode' network mode " +
                "instead of the configured '${ext.network}'."
    }

    private fun buildRunArgs(project: Project, ext: DockerRunExtension, containerName: String): List<String> {
        val imageName = ext.image?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("dockerRun.image is required and must be non-empty.")

        val runArgs = mutableListOf("docker", "run")
        if (ext.daemonize) runArgs.add("-d")
        if (ext.clean) runArgs.add("--rm")
        if (ext.network != null) runArgs.addAll(listOf("--network", ext.network!!))
        for (port in ext.ports) {
            runArgs.add("-p")
            runArgs.add(port)
        }
        for ((key, value) in ext.volumes) {
            val localFile = project.file(key)
            if (!localFile.exists()) {
                project.logger.warn("ERROR: Local folder $localFile doesn't exist. Mounted volume will not be visible to container")
                throw IllegalStateException("Local folder $localFile doesn't exist.")
            }
            runArgs.add("-v")
            runArgs.add("${localFile.absolutePath}:$value")
        }
        runArgs.addAll(ext.env.flatMap { (k, v) -> listOf("-e", "$k=$v") })
        runArgs.add("--name")
        runArgs.add(containerName)
        if (ext.arguments.isNotEmpty()) runArgs.addAll(ext.arguments)
        runArgs.add(imageName)
        if (ext.command.isNotEmpty()) runArgs.addAll(ext.command)
        return runArgs
    }
}
