// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CooperativeTakeoverSourceContractTest {
    @Test
    fun takeoverRequestsPauseWithoutCancellingTheModelOrTask() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val begin = runtime.substringAfter("fun beginManualTakeoverFromOverlay()")
            .substringBefore("private suspend fun waitIfPaused()")
        val explicitCancel = runtime.substringAfter(
            "fun cancelCurrentTask(taskId: String? = null, origin: String = \"runtime\")"
        )
            .substringBefore("fun cancelCurrentTaskAndNotify")

        assertTrue(begin.contains("pauseForManualControl("))
        assertFalse(begin.contains("requestManualTakeover("))
        assertFalse(begin.contains("notifyManualTakeoverRequired"))
        assertFalse(begin.contains("cancelActiveRequests"))
        assertTrue(explicitCancel.contains("cancelActiveRequests"))
        assertTrue(explicitCancel.contains("Task cancellation requested: origin="))
        assertTrue(runtime.contains("ManualTakeoverState.PAUSE_REQUESTED"))
        assertTrue(runtime.contains("ManualTakeoverState.PAUSED"))
        assertTrue(runtime.contains("ManualTakeoverState.RESUMING"))
    }

    @Test
    fun inFlightModelResponseCompletesBeforeTheNextToolPauseBoundary() {
        val operator = (com.droidance.agent.coordinationSources() + "\n" + projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText())
        val streamCollector = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/OperatorStreamCollector.kt").readText().substringAfter(") { event ->")
            .substringBefore("when (event)")
        val toolLoop = operator.substringAfter("for ((toolIdx, tc) in toolCalls.withIndex()) {")
            .substringBefore("val selfManagementTool")

        assertFalse(streamCollector.contains("waitIfPaused()"))
        assertTrue(toolLoop.contains("waitForActiveExecution()"))
        assertTrue(toolLoop.contains("takeManualResumeInstruction()"))
        assertTrue(toolLoop.contains("ToolRoundOutcome.RestartRound"))
    }

    @Test
    fun takeoverSurfaceUsesTheRetainedVirtualDisplayAcrossIdleTurnBoundaries() {
        val overlay = projectFile("app/src/main/java/com/droidance/ui/overlay/AgentStatusOverlay.kt").readText()
        val launcher = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerLauncher.kt"
        ).readText()
        val route = overlay.substringAfter("private fun handleRuntimeIslandAction()")
            .substringBefore("private fun handleRuntimeIslandStop()")

        assertTrue(overlay.contains("virtualSpaceViewerLauncher.open()"))
        assertTrue(launcher.contains("virtualSpaceManager.hasLiveSessionForMode(binding.runtimeMode)"))
        assertTrue(launcher.contains("virtualSpaceReadiness.ensureReadyResult().ready"))
        assertTrue(launcher.contains("virtualSpaceManager.activeBinding() != null"))
        assertTrue(launcher.contains("if (!hasLiveDisplay() && (expectedOwner != null || !prepareDisplay())) return false"))
        assertFalse(launcher.contains("executionContextStore"))
        assertFalse(launcher.contains("virtualSpaceManager.isActive()"))
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
