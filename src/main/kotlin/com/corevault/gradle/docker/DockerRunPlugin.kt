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
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

class DockerRunPlugin : Plugin<Project> {

    private companion object {
        const val GROUP = "Docker Run"
    }

    override fun apply(project: Project) {
        val ext = project.extensions.create("dockerRun", DockerRunExtension::class.java)

        // Resolved lazily; throws only when a task that needs the name is actually executed/configured.
        val containerName = project.provider {
            ext.name?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("dockerRun.name is required and must be non-empty.")
        }

        // Register every task eagerly at apply() time so consumers can wire them with
        // tasks.named("dockerRun") { ... } before the project is evaluated.
        val dockerRunStatus = project.tasks.register("dockerRunStatus", DockerRunStatusTask::class.java) { t ->
            t.group = GROUP
            t.description = "Checks the run status of the container"
            t.containerName.set(containerName)
        }

        project.tasks.register("dockerNetworkModeStatus", DockerNetworkModeStatusTask::class.java) { t ->
            t.group = GROUP
            t.description = "Checks the network configuration of the container"
            t.containerName.set(containerName)
            t.configuredNetwork.set(project.provider { ext.network })
        }

        val dockerRun = project.tasks.register("dockerRun", Exec::class.java) { t ->
            t.group = GROUP
            t.description = "Runs the specified container with port mappings"
        }

        val dockerStop = project.tasks.register("dockerStop", Exec::class.java) { t ->
            t.group = GROUP
            t.description = "Stops the named container if it is running"
            t.isIgnoreExitValue = true
        }

        val dockerRemoveContainer = project.tasks.register("dockerRemoveContainer", Exec::class.java) { t ->
            t.group = GROUP
            t.description = "Removes the persistent container associated with the Docker Run tasks"
            t.isIgnoreExitValue = true
        }

        // Command lines depend on the fully-evaluated extension, so resolve them here. Values are
        // captured as plain serializable types (List<String>/Boolean) to stay configuration-cache safe.
        project.afterEvaluate {
            val name = containerName.get()
            val runArgs = buildRunArgs(project, ext, name)
            val ignoreExit = ext.ignoreExitValue
            val finalizeWithStatus = !ext.clean

            dockerRun.configure { t ->
                t.isIgnoreExitValue = ignoreExit
                t.commandLine(runArgs)
                if (finalizeWithStatus) {
                    t.finalizedBy(dockerRunStatus)
                }
            }
            dockerStop.configure { t -> t.commandLine("docker", "stop", name) }
            dockerRemoveContainer.configure { t -> t.commandLine("docker", "rm", name) }
        }
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
            check(localFile.exists()) {
                "Local folder $localFile doesn't exist. Mounted volume will not be visible to container."
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

/**
 * Reports whether the configured container is running. Implemented as a custom task (rather than an
 * Exec task that captures a shared output stream) so it stays compatible with the configuration cache.
 */
abstract class DockerRunStatusTask : DefaultTask() {

    @get:Input
    abstract val containerName: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun check() {
        val name = containerName.get()
        val output = ByteArrayOutputStream()
        execOperations.exec { spec ->
            spec.commandLine("docker", "inspect", "--format={{.State.Running}}", name)
            spec.isIgnoreExitValue = true
            spec.standardOutput = output
            spec.errorOutput = ByteArrayOutputStream()
        }
        val running = output.toString().trim() == "true"
        logger.lifecycle("Docker container '$name' is ${if (running) "RUNNING" else "STOPPED"}.")
    }
}

/** Reports the network mode the configured container is running with. Configuration-cache safe. */
abstract class DockerNetworkModeStatusTask : DefaultTask() {

    @get:Input
    abstract val containerName: Property<String>

    @get:Input
    @get:Optional
    abstract val configuredNetwork: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun check() {
        val name = containerName.get()
        val output = ByteArrayOutputStream()
        execOperations.exec { spec ->
            spec.commandLine("docker", "inspect", "--format={{.HostConfig.NetworkMode}}", name)
            spec.isIgnoreExitValue = true
            spec.standardOutput = output
            spec.errorOutput = ByteArrayOutputStream()
        }
        val mode = output.toString().trim()
        val configured = configuredNetwork.orNull
        val message = when {
            mode == "default" ->
                "Docker container '$name' has default network configuration (bridge)."
            mode == configured ->
                "Docker container '$name' is configured to run with '$configured' network mode."
            else ->
                "Docker container '$name' runs with '$mode' network mode instead of the configured '$configured'."
        }
        logger.lifecycle(message)
    }
}
