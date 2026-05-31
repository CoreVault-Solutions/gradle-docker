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
        val value = requireNotNull(port.toIntOrNull()) { "Port must be a number, got: $port" }
        require(value in 1..65535) { "Port must be in the range [1,65535], got: $port" }
    }
}
