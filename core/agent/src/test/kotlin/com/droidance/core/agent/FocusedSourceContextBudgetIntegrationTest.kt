// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.context.StructuralContextTokenEstimator
import com.droidance.core.agent.tools.ToolProtocolResult
import com.droidance.core.model.llm.FakeModelClient
import com.droidance.core.model.llm.ModelClient
import com.droidance.core.model.llm.models.LLMStreamEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Exercises the production loop, including its real checkpoint and source projections. */
class FocusedSourceContextBudgetIntegrationTest {
    @Test fun sourceReviewDoesNotSummarizeHistoryExcludedFromItsRequest() = runBlocking {
        val execution = runReview(summaryFails = false)
        assertTrue("The candidate must reach the source-review boundary", execution.reviewRequested)
        assertEquals("Excluded execution history must not start a summary request: ${execution.diagnostics}",
            0, execution.summaryRequests)
        assertEquals(TurnTerminal.DONE, execution.result.terminal)
        assertTrue(execution.reviewRequestText.contains("review_feedback"))
        assertFalse(execution.reviewRequestText.contains(OLD_HISTORY))
        assertTrue(execution.actualRequestTokens in 1 until execution.hardInputTokens)
        assertTrue("The required original image remains in the bounded request", execution.imageCount > 0)
    }

    @Test fun unavailableSummaryCannotBlockAnOtherwiseBoundedOriginalSourceReview() = runBlocking {
        val execution = runReview(summaryFails = true)
        assertTrue("The candidate must reach the source-review boundary", execution.reviewRequested)
        assertEquals("An unrelated summary failure must not prevent the actual source-review request: ${execution.diagnostics}",
            TurnTerminal.DONE, execution.result.terminal)
        assertEquals(0, execution.summaryRequests)
        assertTrue(execution.reviewRequestText.contains("review_feedback"))
    }

    @Test fun actualSourceRequestAboveHardCapacityIsRejectedWithoutSendingOrSummarizing() = runBlocking {
        val execution = runReview(summaryFails = true, reviewWindow = 40_000)
        assertTrue(execution.reviewRequested)
        assertEquals(TurnTerminal.SYSTEM_ERROR, execution.result.terminal)
        assertEquals(TurnFailureReason.MODEL_PROTOCOL_ERROR, execution.result.failureReason)
        assertEquals(0, execution.summaryRequests)
        assertTrue(execution.reviewRequestText.isEmpty())
        assertTrue(execution.diagnostics.contains("isolated source request exceeds hard input capacity"))
    }

