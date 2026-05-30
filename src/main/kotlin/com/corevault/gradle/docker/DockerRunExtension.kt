package com.corevault.gradle.docker

open class DockerRunExtension {

    var name: String? = null
    var image: String? = null
    var network: String? = null
    var command: MutableList<String> = mutableListOf()
    var ports: MutableSet<String> = mutableSetOf()
    var env: MutableMap<String, String> = mutableMapOf()
    var arguments: MutableList<String> = mutableListOf()
    var volumes: MutableMap<Any, String> = mutableMapOf()
    var daemonize: Boolean = true
    var clean: Boolean = false
    var ignoreExitValue: Boolean = false

    fun command(vararg args: String) {
        command = args.toMutableList()
    }

    fun arguments(vararg args: String) {
        arguments = args.toMutableList()
    }

    fun env(env: Map<String, String>) {
        this.env = env.toMutableMap()
    }

    fun volumes(volumes: Map<Any, String>) {
        this.volumes = volumes.toMutableMap()
    }

    fun ports(vararg portMappings: String) {
        val result = mutableSetOf<String>()
        for (port in portMappings) {
            val parts = port.split(":", limit = 2)
            if (parts.size == 1) {
                checkPortIsValid(parts[0])
                result.add("${parts[0]}:${parts[0]}")
            } else {
                checkPortIsValid(parts[0])
                checkPortIsValid(parts[1])
                result.add("${parts[0]}:${parts[1]}")
            }
        }
        ports = result
    }

    private fun checkPortIsValid(port: String) {
        val value = port.toIntOrNull()
            ?: throw IllegalArgumentException("Port must be a number, got: $port")
        if (value <= 0 || value > 65535) {
            throw IllegalArgumentException("Port must be in the range [1,65535], got: $port")
        }
    }
}
