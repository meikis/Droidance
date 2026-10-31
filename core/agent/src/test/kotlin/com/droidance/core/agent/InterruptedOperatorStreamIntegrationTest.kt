// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ModelRoundCompletedEventPayload
import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.ModelStreamPlan
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InterruptedOperatorStreamIntegrationTest {
    @Test fun reasoningWithoutDecisionUsesOneBoundedToolFreeClosingRequest() = runBlocking {
        val model = FakeModelClient()
        val interrupted = object : ModelClient by model {
            override suspend fun streamChat(messages: List<ChatMessage>, tools: List<ToolDefinition>, systemPrompt: String?,
                hasImage: Boolean, enableComputerTool: Boolean, computerFrameSize: Pair<Int, Int>?,
                maxOutputTokens: Int?, requestTimeoutMs: Long?, requestCancellationGeneration: Long?,
                requestPlan: ModelStreamPlan?, isolatedContext: Boolean, reasoningEffort: ReasoningEffort): Flow<LLMStreamEvent> {
                val response = model.streamChat(messages, tools, systemPrompt, hasImage, enableComputerTool, computerFrameSize,
                    maxOutputTokens, requestTimeoutMs, requestCancellationGeneration, requestPlan, isolatedContext, reasoningEffort)
                val first = model.recordedRequests.size == 1
                return flow {
                    response.collect { emit(it) }
                    if (first) throw ModelStreamTimeoutException("first_output")
                }
            }
        }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = interrupted, stoppedReplyEnabled = true)
        val started = fixture.startTurn("reasoning-timeout", "Inspect the requested state", activate = false)
        model.roundResponses += listOf(LLMStreamEvent.ReasoningChunk("private incomplete reasoning"))
        model.roundResponses += listOf(LLMStreamEvent.Content("I could not finish the requested check. No device actions were executed."))
        val result = fixture.coordinator.coordinateTask(taskGoal = "Inspect the requested state", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        assertEquals(TurnFailureReason.MODEL_TIMEOUT, result.failureReason)
        assertEquals(2, model.recordedRequests.size)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        val rounds = fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .map { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson) }
        assertTrue(rounds.any { it.terminalDiagnosticCode == "LLM_NO_ACTIONABLE_OUTPUT" })
        assertTrue(rounds.any { it.finishMetadata["phase"] == "stopped_task_reply" })
        assertFalse(model.recordedRequests.last().any { "private incomplete reasoning" in it.content })
    }

    @Test fun timeoutDiscardsPartialCallsAndRetriesWithoutAFalseSchemaRepair() =
        interruptedStream("LLM_TIMEOUT_RETRY") { withTimeout(1L) { delay(100L) } }

    @Test fun transportTimeoutDiscardsPartialCallsAndRetriesWithoutReplayingEffects() =
        interruptedStream("TRANSPORT_TIMEOUT_RETRY") { throw java.io.InterruptedIOException("timeout") }

    @Test fun disconnectedSocketDiscardsPartialCallsAndRetriesWithoutReplayingEffects() =
        interruptedStream("NETWORK_RETRY") { throw java.net.SocketException("connection reset") }

    private fun interruptedStream(expectedDiagnostic: String, interrupt: suspend () -> Unit) = runBlocking {
        val model = FakeModelClient()
        val interrupted = object : ModelClient by model {
            override suspend fun streamChat(
                messages: List<ChatMessage>, tools: List<ToolDefinition>, systemPrompt: String?,
                hasImage: Boolean, enableComputerTool: Boolean, computerFrameSize: Pair<Int, Int>?,
                maxOutputTokens: Int?, requestTimeoutMs: Long?, requestCancellationGeneration: Long?,
                requestPlan: ModelStreamPlan?, isolatedContext: Boolean, reasoningEffort: ReasoningEffort
            ): Flow<LLMStreamEvent> {
                val response = model.streamChat(messages, tools, systemPrompt, hasImage,
                    enableComputerTool, computerFrameSize, maxOutputTokens, requestTimeoutMs,
                    requestCancellationGeneration, requestPlan, isolatedContext, reasoningEffort)
                val firstRequest = model.recordedRequests.size == 1
                return flow {
                    response.collect { emit(it) }
                    if (firstRequest) interrupt()
                }
            }
        }
        val fixture = TaskCoordinatorTestFixture(modelClientOverride = interrupted)
        val started = fixture.startTurn("interrupted-stream", "Retain the fact", activate = false)
        model.roundResponses += listOf(
            LLMStreamEvent.Content("unfinished opening"),
            LLMStreamEvent.ToolCallChunk(0, "partial", "workspace", "{\"op\":")
        )
        model.roundResponses += listOf(LLMStreamEvent.ToolCallChunk(0, "complete", "workspace",
            """{"op":"write","path":"work.md","text":"retained fact"}"""))
        model.roundResponses += listOf(fixture.completionCall("finish",
            """{"status":"need_more_info","deliverables":[],"results":[{"text":"Retained fact","evidence_refs":[]}],"unresolved_requirements":["Confirm the retained fact"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}"""))

        fixture.coordinator.coordinateTask(taskGoal = "Retain the fact", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)

        val writes = fixture.toolExecutor.localCalls.filter { it.name == "workspace" }
        assertEquals(1, writes.size)
        assertTrue("retained fact" in writes.single().arguments)
        assertEquals(3, model.recordedRequests.size)
        val events = fixture.events(started)
        val rounds = events.filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .map { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson) }
        val timeout = rounds.single { it.terminalDiagnosticCode == expectedDiagnostic }
        assertEquals("1", timeout.finishMetadata["discarded_partial_tool_calls"])
        assertEquals("18", timeout.finishMetadata["discarded_partial_content_chars"])
        assertTrue(timeout.publicBlocks.isEmpty())
        assertEquals(2, events.count { it.eventKind == AgentEventKind.TOOL_CALL })
        assertFalse(events.any { "ARGUMENT_SCHEMA_MISMATCH" in it.payloadJson })
        assertFalse(model.recordedRequests[1].any { "unfinished opening" in it.content })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
    }
}