    @Test fun sourceReplanningUsesTheSameBoundedProjectionAndNormalExecutionResumesCheckpointing() = runBlocking {
        val operator = FakeModelClient()
        val client = object : ModelClient by operator {
            override fun currentContextWindowTokens() = if (operator.recordedRequests.isEmpty()) 1_000_000 else 128_000
        }
        val fixture = TaskCoordinatorTestFixture(retainedImageLimit = 0, modelClientOverride = client)
        val started = fixture.startTurn("replan-budget", "Inspect the source")
        repeat(6) { index -> fixture.recordClosedHistory(started.active.scope, "old-$index",
            if (index == 0 || index == 5) "Earlier task state" else OLD_HISTORY.repeat(6_000)) }
        fixture.model.cannedStreamEvents = listOf(LLMStreamEvent.Content("Retained task scope and source references."))
        var firstOpen = true
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "android_act" && firstOpen) {
                firstOpen = false
                ToolProtocolResult.notExecuted("OBSERVATION_RECOVERY_REQUIRED", true)
            } else if (call.name == "android_read") fixture.toolExecutor.stageObservation(
                byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), "fresh-source", "Observed changed source")
            else null
        }
        val memo = """{"record_path":"work.md","state":"working","output_constraints":[],"requested_deliverables":[],"protected_values":[],"requirements":["Inspect the source"],"steps":[]}"""
        operator.roundResponses += listOf(call("memo", "task_memo", memo),
            LLMStreamEvent.ToolCallChunk(1, "open", "android_act", """{"op":"open","app_name":"Source"}"""))
        operator.roundResponses += listOf(call("recovered-open", "android_act", """{"op":"open","app_name":"Source"}"""))
        operator.roundResponses += listOf(call("finish", "done",
            """{"status":"need_more_info","deliverables":[],"results":[],"unresolved_requirements":["Source unavailable"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}"""))
        val result = fixture.coordinator.coordinateTask(taskGoal = "Inspect the source", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        assertEquals(result.toString() + "\n" + fixture.diagnostics.takeLast(8).joinToString("\n"), TurnTerminal.NEED_MORE_INFO, result.terminal)
        assertEquals(3, operator.recordedRequests.size)
        val replan = operator.recordedRequests[1].joinToString("\n") { it.content }
        assertTrue(replan.contains("OBSERVATION_RECOVERY_REQUIRED"))
        assertFalse(replan.contains(OLD_HISTORY))
        assertFalse(replan.contains("task_compaction_summary"))
        assertTrue("Ordinary execution must retain its checkpoint mechanism", fixture.model.recordedRequests.isNotEmpty())
        val events = fixture.events(started)
        val recovered = events.single { it.eventKind == AgentEventKind.TOOL_CALL &&
            Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "recovered-open" }
        val settled = events.single { it.eventKind == AgentEventKind.TOOL_FINISHED && it.callId == recovered.callId }
        assertTrue(events.filter { it.eventKind == AgentEventKind.CONTEXT_CHECKPOINT }.all { it.sequence > settled.sequence })
        assertEquals(1, events.count { it.eventKind == AgentEventKind.CONTEXT_CHECKPOINT })
        assertTrue(operator.recordedRequests[2].any { it.content.contains("task_compaction_summary") })
        operator.recordedRequests.forEach { request ->
            val declared = request.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
            assertTrue(request.filter { it.role == com.droidance.core.model.llm.models.Role.TOOL }.all { it.toolCallId in declared })
        }
    }

    private suspend fun runReview(summaryFails: Boolean, reviewWindow: Int = 128_000): ReviewExecution {
        val operator = FakeModelClient()
        // Introduce pressure exactly at the review boundary. Earlier ordinary rounds have
        // ample capacity, isolating whether review budgets the filtered request or old history.
        val client = object : ModelClient by operator {
            override fun currentContextWindowTokens(): Int =
                if (operator.recordedRequests.size < 2) 1_000_000 else reviewWindow
        }
        val fixture = TaskCoordinatorTestFixture(reviewEnabled = true, retainedImageLimit = 0,
            modelClientOverride = client)
        val started = fixture.startTurn("focused-budget-$summaryFails", "Read the current source")
        repeat(6) { index ->
            fixture.recordClosedHistory(started.active.scope, "old-$index",
                if (index == 0 || index == 5) "Earlier task state" else OLD_HISTORY.repeat(6_000))
        }
        if (summaryFails) fixture.model.nextStreamFailure = IOException("Summary endpoint unavailable")
        else fixture.model.cannedStreamEvents = listOf(LLMStreamEvent.Content("Unused execution summary."))
        operator.roundResponses += listOf(call("read", "android_read", """{"op":"screen"}"""))
        val draft = """{"status":"done","deliverables":[],"results":[{"text":"Current source read","evidence_refs":["obs_read", "obs_initial"]}],"unresolved_requirements":[],"requirement_checks":[]}"""
        operator.roundResponses += listOf(call("draft", "done", draft))
        val candidate = CompletionReviewCandidate.from("done",
            JsonObject(Json.parseToJsonElement(draft).jsonObject - "deliverables").toString())
        operator.roundResponses += listOf(call("confirm", "done",
            draft))
        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Read the current source", additionalContext = "", durableTurn = started.active,
            turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, initialScreenRequired = true,
            initialScreenFrame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10),
            toolProfile = started.profile, mode = started.mode)
        fixture.complete(started, result)
        val reviewRequested = fixture.events(started).filter { it.eventKind == AgentEventKind.TOOL_FINISHED }
            .map { Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result }
            .any { it.diagnosticCode == CompletionReviewGate.CODE }
        val request = operator.recordedRequests.drop(2).singleOrNull().orEmpty()
        val historical = request.filter { it.historicalImage }
        assertEquals("An isolated source request must send each retained image message once",
            historical.size, historical.map { it.copy(timestamp = 0) }.distinct().size)
        val estimator = StructuralContextTokenEstimator.forSession(
            started.active.session.key.identity.transportKind.name, "llm-router-v1", "fake_model")
        return ReviewExecution(result, reviewRequested, fixture.model.recordedRequests.size,
            request.joinToString("\n") { it.content }, fixture.diagnostics.takeLast(10).joinToString("\n"),
            estimator.estimateRequest(started.active.stablePrefix.systemPrompt, operator.recordedTools.last(), request),
            reviewWindow - reviewWindow / 16 - requireNotNull(operator.recordedMaxOutputTokens.last()),
            request.sumOf { it.imageBase64List.ifEmpty { listOfNotNull(it.imageBase64) }.size })
    }

    private data class ReviewExecution(val result: TurnResult, val reviewRequested: Boolean,
        val summaryRequests: Int, val reviewRequestText: String, val diagnostics: String,
        val actualRequestTokens: Int, val hardInputTokens: Int, val imageCount: Int)

    private fun call(id: String, name: String, arguments: String) =
        LLMStreamEvent.ToolCallChunk(0, id, name, arguments)

    companion object { private const val OLD_HISTORY = "UNSENT_OLD_ASSERTION " }
}
