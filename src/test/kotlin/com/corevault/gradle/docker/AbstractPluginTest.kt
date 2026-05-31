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

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

abstract class AbstractPluginTest {

    @TempDir
    lateinit var projectDir: File

    lateinit var buildFile: File

    @BeforeEach
    fun setup() {
        buildFile = file("build.gradle")
        println("Build directory:\n${projectDir.absolutePath}")
    }

    /** True when running in a CI environment (GitHub Actions, CircleCI, ... all set CI=true). */
    fun isCi(): Boolean = System.getenv("CI") == "true"

    /**
     * Skips (does not fail) the calling test when a usable Docker toolchain isn't expected — i.e. in
     * CI, where runners don't provide buildx/qemu/docker-compose-v1. These integration tests still
     * run locally where Docker is available. Call as the first line of any Docker-dependent test.
     */
    fun assumeDockerAvailable() {
        Assumptions.assumeFalse(isCi(), "Docker-dependent integration test skipped in CI environment")
    }

    fun gradleRunner(vararg tasks: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*tasks)
            .withPluginClasspath()
            .withDebug(true)

    fun exec(vararg command: String): String {
        val proc = ProcessBuilder(*command).start()
        // Drain stderr on a separate thread so a full stderr pipe can't deadlock the stdout read.
        val errGobbler = Thread { proc.errorStream.bufferedReader().readText() }.apply { start() }
        val output = proc.inputStream.bufferedReader().readText()
        errGobbler.join()
        proc.waitFor()
        return output
    }

    fun execCond(vararg command: String): Boolean {
        val proc = ProcessBuilder(*command).start()
        // Drain both streams concurrently to avoid a full-pipe deadlock.
        val outGobbler = Thread { proc.inputStream.bufferedReader().readText() }.apply { start() }
        val errGobbler = Thread { proc.errorStream.bufferedReader().readText() }.apply { start() }
        outGobbler.join()
        errGobbler.join()
        proc.waitFor()
        return proc.exitValue() == 0
    }

    fun createFile(path: String, baseDir: File = projectDir): File {
        val f = file(path, baseDir)
        check(!f.exists()) { "File already exists: $f" }
        f.parentFile?.mkdirs()
        check(f.createNewFile()) { "Could not create file: $f" }
        return f
    }

    fun file(path: String, baseDir: File = projectDir): File {
        val parts = path.split("/")
        val dir =
            if (parts.size > 1) {
                File(baseDir, parts.dropLast(1).joinToString("/")).also { it.mkdirs() }
            } else {
                baseDir
            }
        return File(dir, parts.last())
    }

    fun directory(path: String, baseDir: File = projectDir): File =
        File(baseDir, path).also { it.mkdirs() }

    fun escapePath(path: String): String = path.replace("\\", "\\\\")
}
