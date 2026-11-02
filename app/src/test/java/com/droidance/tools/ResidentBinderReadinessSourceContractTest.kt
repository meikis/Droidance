// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5.5 断 Wi-Fi 后 readiness 契约：MAIN_SCREEN 与 VIRTUAL_DISPLAY 都认可存活的常驻 Binder，
 * shell/截图工具走统一通道，在线 ADB 与常驻 Binder 是两个独立 transport 信号。
 */
class ResidentBinderReadinessSourceContractTest {

    @Test
    fun shellBackedToolReadinessUsesUnifiedChannelForMainAndVirtual() {
        val executor = com.droidance.agent.executorSources()
        val shellFn = executor.substringAfter("private fun isShellBackedToolAvailable")
            .substringBefore("\n    private fun ")

        assertTrue(shellFn.contains("deviceShellCommandChannel.isAvailable()"))
        // 不再只在 VIRTUAL_SPACE 放行 Binder，也不再单独门禁 adbShell。
        assertFalse(shellFn.contains("effectiveSpace != ExecutionSpace.VIRTUAL_SPACE"))
        assertFalse(shellFn.contains("adbShell.isShellAvailable()"))
    }

    @Test
    fun channelPrefersResidentBinderThenFallsBackToFrozenModeShell() {
        val channel = projectFile("core/device/src/main/java/com/droidance/core/device/tools/DeviceShellCommandChannel.kt").readText()

        assertTrue(channel.contains("modeProvider.currentMode()?.let(sidecarBridge::isResidentBinderAlive) == true"))
        assertTrue(channel.contains("sidecarBridge.execShellCommand("))
        assertTrue(channel.contains("RuntimeMode.STANDARD -> adbShell.exec("))
        assertFalse(channel.contains("execRootOnly"))
        assertTrue(channel.contains("enum class Backend"))

        val execFn = channel.substringAfter("fun exec(")
        val binderIdx = execFn.indexOf("binderAvailable()")
        val shellIdx = execFn.indexOf("shellAvailable()")
        assertTrue("Binder must be tried before frozen-mode shell fallback", binderIdx in 0 until shellIdx)
    }

    @Test
    fun unifiedShellUsesFrozenDeviceBackendAndLegacyRegistrarsAreGone() {
        val protocol = projectFile("core/device/src/main/java/com/droidance/core/device/tools/UnifiedDeviceToolRegistrar.kt").readText()
        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()
        val retiredPlatform = projectFile("app/src/main/java/com/droidance/tools")
            .resolve("PlatformToolRegistrar.kt")
        val retiredSystemShell = projectFile("app/src/main/java/com/droidance/tools")
            .resolve("SystemShellToolRegistrar.kt")

        assertTrue(protocol.contains("val outcome = deviceBackend.shell(command, timeoutMs)"))
        assertTrue(backend.contains("frozenSidecarBinding(executionContextStore.requireCurrent())"))
        assertTrue(backend.contains("!context.isVirtualSpace || context.displayId <= 0"))
        assertTrue(backend.contains("bound_sidecar_device_shell"))
        assertTrue(backend.contains("SidecarSessionBinding(context.mode, context.displayId, context.backendSessionId)"))
        assertFalse(retiredPlatform.exists())
        assertFalse(retiredSystemShell.exists())
    }

