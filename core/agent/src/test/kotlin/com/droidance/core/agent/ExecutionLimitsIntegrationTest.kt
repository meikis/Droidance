// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production loop after removing the configurable round-count limit. */
class ExecutionLimitsIntegrationTest {
    @Test(timeout = 30_000)
    fun localTaskCanContinuePastTheFormerMaximumRoundSettingAndComplete() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        fixture.model.cannedContextWindowTokens = 1_000_000
        val started = fixture.startTurn("long-local-task", "Inspect all requested records",
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        repeat(101) { index ->
            fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(
                0, "record-$index", "session_read", "{}"))
        }
        fixture.model.roundResponses += listOf(fixture.completionCall("finish", """
            {"status":"done","deliverables":[],"results":[{"text":"Requested records inspected.","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}
        """.trimIndent()))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Inspect all requested records", additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = null, executionStrategy = null,
            toolProfile = started.profile, mode = null)
        fixture.complete(started, result)

        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertTrue(result.taskDoneByModel)
        assertEquals(TurnFailureReason.NONE, result.failureReason)
        assertEquals(102, result.rounds)
        assertEquals(101, fixture.toolExecutor.localCalls.count { it.name == "session_read" })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }

    @Test(timeout = 10_000)
    fun taskTimeLimitStillStopsBeforeTheNextToolAndExplainsTheTimeLimit() = runBlocking {
        val fixture = TaskCoordinatorTestFixture(stoppedReplyEnabled = true, taskWallMs = 2_000L)
        val started = fixture.startTurn("time-limited-task", "Inspect the requested records",
            profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fixture.toolExecutor.beforeLocalExecute = { call ->
            if (call.name == "session_read") delay(2_100L)
        }
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read", "session_read", "{}"))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("The time limit ended the task before all records were inspected."))

        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Inspect the requested records", additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = null, executionStrategy = null,
            toolProfile = started.profile, mode = null)
        fixture.complete(started, result)

        assertEquals(TurnFailureReason.TASK_TIME_LIMIT, result.failureReason)
        assertEquals(TurnTerminal.SYSTEM_ERROR, result.terminal)
        assertFalse(result.taskDoneByModel)
        assertEquals(1, fixture.toolExecutor.localCalls.count { it.name == "session_read" })
        assertNotNull(result.modelFailureSummary)
        assertTrue(fixture.model.recordedRequests.last().single().content.contains("MAX_DEVICE_TASK_WALL_STOP"))
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }
}
