// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ModelRoundCompletedEventPayload
import com.droidance.core.agent.tools.OperatorToolProfile
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ReasoningEffort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReasoningControlIntegrationTest {
    @Test fun ordinaryToolResponsesSelectTheFollowingEffortWithoutASeparatePlanningRound() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Inspect the retained working record"
        val started = fixture.startTurn("reasoning-control", goal, profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        repeat(2) { index ->
            fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read-$index", "workspace",
                """{"op":"read","path":"work.md","next_reasoning_effort":"low"}"""))
        }
        fixture.model.roundResponses += listOf(fixture.completionCall("finish",
            """{"status":"done","deliverables":[],"results":[{"text":"Working record inspected.","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}"""))
        val result = fixture.coordinator.coordinateTask(taskGoal = goal, additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(listOf(ReasoningEffort.MEDIUM, ReasoningEffort.MEDIUM, ReasoningEffort.LOW),
            fixture.model.recordedReasoningEfforts)
        assertTrue(fixture.toolExecutor.localCalls.none { "next_reasoning_effort" in it.arguments })
        val rounds = fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .map { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson) }
        assertEquals("held_by_change_interval", rounds[0].finishMetadata["reasoning_suggestion_status"])
        assertEquals("accepted", rounds[1].finishMetadata["reasoning_suggestion_status"])
        assertTrue(fixture.model.recordedRequests[2].any { "cumulative_execution_facts" in it.content })
        assertTrue(fixture.model.recordedRequests.all { request -> request.any { "<reasoning_control>" in it.content } })
    }

    @Test fun deliveredResultsAreNamedSoTheCompletionDoesNotRestateThem() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val goal = "Report the first part, then finish"
        val started = fixture.startTurn("delivered-results", goal, profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "partial", "report_result",
            """{"text":"## First part ranking\n\n1. Entry A"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish",
            """{"status":"done","deliverables":[],"results":[{"text":"Remaining conclusion.","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}"""))
        val result = fixture.coordinator.coordinateTask(taskGoal = goal, additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertFalse(fixture.model.recordedRequests[0].any { "<delivered_results>" in it.content })
        val next = fixture.model.recordedRequests[1].joinToString("\n") { it.content }
        assertTrue(next, next.contains("<delivered_results>[\"First part ranking\"]"))
    }

    @Test fun providerDefaultReasoningOmitsEffortHintsAndTheirContext() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        fixture.model.reasoningEfforts = emptySet()
        val goal = "Inspect the retained working record"
        val started = fixture.startTurn("reasoning-defaults", goal, profile = OperatorToolProfile.LOCAL_KNOWLEDGE)
        fixture.model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "read", "workspace",
            """{"op":"read","path":"work.md"}"""))
        fixture.model.roundResponses += listOf(fixture.completionCall("finish",
            """{"status":"done","deliverables":[],"results":[{"text":"Working record inspected.","evidence_refs":[]}],"unresolved_requirements":[],"requirement_checks":[]}"""))
        val result = fixture.coordinator.coordinateTask(taskGoal = goal, additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertTrue(fixture.model.recordedTools.flatten().none { ReasoningEffortProtocol.ARGUMENT in it.parameters })
        assertTrue(fixture.model.recordedRequests.flatten().none {
            "<reasoning_control>" in it.content || "<previous_reasoning_transport>" in it.content
        })
        assertFalse(started.active.stablePrefix.systemPrompt.contains("You choose the reasoning effort of your next decision"))
    }
}
