// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.tools.ToolProtocolResult
import com.droidance.core.model.llm.models.LLMStreamEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DevicePermissionRecoveryIntegrationTest {
    @Test fun deniedInputPausesAndResumeObservesAgainWithoutReplayingTheFailedCall() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("permission-recovery", "Inspect the page")
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "denied-click", "screen_act",
            """{"actions":[{"op":"click","x":1,"y":1}]}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("stop",
            """{"status":"need_more_info","deliverables":[],"results":[],"unresolved_requirements":["User input is required"],"requirement_checks":[]}"""))
        fixture.toolExecutor.resultOverride = { call, _ -> when (call.name) {
            "screen_act" -> ToolProtocolResult.failed("INPUT_PERMISSION_DENIED", false)
            "android_read" -> fixture.toolExecutor.stageObservation(frame, "restored-frame", "Restored screen", 2)
            else -> null
        } }
        var paused = false
        var resumes = 0
        var notice = ""
        val result = fixture.coordinator.coordinateTask("Inspect the page", "", started.active, "",
            started.expectedBackend, started.executionStrategy, initialScreenRequired = true, initialScreenFrame = frame,
            toolProfile = started.profile, mode = started.mode, host = object : TurnHost {
                override val isPaused: () -> Boolean = { paused }
                override val onManualTakeoverNeeded: (String) -> Unit = { notice = it; paused = true }
                override val waitIfPaused: suspend () -> Unit = { if (paused) { resumes++; paused = false } }
            })
        fixture.complete(started, result)
        assertEquals("input_permission_denied_handoff", notice)
        assertEquals(1, resumes)
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "screen_act" })
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "android_read" })
        assertEquals(2, fixture.model.recordedRequests.size)
        assertTrue(fixture.model.recordedRequests.last().any { it.content.contains("Restored screen") })
        assertTrue(fixture.events(started).any { it.eventKind == AgentEventKind.TOOL_FINISHED &&
            it.payloadJson.contains("INPUT_PERMISSION_DENIED") })
        assertNotEquals(TurnTerminal.SYSTEM_ERROR, result.terminal)
    }
}
