package com.corevault.gradle.docker

import org.gradle.testkit.runner.GradleRunner
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

    fun gradleRunner(vararg tasks: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*tasks)
            .withPluginClasspath()
            .withDebug(true)

    fun exec(vararg command: String): String {
        val proc = ProcessBuilder(*command).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.errorStream.bufferedReader().readText()
        proc.waitFor()
        return output
    }

    fun execCond(vararg command: String): Boolean {
        val proc = ProcessBuilder(*command).start()
        proc.inputStream.bufferedReader().readText()
        proc.errorStream.bufferedReader().readText()
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
