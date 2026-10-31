// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.LLMStreamEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersistentTaskRoundContextTest {
    @Test fun liveClockAndQueuedHintsReachTheNextDecisionInTheSameOperator() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("live-context", "Continue the authorized goal")
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "open", "android_act",
            """{"op":"launch","package_name":"example.target"}"""))
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("Progress retained."))
        var decisions = 0
        fixture.coordinator.coordinateTask(
taskGoal = "Continue the authorized goal",
additionalContext = "",
durableTurn = started.active,
turnContext = "",
expectedBackend = started.expectedBackend,
executionStrategy = started.executionStrategy,
mode = started.mode,
host = object : com.droidance.core.agent.TurnHost {
    override val roundContext: () -> String = {
                decisions++
                """{"persistent_task_clock":{"remaining_ms":${10_000 - decisions * 1000}},"untrusted_wake_signals":["signal-$decisions"]}"""
            }
}
)
        assertTrue(fixture.model.recordedRequests.size >= 2)
        val first = fixture.model.recordedRequests[0].joinToString("\n") { it.content }
        val second = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
        assertTrue(first, first.contains("signal-1"))
        assertTrue(second, second.contains("signal-2"))
        assertTrue(second.contains("8000"))
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "android_act" })
    }
}
