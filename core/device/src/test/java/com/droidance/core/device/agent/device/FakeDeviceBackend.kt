// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import com.droidance.core.agent.tools.DeviceInputResult
import com.droidance.core.device.tools.DeviceScreenFrame
import com.droidance.core.agent.tools.TapPrecision
import com.droidance.core.device.tools.VisualFrameSampler

class FakeDeviceBackend(
    override var activeDisplayId: Int? = 0
) : DeviceBackend {
    var capturedFrame: DeviceScreenFrame? = null
    var lastExecutedShellCommand: String? = null
    var lastShellTimeoutMs: Long? = null
    var shellOutcome: DeviceShellOutcome = DeviceShellOutcome("fake", 0, "ok", "")
    var clipboardOutcome: com.droidance.core.agent.event.ToolExecutionResult =
        com.droidance.core.agent.tools.ToolProtocolResult.failed("CLIPBOARD_NO_READABLE_CLIP", false)
    var clipboardReadCalls = 0
    val tapCalls = mutableListOf<Triple<Int, Int, TapPrecision>>()
    val swipeCalls = mutableListOf<List<Int>>()
    val keyCalls = mutableListOf<Int>()
    val keyboardRequests = mutableListOf<Boolean>()
    val typedTexts = mutableListOf<Pair<String, Boolean>>()

    override suspend fun captureScreen(forceFresh: Boolean): DeviceScreenFrame? = capturedFrame
    override suspend fun samplePixels(points: List<Pair<Int, Int>>): FreshPixelSample? = error("not used")
    override suspend fun sampleScreen(): VisualFrameSampler.Frame? = null
    override fun lastScreenFrame(): DeviceScreenFrame? = capturedFrame
    override fun invalidateScreenCache() {}
    override fun screenFailureMessage(): String = "fake screen error"

    override suspend fun tap(x: Int, y: Int, precision: TapPrecision): DeviceInputResult {
        tapCalls += Triple(x, y, precision)
        return DeviceInputResult(true, "tapped ($x, $y)")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): DeviceInputResult {
        swipeCalls += listOf(x1, y1, x2, y2, durationMs)
        return DeviceInputResult(true, "swiped")
    }

    override suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): DeviceInputResult =
        error("not used")

    override suspend fun tracePath(points: List<Pair<Int, Int>>, durationMs: Int): DeviceInputResult =
        error("not used")

    override suspend fun pressKey(keyCode: Int): DeviceInputResult {
        keyCalls += keyCode
        return DeviceInputResult(true, "pressed key $keyCode")
    }

    override suspend fun setKeyboardVisible(visible: Boolean): DeviceInputResult {
        keyboardRequests += visible
        return DeviceInputResult(true, "Native keyboard allowed=$visible")
    }

    override suspend fun awaitInputFocus(timeoutMs: Long): InputFocusReadiness =
        InputFocusReadiness(null, "INPUT_CONNECTION_NOT_READY")

    override suspend fun typeText(text: String, clearExisting: Boolean, expectedFocusTarget: String,
        method: com.droidance.core.agent.tools.TextInputMethod, focusPoint: Pair<Int, Int>?): DeviceInputResult {
        typedTexts += text to clearExisting
        return DeviceInputResult(true, "typed $text")
    }

    override suspend fun openPackage(
        packageName: String,
        launchMode: AppLaunchMode,
        intentFlags: Int
    ): DeviceAppLaunchOutcome = DeviceAppLaunchOutcome(true, true, "fake", "opened $packageName")

    override suspend fun openIntentCommand(command: String): DeviceShellOutcome = shellOutcome

    override suspend fun forceStopApp(packageName: String): DeviceShellOutcome = shellOutcome

    override suspend fun shell(command: String, timeoutMs: Long): DeviceShellOutcome {
        lastExecutedShellCommand = command
        lastShellTimeoutMs = timeoutMs
        return shellOutcome
    }

    override suspend fun querySystem(request: SystemStateQuery): SystemQueryOutcome {
        lastExecutedShellCommand = request.command
        val selected = SystemQueryOutput.read(java.io.StringReader(shellOutcome.stdout), request.lineFilter, request.contextLines)
        return SystemQueryOutcome(shellOutcome.backend, shellOutcome.exitCode,
            SystemQueryOutput.Result(selected.stdout, selected.originalChars, selected.selectedChars,
                selected.originalLines, selected.selectedLines, selected.truncated || shellOutcome.stdoutTruncated,
                selected.diagnosticCode),
            shellOutcome.stderr, shellOutcome.diagnosticCode)
    }
    override suspend fun readClipboard(): com.droidance.core.agent.event.ToolExecutionResult {
        clipboardReadCalls++
        return clipboardOutcome
    }
}
