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
            prepareTask.with(ext.getCopySpec())
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
                    t.commandLine("docker", "push", finalTag)
                    t.dependsOn(tagSubTask)
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

        private fun buildCommandLine(ext: DockerExtension): List<String> {
            val cmdList = mutableListOf("docker")
            if (ext.buildx) {
                appendBuildxArgs(cmdList, ext)
            } else {
                cmdList.add("build")
            }
            if (ext.noCache) cmdList.add("--no-cache")
            if (ext.network != null) cmdList.addAll(listOf("--network", ext.network!!))
            for ((key, value) in ext.buildArgs) {
                cmdList.addAll(listOf("--build-arg", "$key=$value"))
            }
            for (secret in ext.secrets) {
                cmdList.addAll(listOf("--secret", secret))
            }
            appendLabelArgs(cmdList, ext)
            if (ext.pull) cmdList.add("--pull")
            cmdList.addAll(listOf("-t", ext.imageName!!, "."))
            return cmdList
        }

        private fun appendBuildxArgs(cmdList: MutableList<String>, ext: DockerExtension) {
            cmdList.addAll(listOf("buildx", "build"))
            if (ext.platform.isNotEmpty()) {
                cmdList.addAll(listOf("--platform", ext.platform.joinToString(",")))
            }
            if (ext.load) cmdList.add("--load")
            if (ext.push) {
                cmdList.add("--push")
                if (ext.load) throw GradleException("Cannot combine 'push' and 'load' options.")
            }
            if (ext.builder != null) {
                cmdList.addAll(listOf("--builder", ext.builder!!))
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
