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
            val statusOutput = ByteArrayOutputStream()
            val dockerRunStatus = project.tasks.register("dockerRunStatus", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Checks the run status of the container"
                t.standardOutput = statusOutput
                t.commandLine("docker", "inspect", "--format={{.State.Running}}", ext.name)
            }
            dockerRunStatus.get().doLast {
                if (statusOutput.toString().trim() != "true") {
                    println("Docker container '${ext.name}' is STOPPED.")
                } else {
                    println("Docker container '${ext.name}' is RUNNING.")
                }
            }

            val networkOutput = ByteArrayOutputStream()
            val dockerNetworkModeStatus = project.tasks.register("dockerNetworkModeStatus", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Checks the network configuration of the container"
                t.standardOutput = networkOutput
                t.commandLine("docker", "inspect", "--format={{.HostConfig.NetworkMode}}", ext.name)
            }
            dockerNetworkModeStatus.get().doLast {
                val networkMode = networkOutput.toString().trim()
                when {
                    networkMode == "default" ->
                        println("Docker container '${ext.name}' has default network configuration (bridge).")
                    networkMode == ext.network ->
                        println("Docker container '${ext.name}' is configured to run with '${ext.network}' network mode.")
                    else ->
                        println(
                            "Docker container '${ext.name}' runs with '$networkMode' network mode " +
                                "instead of the configured '${ext.network}'.",
                        )
                }
            }

            val runArgs = mutableListOf("docker", "run")
            if (ext.daemonize) runArgs.add("-d")
            if (ext.clean) {
                runArgs.add("--rm")
            }
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
            val containerName = ext.name?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("dockerRun.name is required and must be non-empty.")
            val imageName = ext.image?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("dockerRun.image is required and must be non-empty.")

            runArgs.addAll(ext.env.flatMap { (k, v) -> listOf("-e", "$k=$v") })
            runArgs.add("--name")
            runArgs.add(containerName)
            if (ext.arguments.isNotEmpty()) runArgs.addAll(ext.arguments)
            runArgs.add(imageName)
            if (ext.command.isNotEmpty()) runArgs.addAll(ext.command)

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
                t.commandLine("docker", "stop", ext.name)
            }

            project.tasks.register("dockerRemoveContainer", Exec::class.java) { t ->
                t.group = GROUP
                t.description = "Removes the persistent container associated with the Docker Run tasks"
                t.isIgnoreExitValue = true
                t.commandLine("docker", "rm", ext.name)
            }
        }
    }
}
