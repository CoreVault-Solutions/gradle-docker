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
    private var tags: Set<String> = emptySet()
    var namedTags: HashMap<String, String> = HashMap()
        set(value) {
            field = HashMap(value)
        }
    var labels: HashMap<String, String> = HashMap()
    private var buildArgs: Map<String, String> = emptyMap()
    private var pull: Boolean = false
    var noCache: Boolean = false
    private var network: String? = null
    private var buildx: Boolean = false
    private var platform: Set<String> = emptySet()
    private var load: Boolean = false
    private var push: Boolean = false
    private var builder: String? = null

    private var resolvedDockerfile: File? = null
    private var resolvedDockerComposeTemplate: File? = null
    private var resolvedDockerComposeFile: File? = null

    private val copySpec: CopySpec = project.copySpec()

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

    fun getTags(): Set<String> {
        val result = HashSet(this.tags)
        result.add("latest")
        return result.toSet()
    }

    @Deprecated(
        "Use tag(taskName, tag) for named tags or configure namedTags property directly",
        ReplaceWith("tag(taskName, tag)"),
    )
    fun tags(vararg args: String) {
        this.tags = args.toSet()
    }

    fun tag(taskName: String, tag: String) {
        if (namedTags.putIfAbsent(taskName, tag) != null) {
            project.logger.warn("Tag $taskName already exists.")
        }
    }

    fun getResolvedDockerfile(): File? = resolvedDockerfile

    fun getResolvedDockerComposeTemplate(): File? = resolvedDockerComposeTemplate

    fun getResolvedDockerComposeFile(): File? = resolvedDockerComposeFile

    fun getCopySpec(): CopySpec = copySpec

    fun resolvePathsAndValidate() {
        resolvedDockerfile = dockerfile ?: project.file(DEFAULT_DOCKERFILE_PATH)
        resolvedDockerComposeFile = project.file(dockerComposeFile)
        resolvedDockerComposeTemplate = project.file(dockerComposeTemplate)
    }

    fun getBuildArgs(): Map<String, String> = buildArgs

    fun getNetwork(): String? = network

    fun setNetwork(network: String) {
        this.network = network
    }

    fun buildArgs(buildArgs: Map<String, String>) {
        this.buildArgs = buildArgs.toMap()
    }

    fun getPull(): Boolean = pull

    fun pull(pull: Boolean) {
        this.pull = pull
    }

    fun getLoad(): Boolean = load

    fun load(load: Boolean) {
        this.load = load
    }

    fun getPush(): Boolean = push

    fun push(push: Boolean) {
        this.push = push
    }

    fun getBuildx(): Boolean = buildx

    fun buildx(buildx: Boolean) {
        this.buildx = buildx
    }

    fun getPlatform(): Set<String> = platform

    fun platform(vararg args: String) {
        this.platform = args.toSet()
    }

    fun getBuilder(): String? = builder

    fun builder(builder: String) {
        this.builder = builder
    }
}
