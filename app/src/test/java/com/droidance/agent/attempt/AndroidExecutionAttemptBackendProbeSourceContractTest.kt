// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.attempt

import com.droidance.core.agent.attempt.*

import com.droidance.agent.attempt.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidExecutionAttemptBackendProbeSourceContractTest {
    @Test
    fun retiredRoutesAreRejectedBeforePreparingAnyBackend() {
        val source = projectFile("app/src/main/java/com/droidance/agent/attempt/AndroidExecutionAttemptBackendProbe.kt").readText()
        assertTrue(source.contains("route.runtimeMode == RuntimeMode.STANDARD && route.executionSpace == ExecutionSpace.VIRTUAL_SPACE"))
        assertTrue(source.contains("if (!supported(route)) return unavailable(route)"))
    }

    @Test
    fun standardVirtualExecutionFreezesTheResidentBinding() {
        val source = projectFile("app/src/main/java/com/droidance/agent/attempt/AndroidExecutionAttemptBackendProbe.kt").readText()
        assertTrue(source.contains("virtualSpaceReadiness.ensureReadyResult()"))
        assertTrue(source.contains("virtualSpaceManager.activeBinding()"))
        assertTrue(source.contains("it.displayId > 0"))
        assertTrue(source.contains("virtualSpaceSidecarBridge.isBoundSessionAlive(it)"))
        assertFalse(source.contains("RootMainScreenSessionBinding"))
    }

    @Test
    fun adbSessionGenerationChangesWhenConnectionIsReplaced() {
        val air = projectFile("core/device/src/main/java/com/droidance/core/device/air/AirModePairingManager.kt").readText()
        assertTrue(air.contains("shellSessionGeneration += 1L"))
        assertTrue(air.contains("fun currentShellSessionId(): String?"))
        assertFalse(projectFile("core/device/src/main/java/com/droidance/core/device/tools").resolve("RootDaemonShell.kt").exists())
    }

    @Test
    fun unifiedDeviceBackendCannotEscapeTheFrozenVirtualSession() {
        val backend = projectFile("app/src/main/java/com/droidance/agent/device/AndroidDeviceBackend.kt").readText()
        assertTrue(backend.contains("sidecarBridge.execShellCommand(binding, command, timeoutMs)"))
        assertTrue(backend.contains("context.mode != RuntimeMode.STANDARD || !context.isVirtualSpace || context.displayId <= 0"))
        assertTrue(backend.contains("SidecarSessionBinding(context.mode, context.displayId, context.backendSessionId)"))
        assertTrue(backend.contains("sidecarBridge::isBoundSessionAlive"))
        assertFalse(backend.contains("execRoot"))
    }

    @Test
    fun touchTextAndForegroundQueriesShareTheSameFrozenIdentityCheck() {
        val bridge = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/VirtualSpaceSidecarBridge.kt").readText()
        val input = projectFile("core/device/src/main/java/com/droidance/core/device/tools/SidecarInputController.kt").readText()
        val boundCalls = bridge.substringAfter("private fun <T> callBoundValue(")
            .substringBefore("private fun backendSessionId(")
        assertTrue(boundCalls.contains("matchesExpectedSession(binding.displayId, binding.runtimeMode, service, binding.backendSessionId)"))
        assertTrue(boundCalls.contains("matchesExpectedSession(displayId, mode, service, expectedBackendSessionId)"))
        assertTrue(input.contains("sidecarBridge.injectTouch(cancellationBinding, android.view.MotionEvent.ACTION_CANCEL"))
        assertFalse(bridge.contains("if (rootSession != null && rootSession == expectedSessionId) return true"))
    }

    private fun projectFile(path: String): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $current")
    }
}