    @Test
    fun screenInputDeliveryUsesDeviceBackendInsteadOfToolExecutorOwnedRootInput() {
        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()
        val executor = com.droidance.agent.executorSources()
        val touch = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TouchInputService.kt").readText()
        val text = projectFile("core/device/src/main/java/com/droidance/core/device/tools/TextInputService.kt").readText()
        val key = projectFile("core/device/src/main/java/com/droidance/core/device/tools/KeyInputService.kt").readText()
        val observe = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()
        val verifier = projectFile("app/src/main/java/com/droidance/agent/LocalActionVerifier.kt").readText()

        assertTrue(backend.contains("suspend fun tap("))
        assertTrue(backend.contains("suspend fun swipe("))
        assertTrue(backend.contains("suspend fun pressKey("))
        assertTrue(backend.contains("suspend fun typeText("))
        assertTrue(backend.contains("suspend fun captureScreen("))
        assertTrue(backend.contains("sidecarCapture.captureBoundFrame(executionContextStore.requireCurrent(), forceFresh)"))
        assertFalse(executor.contains("private val sidecarInput: SidecarInputController"))
        assertFalse(executor.contains("internal val sidecarCapture: SidecarScreenCapture"))
        assertTrue(executor.contains("deviceBackend.tap(x, y, precision)"))
        assertTrue(observe.contains("private val deviceBackend: DeviceBackend"))
        assertTrue(verifier.contains("private val deviceBackend: DeviceBackend"))
        assertFalse(observe.contains("private val capture: SidecarScreenCapture"))
        assertFalse(verifier.contains("private val sidecarCapture: SidecarScreenCapture"))
        assertTrue(touch.contains("deviceBackend.swipe("))
        assertTrue(text.contains("deviceBackend.typeText(text, clear, focusAfter, method,"))
        assertTrue(backend.contains("sidecarInput.inputText(text, clearExisting, expectedFocusTarget, method)"))
        assertTrue(key.contains("deviceBackend.pressKey(keyCode)"))
        assertFalse(touch.contains("sidecarInput."))
        assertFalse(text.contains("sidecarInput."))
        assertFalse(key.contains("sidecarInput."))
    }

    @Test
    fun rootScreenCaptureRecognizesResidentBinder() {
        val capture = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarScreenCapture.kt").readText()
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()

        assertTrue(bridge.contains("fun isResidentBinderAlive()"))
        val isAvailableBody = capture.substringAfter("fun isAvailable()").substringBefore("\n\n")
        assertTrue(isAvailableBody.contains("sidecarBridge.isResidentBinderAlive()"))
    }

    @Test
    fun sidecarResendsBinderWhenTheAppRestartsWithTheScreenOff() {
        val sidecar = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarMain.java").readText()
        val rebroadcast = sidecar.substringAfter("private static void startRebroadcast(")

        assertTrue(rebroadcast.contains("readAppPid(targetPackage)"))
        assertTrue(rebroadcast.contains("appPid != observedAppPid"))
    }

    @Test
    fun sidecarFollowsScreenStateWithoutSystemReceivers() {
        val sidecar = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarMain.java").readText()

        assertFalse(sidecar.contains("context.registerReceiver("))
        assertFalse(sidecar.contains("KeyguardManager keyguard"))
        assertTrue(sidecar.contains("startScreenLifecycle(sidecarContext, service, targetPackage)"))
        assertTrue(sidecar.contains("service.onScreenLocked()"))
        assertTrue(sidecar.contains("service.onScreenUnlocked()"))
    }

    @Test
    fun readNotificationsUsesTheUnifiedDeviceShellChannel() {
        val registrar = projectFile("app/src/main/java/com/droidance/tools/NotificationToolRegistrar.kt").readText()

        assertTrue(registrar.contains("private val deviceShell: DeviceShellCommandChannel"))
        assertFalse(registrar.contains("adbShell"))
    }

    @Test
    fun capabilityRegistryKeepsAdbAndBinderSignalsSeparateWithTransportDiagnostic() {
        val registry = projectFile("app/src/main/java/com/droidance/agent/RuntimeCapabilityRegistry.kt").readText()

        assertTrue(registry.contains("val adbBridgeAvailable"))
        assertTrue(registry.contains("val virtualSpaceBridgeAvailable"))
        assertTrue(registry.contains("adbBridgeAvailable = airModePairingManager.isConnected"))
        // ADB 与 Binder 是独立信号；变化只记录 transport 迁移，两者都消失才提示重连，不触发重新配对阻断。
        assertTrue(registry.contains("transport_changed(adbAvailable="))
        assertTrue(registry.contains("transport_reconnect_needed"))
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
