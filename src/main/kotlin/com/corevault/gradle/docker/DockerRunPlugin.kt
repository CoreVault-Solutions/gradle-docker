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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

class DockerRunPlugin : Plugin<Project> {

    private companion object {
        const val GROUP = "Docker Run"
    }

    override fun apply(project: Project) {
        val ext = project.extensions.create("dockerRun", DockerRunExtension::class.java)
        // Wired lazily; the extension is read when each task executes (or when the configuration cache
        // is stored), never eagerly during configuration of unrelated tasks like `help`/`tasks`.
        val containerName = project.provider { ext.name }
        val projectDir = project.layout.projectDirectory

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

        project.tasks.register("dockerRun", DockerRunTask::class.java) { t ->
            t.group = GROUP
            t.description = "Runs the specified container with port mappings"
            t.containerName.set(containerName)
            t.image.set(project.provider { ext.image })
            t.network.set(project.provider { ext.network })
            t.ports.set(project.provider { ext.ports })
            t.envVars.set(project.provider { ext.env })
            t.volumes.set(project.provider { ext.volumes.entries.associate { (k, v) -> k.toString() to v } })
            t.command.set(project.provider { ext.command })
            t.arguments.set(project.provider { ext.arguments })
            t.daemonize.set(project.provider { ext.daemonize })
            t.clean.set(project.provider { ext.clean })
            t.ignoreExitValue.set(project.provider { ext.ignoreExitValue })
            t.projectDirectory.set(projectDir)
            // Reporting status after a (non-removed) container starts is always safe; a --rm/clean
            // container is simply reported as stopped.
            t.finalizedBy(dockerRunStatus)
        }

        project.tasks.register("dockerStop", DockerContainerCommandTask::class.java) { t ->
            t.group = GROUP
            t.description = "Stops the named container if it is running"
            t.containerName.set(containerName)
            t.dockerCommand.set("stop")
        }

        project.tasks.register("dockerRemoveContainer", DockerContainerCommandTask::class.java) { t ->
            t.group = GROUP
            t.description = "Removes the persistent container associated with the Docker Run tasks"
            t.containerName.set(containerName)
            t.dockerCommand.set("rm")
        }
    }
}

private const val NAME_REQUIRED_MESSAGE = "dockerRun.name is required and must be non-empty."

private fun requireNonBlank(value: String?, message: String): String =
    value?.takeIf { it.isNotBlank() } ?: throw IllegalStateException(message)

/**
 * Runs `docker run` for the configured container. The command line is assembled and volume mounts are
 * validated at execution time (not during configuration), and the task holds only serializable inputs,
 * so it is both configuration-cache safe and free of configuration-time side effects.
 */
abstract class DockerRunTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {

    @get:Input @get:Optional abstract val containerName: Property<String>

    @get:Input @get:Optional abstract val image: Property<String>

    @get:Input @get:Optional abstract val network: Property<String>

    @get:Input abstract val ports: SetProperty<String>

    @get:Input abstract val envVars: MapProperty<String, String>

    @get:Input abstract val volumes: MapProperty<String, String>

    @get:Input abstract val command: ListProperty<String>

    @get:Input abstract val arguments: ListProperty<String>

    @get:Input abstract val daemonize: Property<Boolean>

    @get:Input abstract val clean: Property<Boolean>

    @get:Input abstract val ignoreExitValue: Property<Boolean>

    @get:Internal abstract val projectDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val name = requireNonBlank(containerName.orNull, NAME_REQUIRED_MESSAGE)
        val img = requireNonBlank(image.orNull, "dockerRun.image is required and must be non-empty.")

        val args = mutableListOf("docker", "run")
        if (daemonize.get()) args.add("-d")
        if (clean.get()) args.add("--rm")
        network.orNull?.let { args.addAll(listOf("--network", it)) }
        ports.get().forEach { args.addAll(listOf("-p", it)) }
        volumes.get().forEach { (host, container) ->
            val hostFile = File(host).let { if (it.isAbsolute) it else projectDirectory.get().asFile.resolve(host) }
            check(hostFile.exists()) {
                "Local folder $hostFile doesn't exist. Mounted volume will not be visible to container."
            }
            args.addAll(listOf("-v", "${hostFile.absolutePath}:$container"))
        }
        envVars.get().forEach { (k, v) -> args.addAll(listOf("-e", "$k=$v")) }
        args.addAll(listOf("--name", name))
        if (arguments.get().isNotEmpty()) args.addAll(arguments.get())
        args.add(img)
        if (command.get().isNotEmpty()) args.addAll(command.get())

        val output = ByteArrayOutputStream()
        execOperations.exec { spec ->
            spec.commandLine(args)
            spec.isIgnoreExitValue = ignoreExitValue.get()
            spec.standardOutput = output
            spec.errorOutput = output
        }
        val text = output.toString().trim()
        if (text.isNotEmpty()) logger.lifecycle(text)
    }
}

/** Runs a simple `docker <command> <container>` (e.g. stop/rm), ignoring the exit value. */
abstract class DockerContainerCommandTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Input @get:Optional abstract val containerName: Property<String>

    @get:Input abstract val dockerCommand: Property<String>

    @TaskAction
    fun run() {
        val name = requireNonBlank(containerName.orNull, NAME_REQUIRED_MESSAGE)
        execOperations.exec { spec ->
            spec.commandLine("docker", dockerCommand.get(), name)
            spec.isIgnoreExitValue = true
        }
    }
}

/**
 * Reports whether the configured container is running. Implemented as a custom task (rather than an
 * Exec task that captures a shared output stream) so it stays compatible with the configuration cache.
 */
abstract class DockerRunStatusTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {

    @get:Input @get:Optional abstract val containerName: Property<String>

    @TaskAction
    fun check() {
        val name = requireNonBlank(containerName.orNull, NAME_REQUIRED_MESSAGE)
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
abstract class DockerNetworkModeStatusTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Input @get:Optional abstract val containerName: Property<String>

    @get:Input @get:Optional abstract val configuredNetwork: Property<String>

    @TaskAction
    fun check() {
        val name = requireNonBlank(containerName.orNull, NAME_REQUIRED_MESSAGE)
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
