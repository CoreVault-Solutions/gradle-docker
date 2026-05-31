/*
 * (c) Copyright 2025-2026 CoreVault Solutions.
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
 */
package com.corevaultsolutions.gradle.docker

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies each plugin's tasks can be stored in the Gradle configuration cache.
 *
 * Uses `--configuration-cache --dry-run`, which configures the task graph and stores the cache
 * without needing a Docker daemon — so these run in CI as well as locally. A task that captures
 * `Project`, a `Configuration`, or a shared `ByteArrayOutputStream` would fail the cache store here.
 */
class ConfigurationCacheTests : AbstractPluginTest() {

    private fun assertNoConfigCacheProblems(output: String) {
        assertFalse(
            output.contains("problem was found storing the configuration cache") ||
                output.contains("problems were found storing the configuration cache"),
            "Configuration cache problems were reported:\n$output",
        )
        // Positive signal: prove an entry was actually stored/reused, not merely that no error text appeared.
        assertTrue(
            Regex("Configuration cache entry (stored|reused)|Reusing configuration cache").containsMatchIn(output),
            "Expected the configuration cache to be stored or reused, but no positive signal was found:\n$output",
        )
    }

    @Test
    fun `docker and dockerTag tasks are configuration-cache compatible`() {
        file("Dockerfile").writeText("FROM alpine:3.2\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevaultsolutions.docker' }
            docker {
                imageName = 'cc-image'
                tags = ['latest', 'v1']
                labels['owner'] = 'corevault'
            }
            """.trimIndent(),
        )
        val result = gradleRunner("docker", "dockerTag", "--configuration-cache", "--dry-run").build()
        assertNoConfigCacheProblems(result.output)
    }

    @Test
    fun `docker-run tasks are configuration-cache compatible`() {
        buildFile.writeText(
            """
            plugins { id 'com.corevaultsolutions.docker-run' }
            dockerRun {
                name = 'cc-bar'
                image = 'alpine:3.2'
                ports '8080'
                network = 'host'
            }
            """.trimIndent(),
        )
        val result = gradleRunner(
            "dockerRun",
            "dockerRunStatus",
            "dockerNetworkModeStatus",
            "dockerStop",
            "dockerRemoveContainer",
            "--configuration-cache",
            "--dry-run",
        ).build()
        assertNoConfigCacheProblems(result.output)
    }

    @Test
    fun `generateDockerCompose is configuration-cache compatible`() {
        file("docker-compose.yml.template").writeText("svc:\n  image: '{{currentImageName}}'\n")
        buildFile.writeText(
            """
            plugins { id 'com.corevaultsolutions.docker-compose' }
            repositories { mavenCentral() }
            dockerCompose {
                templateTokens(['currentImageName': 'repo/svc:1.0.0'])
            }
            """.trimIndent(),
        )
        val result = gradleRunner("generateDockerCompose", "--configuration-cache", "--dry-run").build()
        assertNoConfigCacheProblems(result.output)
    }
}
