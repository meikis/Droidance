// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.air

import com.droidance.core.device.air.*

import com.droidance.air.*

import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.device.ExecutionSpace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirModeAutomationSourceContractTest {
    @Test
    fun adbShellExecutorHasNoRootFallback() {
        val shell = projectFile("core/device/src/main/java/com/droidance/core/device/tools/AdbShellExecutor.kt").readText()
        assertTrue(shell.contains("airManager.get().isShellReady"))
        assertTrue(shell.contains("airManager.get().shell("))
        assertFalse(shell.contains("execRoot"))
        assertFalse(shell.contains("ProcessBuilder"))
    }

    @Test
    fun adbShellAvailabilityUsesAirShellReadinessAndTreatsTimeoutAsFailure() {
        val shell = projectFile("core/device/src/main/java/com/droidance/core/device/tools/AdbShellExecutor.kt").readText()

        assertTrue("ADB shell availability must depend on AIR shell readiness", shell.contains("airManager.get().isShellReady"))
        assertTrue("AIR timeout must fail the shell call instead of being parsed as normal stdout", shell.contains("output.startsWith(\"[AIR_TIMEOUT]\")"))
    }

    @Test
    fun airInputUsesAdbShellWithNoAccessibilityFallback() {
        val input = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()
        val textRoutes = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TextInputRoutes.kt").readText()

        // 无 root 走 ADB shell input
        assertTrue(input.contains("sidecarBridge.injectNative(frozen, args)"))
        assertTrue(input.contains("execution.mode == RuntimeMode.STANDARD && execution.isVirtualSpace && execution.displayId > 0"))
        assertTrue(input.contains("fun clearFocusedText()"))
        // 彻底去无障碍：输入注入与文本路由都不再有 Accessibility 兜底
        assertFalse("input must not fall back to accessibility", input.contains("accessibilityFallback"))
        assertFalse("text routes must not use accessibility setText", textRoutes.contains("setTextOnFocusedEditable"))
        assertFalse("text routes must not import AccessibilityService", textRoutes.contains("viaAccessibility"))
    }

    @Test
    fun airLaunchDeepLinkAndForegroundVerificationDoNotRequireRoot() {
        val appLaunch = projectFile("app/src/main/java/com/droidance/tools/AppLaunchService.kt").readText()
        val verifier = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()
        val elementInput = projectFile("core/device/src/main/java/com/droidance/core/device/tools/ElementInteractionService.kt").readText()
        val touchInput = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TouchInputService.kt").readText()
        val textInput = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TextInputService.kt").readText()
        val executor = com.droidance.agent.executorSources()

        assertTrue(appLaunch.contains("deviceBackend.openIntentCommand(command)"))
        assertFalse(appLaunch.contains("deviceShellCommandChannel"))
        assertTrue(verifier.contains("sidecarBridge.getForegroundComponent("))
        assertFalse(verifier.contains("adbShell.exec(command, timeoutMs)"))
        assertTrue(elementInput.contains("performVerifiedTap("))
        assertFalse(elementInput.contains("adbShell"))
        assertFalse(touchInput.contains("sidecarInput"))
        assertTrue(touchInput.contains("deviceBackend.swipe("))
        assertFalse(textInput.contains("sidecarInput"))
        assertTrue(textInput.contains("deviceBackend.typeText(text, clear, focusAfter, method,"))
        // ToolExecutor 不再硬编码 stealth(无root但有shell) 分支——AIR 无 root 能力已下沉到
        // SidecarScreenCapture(root 或 AIR bridge)/DeviceBackend 层；文本输入直接走冻结
        // execution context 下的 sidecar 事务，不在工具层重新判断通道。
    }

    @Test
    fun backgroundExecutionGuardKeepsVirtualSpaceOffThePhysicalLockScreen() {
        val guard = projectFile("app/src/main/java/com/droidance/daemon/DeviceExecutionGuard.kt").readText()
        assertTrue(guard.contains("ExecutionSpace.VIRTUAL_SPACE"))
        assertTrue(guard.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(guard.contains("virtualSpaceManager.onScreenLocked()"))
        assertTrue(guard.indexOf("virtualSpaceManager.ensureDisplayInteractive()") < guard.indexOf("virtualSpaceManager.onScreenLocked()"))
        assertFalse(guard.contains("wakeMainScreenWithShell"))
        assertFalse(guard.contains("relockScreenWithShell"))
        assertFalse(guard.contains("KEYCODE_WAKEUP"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
