// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.device.air.AirModePairingManager
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/** ADB bootstrap and diagnostic commands. Committed device calls use their frozen Sidecar. */
@Singleton
class AdbShellExecutor @Inject constructor(
    private val latencyTracker: com.droidance.core.agent.debug.TaskLatencyTracker,
    private val airManager: Lazy<AirModePairingManager>
) {
    data class Result(val stdout: String, val stderr: String, val exitCode: Int,
        val executionStatus: com.droidance.core.agent.event.ToolExecutionStatus = if (exitCode == 0)
            com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED else com.droidance.core.agent.event.ToolExecutionStatus.FAILED,
        val diagnosticCode: String? = null,
        val stdoutTruncated: Boolean = false, val stderrTruncated: Boolean = false,
        val stdoutBytes: Long = stdout.toByteArray().size.toLong(), val stderrBytes: Long = stderr.toByteArray().size.toLong(),
        val executionUid: Int? = null) {
        val success: Boolean get() = executionStatus == com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED
        val output: String get() = stdout.ifBlank { stderr }
    }
    fun isAvailable(): Boolean = airManager.get().isShellReady
    fun escapeForShell(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    fun exec(command: String, timeoutMs: Long = 15_000): Result {
        latencyTracker.airShellCommands.incrementAndGet()
        val marker = "__DROIDANCE_EXIT_${System.nanoTime()}__"
        val script = "$command\nrc=\$?\necho $marker\$rc"
        val output = airManager.get().shell("sh -c ${escapeForShell(script)}", timeoutMs)
        val failed = output.startsWith("[AIR_DISCONNECTED]") ||
            output.startsWith("[AIR_ERROR]") ||
            output.startsWith("[AIR_TIMEOUT]")
        if (failed) return Result("", output, -1)

        val lines = output.lineSequence().toList()
        val markerIndex = lines.indexOfLast { it.startsWith(marker) }
        if (markerIndex < 0) return Result(output.trim(), "", 0)

        val exitCode = lines[markerIndex].removePrefix(marker).trim().toIntOrNull() ?: 0
        val body = lines.take(markerIndex).joinToString("\n").trim()
        return Result(
            stdout = if (exitCode == 0) body else "",
            stderr = if (exitCode == 0) "" else body,
            exitCode = exitCode
        )
    }

}
