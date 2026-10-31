// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import com.droidance.core.device.tools.DeviceScreenFrame
import com.droidance.core.agent.tools.DeviceInputResult
import com.droidance.core.agent.tools.TapPrecision

data class DeviceShellOutcome(
    val backend: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val diagnosticCode: String? = null,
    val executionStatus: com.droidance.core.agent.event.ToolExecutionStatus = if (exitCode == 0)
        com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED else com.droidance.core.agent.event.ToolExecutionStatus.FAILED,
    val stdoutTruncated: Boolean = false, val stderrTruncated: Boolean = false,
    val stdoutBytes: Long = stdout.toByteArray().size.toLong(), val stderrBytes: Long = stderr.toByteArray().size.toLong(),
    val executionUid: Int? = null
) {
    val success: Boolean get() = diagnosticCode == null && executionStatus == com.droidance.core.agent.event.ToolExecutionStatus.SUCCEEDED
}

data class SystemQueryOutcome(
    val backend: String,
    val exitCode: Int,
    val output: SystemQueryOutput.Result,
    val stderr: String,
    val diagnosticCode: String? = null
) {
    val success: Boolean get() = diagnosticCode == null && output.diagnosticCode == null && exitCode == 0
}

data class DeviceAppLaunchOutcome(
    val accepted: Boolean,
    val frameReady: Boolean? = null,
    val backend: String,
    val detail: String,
    val diagnosticCode: String? = null
)

data class InputFocusReadiness(val target: String?, val diagnosticCode: String, val elapsedMs: Long = 0,
    val queryExitCode: Int? = null, val queryDiagnosticCode: String? = null,
    val queryExecutionStatus: String? = null, val queryStderr: String? = null,
    val queryAttempts: Int = 0, val queryOutputTruncated: Boolean = false,
    val binderFailure: String? = null, val queryOriginalChars: Long = 0, val querySelectedChars: Long = 0, val requestedDisplayId: Int? = null,
    val targetKind: String? = null, val focusedWindowName: String? = null, val queryCapturedElapsedMs: Long? = null)

data class FreshPixelSample(
    val colors: IntArray, val width: Int, val height: Int, val displayId: Int,
    val capturedAtMs: Long, val capturedElapsedMs: Long, val backendSessionId: String, val rotationDegrees: Int
)

/** Low-level operations bound to the immutable ExecutionContext for the active attempt. */
interface DeviceBackend {
    val activeDisplayId: Int?
    suspend fun captureScreen(forceFresh: Boolean = false): DeviceScreenFrame?
    suspend fun samplePixels(points: List<Pair<Int, Int>>): FreshPixelSample?
    suspend fun sampleScreen(): com.droidance.core.device.tools.VisualFrameSampler.Frame?
    fun lastScreenFrame(): DeviceScreenFrame?
    fun invalidateScreenCache()
    fun screenFailureMessage(): String
    suspend fun tap(x: Int, y: Int, precision: TapPrecision): DeviceInputResult
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): DeviceInputResult
    suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): DeviceInputResult
    suspend fun tracePath(points: List<Pair<Int, Int>>, durationMs: Int): DeviceInputResult
    suspend fun pressKey(keyCode: Int): DeviceInputResult
    suspend fun setKeyboardVisible(visible: Boolean): DeviceInputResult
    suspend fun awaitInputFocus(timeoutMs: Long): InputFocusReadiness
    suspend fun typeText(text: String, clearExisting: Boolean, expectedFocusTarget: String,
        method: com.droidance.core.agent.tools.TextInputMethod = com.droidance.core.agent.tools.TextInputMethod.SHORTCUT,
        focusPoint: Pair<Int, Int>? = null): DeviceInputResult
    suspend fun openPackage(
        packageName: String,
        launchMode: AppLaunchMode,
        intentFlags: Int
    ): DeviceAppLaunchOutcome
    suspend fun openIntentCommand(command: String): DeviceShellOutcome
    suspend fun forceStopApp(packageName: String): DeviceShellOutcome
    suspend fun shell(command: String, timeoutMs: Long): DeviceShellOutcome
    suspend fun querySystem(request: SystemStateQuery): SystemQueryOutcome
    suspend fun readClipboard(): com.droidance.core.agent.event.ToolExecutionResult
}

/** Argument-free platform dump operations. No arbitrary command text crosses this boundary. */
data class SystemStateQuery(val service: String?, val kind: SystemQueryKind = SystemQueryKind.SERVICE_DUMP,
                           val lineFilter: String = "", val contextLines: Int = 0) {
    init {
        require(service == null || service.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && !service.startsWith("-")) {
            "Invalid Android service identifier"
        }
        require(kind == SystemQueryKind.SERVICE_DUMP || service == null)
        require(lineFilter.length <= 200 && contextLines in 0..40 && (contextLines == 0 || lineFilter.isNotBlank()))
    }
    val command: String get() = when (kind) {
        SystemQueryKind.SERVICE_DUMP -> service?.let { "dumpsys -t 5 $it" } ?: "dumpsys -l"
        SystemQueryKind.WIFI_STATUS -> "cmd wifi status"
    }
}

enum class SystemQueryKind { SERVICE_DUMP, WIFI_STATUS }
