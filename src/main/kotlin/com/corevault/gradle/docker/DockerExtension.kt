package com.corevault.gradle.docker

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
    private var dependencies: Set<Task> = emptySet()

    /** Tags to apply to the image. A 'latest' tag is always added on top — see [allTags]. */
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

    /** The full set of tags to apply, always including 'latest'. */
    val allTags: Set<String>
        get() = tags + "latest"

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
        this.dependencies = args.toSet()
    }

    fun getDependencies(): Set<Task> = dependencies

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
