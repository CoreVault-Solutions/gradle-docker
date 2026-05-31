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

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.CopySpec
import java.io.File

open class DockerExtension(val project: Project) {

    companion object {
        private const val DEFAULT_DOCKERFILE_PATH = "Dockerfile"
    }

    var secrets: MutableList<String> = mutableListOf()

    var imageName: String? = null
        get() {
            val name = field
            check(!name.isNullOrEmpty()) { "imageName is a required docker configuration item." }
            return name
        }

    private var dockerfile: File? = null
    private var dockerComposeTemplate: String = "docker-compose.yml.template"
    private var dockerComposeFile: String = "docker-compose.yml"
    private val dependencies: MutableSet<Task> = linkedSetOf()

    /** Tags to apply to the image. The project version is always added on top — see [allTags]. */
    var tags: Set<String> = emptySet()

    var namedTags: HashMap<String, String> = HashMap()
        set(value) {
            field = HashMap(value)
        }
    var labels: HashMap<String, String> = HashMap()
    var buildArgs: Map<String, String> = emptyMap()
    var pull: Boolean = false
    var noCache: Boolean = false
    var network: String? = null
    var buildx: Boolean = false
    var platform: Set<String> = emptySet()
    var load: Boolean = false
    var push: Boolean = false
    var builder: String? = null

    var resolvedDockerfile: File? = null
        private set
    var resolvedDockerComposeTemplate: File? = null
        private set
    var resolvedDockerComposeFile: File? = null
        private set

    private val copySpec: CopySpec = project.copySpec()

    /**
     * The full set of tags to apply, always including the project version (matching the original
     * Palantir behavior, so a versioned project publishes a version-tagged image).
     */
    val allTags: Set<String>
        get() = tags + project.version.toString()

    fun setDockerfile(dockerfile: File) {
        check(dockerfile.exists()) { "Could not find specified Dockerfile: $dockerfile" }
        this.dockerfile = dockerfile
    }

    fun setDockerComposeTemplate(dockerComposeTemplate: String) {
        this.dockerComposeTemplate = dockerComposeTemplate
        check(project.file(dockerComposeTemplate).exists()) {
            "Could not find specified template file: ${project.file(dockerComposeTemplate)}"
        }
    }

    fun setDockerComposeFile(dockerComposeFile: String) {
        this.dockerComposeFile = dockerComposeFile
    }

    fun dependsOn(vararg args: Task) {
        this.dependencies.addAll(args)
    }

    fun getDependencies(): Set<Task> = dependencies.toSet()

    fun files(vararg files: Any): CopySpec = copySpec.from(*files)

    fun tag(taskName: String, tag: String) {
        if (namedTags.putIfAbsent(taskName, tag) != null) {
            project.logger.warn("Tag $taskName already exists.")
        }
    }

    fun getCopySpec(): CopySpec = copySpec

    fun resolvePathsAndValidate() {
        resolvedDockerfile = dockerfile ?: project.file(DEFAULT_DOCKERFILE_PATH)
        resolvedDockerComposeFile = project.file(dockerComposeFile)
        resolvedDockerComposeTemplate = project.file(dockerComposeTemplate)
    }
}
