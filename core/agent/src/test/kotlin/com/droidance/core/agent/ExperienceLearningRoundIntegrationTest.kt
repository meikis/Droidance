// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceLearningRoundIntegrationTest {
    @Test
    fun learningWritesAndAnIndependentTaskActionShareOneNormalRound() = runBlocking {
        val f = TaskCoordinatorTestFixture()
        val goal = "Record the discovery and continue processing the current artifact."
        val started = f.startTurn("inline-experience", goal, profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        f.model.roundResponses += listOf(
            LLMStreamEvent.ToolCallChunk(0, "learn", "experience", "{}"),
            LLMStreamEvent.ToolCallChunk(1, "continue", "workspace", "{}")
        )
        f.model.roundResponses += listOf(f.completionCall("complete",
            """{"status":"done","results":[{"text":"The artifact was processed."}],"unresolved_requirements":[]}"""))
        val result = f.coordinator.coordinateTask(taskGoal = goal, additionalContext = "", durableTurn = started.active,
            turnContext = "Learn in the normal task flow.", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals(listOf("experience", "workspace", "done"), f.toolExecutor.localCalls.map { it.name })
        assertEquals(2, f.model.recordedRequests.size)
        assertTrue(f.toolExecutor.deviceCalls.isEmpty())
    }
}
