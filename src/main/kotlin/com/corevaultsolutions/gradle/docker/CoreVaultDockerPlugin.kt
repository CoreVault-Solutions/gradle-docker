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

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.attributes.AttributesFactory
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import java.util.regex.Pattern
import javax.inject.Inject

class CoreVaultDockerPlugin @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val attributesFactory: AttributesFactory,
) : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("docker", DockerExtension::class.java, project)
        if (project.configurations.findByName("docker") == null) {
            project.configurations.create("docker")
        }

        val clean = project.tasks.register("dockerClean", Delete::class.java) { t ->
            t.group = "Docker"
            t.description = "Cleans Docker build directory."
        }

        val prepare = project.tasks.register("dockerPrepare", Copy::class.java) { t ->
            t.group = "Docker"
            t.description = "Prepares Docker build directory."
            t.dependsOn(clean)
        }

        val execBuild = project.tasks.register("docker", Exec::class.java) { t ->
            t.group = "Docker"
            t.description = "Builds Docker image."
            t.dependsOn(prepare)
        }

        val tag = project.tasks.register("dockerTag") { t ->
            t.group = "Docker"
            t.description = "Applies all tags to the Docker image."
            t.dependsOn(execBuild)
        }

        val pushAllTags = project.tasks.register("dockerTagsPush") { t ->
            t.group = "Docker"
            t.description = "Pushes all tagged Docker images to configured Docker Hub."
        }

        project.tasks.register("dockerPush") { t ->
            t.group = "Docker"
            t.description = "Pushes named Docker image to configured Docker Hub."
            t.dependsOn(pushAllTags)
        }

        val dockerfileZip = project.tasks.register("dockerfileZip", Zip::class.java) { t ->
            t.group = "Docker"
            t.description = "Bundles the configured Dockerfile in a zip file"
        }

        val dockerConfiguration = project.configurations.named("docker")
        val dockerArtifact = project.artifacts.add("docker", dockerfileZip)
        project.components.add(
            DockerComponent(
                dockerArtifact,
                dockerConfiguration.get().allDependencies,
                objectFactory,
                attributesFactory,
            ),
        )

        project.afterEvaluate {
            ext.resolvePathsAndValidate()
            val dockerDirProvider = project.layout.buildDirectory.dir("docker")

            val cleanTask = clean.get()
            cleanTask.setDelete(dockerDirProvider)

            val prepareTask = prepare.get()
            prepareTask.with(ext.copySpec)
            val dockerfileName = ext.resolvedDockerfile!!.name
            prepareTask.from(ext.resolvedDockerfile).rename { fileName: String ->
                fileName.replace(dockerfileName, "Dockerfile")
            }
            prepareTask.into(dockerDirProvider.get())

            val dockerDependencies = ext.getDependencies()
            val execTask = execBuild.get()
            execTask.setWorkingDir(dockerDirProvider.get())
            // Resolve the command line at configuration time (not in a doFirst). The task then holds
            // only serializable state, keeping it compatible with the Gradle configuration cache.
            execTask.commandLine(buildCommandLine(ext))
            execTask.dependsOn(dockerDependencies)
            execTask.logging.captureStandardOutput(LogLevel.INFO)
            execTask.logging.captureStandardError(LogLevel.ERROR)

            val imageName = ext.imageName!!

            // Build tag map: taskName -> (displayName, finalTag). Everything is resolved here, at
            // configuration time, so the tag/push tasks capture only plain strings.
            val tags = mutableMapOf<String, Pair<String, String>>()
            ext.namedTags.forEach { (taskName, tagName) ->
                val normalizedTaskName = generateTagTaskName(taskName)
                require(!tags.containsKey(normalizedTaskName)) {
                    "Task name '$normalizedTaskName' (from named tag '$taskName') already exists."
                }
                // For named tags the supplied value is already the fully-qualified tag.
                tags[normalizedTaskName] = Pair(tagName, tagName)
            }
            if (ext.allTags.isNotEmpty()) {
                ext.allTags.forEach { unresolvedTagName ->
                    val taskName = generateTagTaskName(unresolvedTagName)
                    require(!tags.containsKey(taskName)) { "Task name '$taskName' already exists." }
                    tags[taskName] = Pair(unresolvedTagName, computeName(imageName, unresolvedTagName))
                }
            }

            tags.forEach { (taskName, tagInfo) ->
                val (displayName, finalTag) = tagInfo

                val tagSubTask = project.tasks.register("dockerTag$taskName", Exec::class.java) { t ->
                    t.group = "Docker"
                    t.description = "Tags Docker image with tag '$displayName'"
                    t.setWorkingDir(dockerDirProvider.get())
                    t.commandLine("docker", "tag", imageName, finalTag)
                    t.dependsOn(execBuild)
                }
                tag.get().dependsOn(tagSubTask)

                val pushSubTask = project.tasks.register("dockerPush$taskName", Exec::class.java) { t ->
                    t.group = "Docker"
                    t.description = "Pushes the Docker image with tag '$displayName'"
                    t.setWorkingDir(dockerDirProvider.get())
                    val metadataFile = dockerDirProvider.get().file("metadata-$taskName.json").asFile
                    if (ext.buildx) {
                        t.commandLine(
                            buildCommandLine(
                                ext,
                                imageName = finalTag,
                                extraArgs = listOf("--metadata-file", metadataFile.name),
                                forceBuildxPush = true,
                            ),
                        )
                        t.dependsOn(prepare)
                    } else {
                        t.commandLine("docker", "push", finalTag)
                        t.dependsOn(tagSubTask)
                        t.doLast {
                            val process = ProcessBuilder(
                                "docker",
                                "inspect",
                                "--format",
                                "{{index .RepoDigests 0}}",
                                finalTag,
                            ).redirectErrorStream(true).start()
                            val output = process.inputStream.bufferedReader().readText().trim()
                            val exitCode = process.waitFor()
                            val digest =
                                if (exitCode == 0) {
                                    output
                                } else {
                                    t.logger.warn("Failed to extract digest for $finalTag: $output")
                                    ""
                                }
                            metadataFile.parentFile.mkdirs()
                            metadataFile.writeText(
                                """{"containerimage.digest":"${jsonEscape(digest)}","image.name":"${jsonEscape(finalTag)}"}""",
                            )
                        }
                    }
                }
                pushAllTags.get().dependsOn(pushSubTask)
            }

            dockerfileZip.get().from(ext.resolvedDockerfile)
        }
    }

    companion object {
        @Suppress("unused")
        private val log: Logger = Logging.getLogger(CoreVaultDockerPlugin::class.java)
        private val LABEL_KEY_PATTERN: Pattern = Pattern.compile("^[a-z0-9.-]+$")

        private fun buildCommandLine(
            ext: DockerExtension,
            imageName: String? = ext.imageName,
            extraArgs: List<String> = emptyList(),
            forceBuildxPush: Boolean = false,
        ): List<String> {
            if ((ext.sbom || ext.provenanceMode != null) && !ext.buildx) {
                throw GradleException(
                    "SBOM and provenance attestations require buildx to be enabled. Set buildx = true in docker { }.",
                )
            }
            val cmdList = mutableListOf("docker")
            if (ext.buildx) {
                appendBuildxArgs(cmdList, ext, forceBuildxPush)
            } else {
                cmdList.add("build")
            }
            if (ext.noCache) cmdList.add("--no-cache")
            ext.target?.takeIf { it.isNotBlank() }?.let { cmdList.addAll(listOf("--target", it)) }
            if (ext.network != null) cmdList.addAll(listOf("--network", ext.network!!))
            for ((key, value) in ext.buildArgs) {
                cmdList.addAll(listOf("--build-arg", "$key=$value"))
            }
            for (secret in ext.secrets) {
                cmdList.addAll(listOf("--secret", secret))
            }
            appendLabelArgs(cmdList, ext)
            if (ext.pull) cmdList.add("--pull")
            imageName?.let { cmdList.addAll(listOf("-t", it)) }
            cmdList.addAll(extraArgs)
            cmdList.add(".")
            return cmdList
        }

        private fun appendBuildxArgs(
            cmdList: MutableList<String>,
            ext: DockerExtension,
            forceBuildxPush: Boolean,
        ) {
            cmdList.addAll(listOf("buildx", "build"))
            if (ext.platform.isNotEmpty()) {
                cmdList.addAll(listOf("--platform", ext.platform.joinToString(",")))
            }
            if (ext.load && !forceBuildxPush) cmdList.add("--load")
            if (ext.push || forceBuildxPush) {
                cmdList.add("--push")
                if (ext.load && !forceBuildxPush) {
                    throw GradleException("Cannot combine 'push' and 'load' options.")
                }
            }
            if (ext.builder != null) {
                cmdList.addAll(listOf("--builder", ext.builder!!))
            }
            if (ext.sbom) {
                val attestType =
                    if (ext.sbomGenerator.isNullOrBlank()) {
                        "type=sbom"
                    } else {
                        "type=sbom,generator=${ext.sbomGenerator}"
                    }
                cmdList.addAll(listOf("--attest", attestType))
            }
            ext.provenanceMode?.let { mode ->
                cmdList.addAll(listOf("--attest", "type=provenance,mode=$mode"))
            }
        }

        private fun appendLabelArgs(cmdList: MutableList<String>, ext: DockerExtension) {
            for ((key, value) in ext.labels) {
                if (!LABEL_KEY_PATTERN.matcher(key).matches()) {
                    throw GradleException(
                        "Docker label '$key' contains illegal characters. " +
                            "Label keys must only contain lowercase alphanumeric, `.`, or `-` characters " +
                            "(must match ${LABEL_KEY_PATTERN.pattern()}).",
                    )
                }
                cmdList.addAll(listOf("--label", "$key=$value"))
            }
        }

        internal fun computeName(name: String, tag: String): String {
            val firstAt = tag.indexOf("@")
            val tagValue = if (firstAt > 0) tag.substring(firstAt + 1) else tag
            if (tagValue.isBlank()) {
                throw GradleException("Docker tag '$tag' must not be empty.")
            }
            return if (tagValue.contains(":") || tagValue.contains("/")) {
                tagValue
            } else {
                val lastColon = name.lastIndexOf(':')
                val lastSlash = name.lastIndexOf('/')
                val endIndex = if (lastColon > lastSlash) lastColon else name.length
                name.substring(0, endIndex) + ":" + tagValue
            }
        }

        internal fun generateTagTaskName(name: String): String {
            if (name.isBlank()) {
                throw GradleException("Docker tag must not be empty.")
            }
            val firstAt = name.indexOf("@")
            val tagTaskName =
                when {
                    firstAt > 0 -> name.substring(0, firstAt)
                    firstAt == 0 -> throw GradleException("Task name of docker tag '$name' must not be empty.")
                    name.contains(":") || name.contains("/") ->
                        throw GradleException("Docker tag '$name' must have a task name.")
                    else -> name
                }
            if (tagTaskName.isBlank()) {
                throw GradleException("Task name of docker tag '$name' must not be empty.")
            }
            return tagTaskName.replaceFirstChar { it.uppercase() }
        }

        private fun jsonEscape(value: String): String =
            buildString {
                value.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else ->
                            if (char < ' ') {
                                append("\\u")
                                append(char.code.toString(16).padStart(4, '0'))
                            } else {
                                append(char)
                            }
                    }
                }
            }
    }
}
