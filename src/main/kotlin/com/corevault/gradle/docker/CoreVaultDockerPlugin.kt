package com.corevault.gradle.docker

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
        try {
            project.configurations.named("docker")
        } catch (_: Exception) {
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
            prepareTask.with(ext.getCopySpec())
            val dockerfileName = ext.getResolvedDockerfile()!!.name
            prepareTask.from(ext.getResolvedDockerfile()).rename { fileName: String ->
                fileName.replace(dockerfileName, "Dockerfile")
            }
            prepareTask.into(dockerDirProvider.get())

            val dockerDependencies = ext.getDependencies()
            val execTask = execBuild.get()
            execTask.setWorkingDir(dockerDirProvider.get())
            // Defer commandLine so imageName can be set in an afterEvaluate block
            execTask.doFirst { execTask.commandLine(buildCommandLine(ext)) }
            execTask.dependsOn(dockerDependencies)
            execTask.logging.captureStandardOutput(LogLevel.INFO)
            execTask.logging.captureStandardError(LogLevel.ERROR)

            // Build tag map: (displayName, tagResolver(imageName) -> finalTag)
            // Capture raw tag data at configuration time; resolve imageName at execution time via doFirst.
            val tags = mutableMapOf<String, Pair<String, (String) -> String>>()
            ext.namedTags.forEach { (taskName, tagName) ->
                val normalizedTaskName = generateTagTaskName(taskName)
                require(!tags.containsKey(normalizedTaskName)) {
                    "Task name '$normalizedTaskName' (from named tag '$taskName') already exists."
                }
                tags[normalizedTaskName] = Pair(tagName) { _ -> tagName }
            }
            if (ext.getTags().isNotEmpty()) {
                ext.getTags().forEach { unresolvedTagName ->
                    val taskName = generateTagTaskName(unresolvedTagName)
                    require(!tags.containsKey(taskName)) { "Task name '$taskName' already exists." }
                    tags[taskName] = Pair(unresolvedTagName) { imgName -> computeName(imgName, unresolvedTagName) }
                }
            }

            tags.forEach { (taskName, tagInfo) ->
                // tagInfo.first = display name; tagInfo.second = (imageName) -> final tag
                val displayName = tagInfo.first

                val tagSubTask = project.tasks.register("dockerTag$taskName", Exec::class.java) { t ->
                    t.group = "Docker"
                    t.description = "Tags Docker image with tag '$displayName'"
                    t.setWorkingDir(dockerDirProvider.get())
                    t.commandLine("docker", "tag")
                    t.dependsOn(execBuild)
                }
                val tagExec = tagSubTask.get()
                tagExec.doFirst {
                    val resolvedImageName = ext.imageName!!
                    tagExec.args(resolvedImageName, tagInfo.second(resolvedImageName))
                }
                tag.get().dependsOn(tagSubTask)

                val pushSubTask = project.tasks.register("dockerPush$taskName", Exec::class.java) { t ->
                    t.group = "Docker"
                    t.description = "Pushes the Docker image with tag '$displayName'"
                    t.setWorkingDir(dockerDirProvider.get())
                    t.commandLine("docker", "push")
                    t.dependsOn(tagSubTask)
                }
                val pushExec = pushSubTask.get()
                pushExec.doFirst {
                    val resolvedImageName = ext.imageName!!
                    pushExec.args(tagInfo.second(resolvedImageName))
                }
                pushAllTags.get().dependsOn(pushSubTask)
            }

            dockerfileZip.get().from(ext.getResolvedDockerfile())
        }
    }

    companion object {
        @Suppress("unused")
        private val log: Logger = Logging.getLogger(CoreVaultDockerPlugin::class.java)
        private val LABEL_KEY_PATTERN: Pattern = Pattern.compile("^[a-z0-9.-]+$")

        private fun buildCommandLine(ext: DockerExtension): List<String> {
            val cmdList = mutableListOf("docker")
            if (ext.getBuildx()) {
                appendBuildxArgs(cmdList, ext)
            } else {
                cmdList.add("build")
            }
            if (ext.noCache) cmdList.add("--no-cache")
            if (ext.getNetwork() != null) cmdList.addAll(listOf("--network", ext.getNetwork()!!))
            for ((key, value) in ext.getBuildArgs()) {
                cmdList.addAll(listOf("--build-arg", "$key=$value"))
            }
            for (secret in ext.secrets) {
                cmdList.addAll(listOf("--secret", secret))
            }
            appendLabelArgs(cmdList, ext)
            if (ext.getPull()) cmdList.add("--pull")
            cmdList.addAll(listOf("-t", ext.imageName!!, "."))
            return cmdList
        }

        private fun appendBuildxArgs(cmdList: MutableList<String>, ext: DockerExtension) {
            cmdList.addAll(listOf("buildx", "build"))
            if (ext.getPlatform().isNotEmpty()) {
                cmdList.addAll(listOf("--platform", ext.getPlatform().joinToString(",")))
            }
            if (ext.getLoad()) cmdList.add("--load")
            if (ext.getPush()) {
                cmdList.add("--push")
                if (ext.getLoad()) throw RuntimeException("cannot combine 'push' and 'load' options")
            }
            if (ext.getBuilder() != null) {
                cmdList.addAll(listOf("--builder", ext.getBuilder()!!))
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
            val firstAt = name.indexOf("@")
            val tagTaskName =
                when {
                    firstAt > 0 -> name.substring(0, firstAt)
                    firstAt == 0 -> throw GradleException("Task name of docker tag '$name' must not be empty.")
                    name.contains(":") || name.contains("/") ->
                        throw GradleException("Docker tag '$name' must have a task name.")
                    else -> name
                }
            return tagTaskName.replaceFirstChar { it.uppercase() }
        }
    }
}
