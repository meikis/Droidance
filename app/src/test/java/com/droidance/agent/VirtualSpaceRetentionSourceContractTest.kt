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

class VirtualSpaceRetentionSourceContractTest {
    @Test
    fun terminalAndStoppedTurnsRetainTheVirtualDisplayUntilSessionReplacement() {
        val runtime = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()
        val manager = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceManager.kt"
        ).readText()
        val viewer = projectFile(
            "app/src/main/java/com/droidance/agent/device/VirtualSpaceViewerActivity.kt"
        ).readText()
        val home = projectFile(
            "app/src/main/java/com/droidance/ui/viewmodel/HomeViewModel.kt"
        ).readText()
        val executor = com.droidance.agent.executorSources()
        val terminalFinalization = runtime
            .substringAfter("diagnostics.finishTaskDiagnostics(request.requestId)")
            .substringBefore("val effectiveTakeoverStatus")

        assertFalse(runtime.contains("VirtualSpaceTerminalLeasePolicy"))
        assertFalse(terminalFinalization.contains("virtualSpaceManager.stop()"))
        assertTrue(manager.contains("fun stop()"))
        assertTrue(manager.contains("fun discardStaleSession("))
        assertTrue(manager.contains("val retainedExecution: StateFlow<RetainedVirtualExecution?>"))
        assertTrue(manager.contains("RetainedVirtualExecution(threadId, executionId,"))
        assertTrue(manager.contains("mutableRetainedExecution.value = null"))
        assertFalse(runtime.contains("virtualSpaceManager.stop()"))
        assertFalse(viewer.contains("shouldKeepVirtualSpaceViewerOpen()"))
        assertFalse(viewer.contains("virtualSpaceManager.stop()"))
        assertFalse(viewer.contains("observedActiveTask"))
        assertTrue(home.contains("val retainedVirtualExecution = virtualSpaceManager.retainedExecution"))
        val frozenActivation = executor.substringAfter("fun beginFrozenExecutionContext(")
            .substringBefore("fun clearExecutionContext()")
        assertTrue(frozenActivation.contains("virtualSpaceManager.beginExecution(context.executionId,"))
        assertFalse(home.contains("state != AgentState.IDLE &&\n                    executionSpace"))
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
