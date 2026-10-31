// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

internal object ResidentSidecarProcessCommands {
    fun listPidsCommand(
        uid: Int,
        protocolVersion: Int? = null
    ): String {
        require(uid == 2000) { "unsupported sidecar uid=$uid" }
        val identity = "(${'$'}1 == \"shell\" || ${'$'}1 == \"2000\")"
        if (protocolVersion != null) {
            require(protocolVersion > 0) { "invalid sidecar protocol=$protocolVersion" }
        }
        val protocol = protocolVersion
            ?.let { " && /[d]roidance-sidecar-protocol=$it/" }
            .orEmpty()
        return "ps -A -o UID,PID,ARGS | " +
            "awk '$identity && /[V]irtualSpaceSidecarMain/$protocol { print ${'$'}2 }'"
    }

    fun parsePids(output: String): List<Int> =
        output.lineSequence()
            .mapNotNull { line -> line.trim().toIntOrNull() }
            .filter { pid -> pid > 1 }
            .distinct()
            .toList()

    fun stopCommand(pids: Collection<Int>, force: Boolean): String? {
        val signal = if (force) "-9 " else ""
        return pids.asSequence()
            .filter { pid -> pid > 1 }
            .distinct()
            .joinToString("; ") { pid -> "kill ${signal}${pid} 2>/dev/null" }
            .ifBlank { null }
    }
}
