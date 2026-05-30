package com.corevault.gradle.docker

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Exec

class DockerComposePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("dockerCompose", DockerComposeExtension::class.java, project)
        val dockerConfiguration = project.configurations.maybeCreate("docker")

        // sls-packaging adds a 'productDependencies' configuration with inferred lower bounds of products.
        // Wire it up automatically so users don't need to add the dependency manually.
        project.subprojects { subproject ->
            subproject.plugins.withId("com.palantir.product-dependency-introspection") {
                dockerConfiguration.dependencies.add(
                    subproject.dependencies.project(
                        mapOf("path" to subproject.path, "configuration" to "productDependencies"),
                    ),
                )
            }
        }
        project.plugins.withId("com.palantir.product-dependency-introspection") {
            dockerConfiguration.extendsFrom(project.configurations.getByName("productDependencies"))
        }

        project.tasks.register("generateDockerCompose", GenerateDockerCompose::class.java) { t ->
            t.configuration = dockerConfiguration
        }

        val dockerComposeUp = project.tasks.register("dockerComposeUp", Exec::class.java) { t ->
            t.group = "Docker"
            t.description = "Executes docker-compose up"
            // Route subprocess output through Gradle logging so it appears in build output/result.output
            t.logging.captureStandardOutput(LogLevel.INFO)
            t.logging.captureStandardError(LogLevel.ERROR)
        }

        val dockerComposeDown = project.tasks.register("dockerComposeDown", Exec::class.java) { t ->
            t.group = "Docker"
            t.description = "Executes docker-compose down"
            t.logging.captureStandardOutput(LogLevel.INFO)
            t.logging.captureStandardError(LogLevel.ERROR)
        }

        project.afterEvaluate {
            val ext = project.extensions.findByType(DockerComposeExtension::class.java)!!

            // Configure the exec tasks now that the extension is fully evaluated
            dockerComposeUp.get().let { up ->
                up.executable("docker-compose")
                up.args("-f", ext.dockerComposeFile, "up", "-d")
            }

            dockerComposeDown.get().let { down ->
                down.executable("docker-compose")
                down.args("-f", ext.dockerComposeFile, "down")
            }
        }
    }
}
