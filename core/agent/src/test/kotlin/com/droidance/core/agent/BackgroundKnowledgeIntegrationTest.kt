// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.agent.tools.OperatorToolProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackgroundKnowledgeIntegrationTest {
    @Test
    fun reflectionUsesOrdinaryModelLoopWithoutPreparingADevice() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("background-reflection", "Inspect historical task evidence and draft a method.",
            profile = OperatorToolProfile.BACKGROUND_KNOWLEDGE)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("No reusable method was found."))
        var prepared = false
        val result = fixture.coordinator.coordinateTask(
taskGoal = "Inspect historical task evidence and draft a method.",
additionalContext = "",
durableTurn = started.active,
turnContext = "Source is historical.",
expectedBackend = null,
executionStrategy = null,
toolProfile = started.profile,
mode = null,
host = object : com.droidance.core.agent.TurnHost {
    override val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? = { prepared = true; null }
}
)
        assertEquals(TurnTerminal.DIRECT_REPLY, result.terminal)
        assertEquals(1, fixture.model.recordedRequests.size)
        assertFalse(prepared)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }
}
