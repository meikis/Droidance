// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.agent.workspace.TaskExecutionPlan
import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.Role
import com.droidance.core.agent.tools.TaskMemoTool
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolRoundEarlyExitSettlementIntegrationTest {
    @Test fun protocolGuardStopSettlesEveryWithheldSuffixWithoutLosingItsTerminalReason() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("guard-stop-suffix", "Inspect the requested source")
        // Reach the semantic plan guard after structural length validation succeeds.
        val invalidReview = memo.dropLast(1) +
            """, "review":{"outcome":"progress","finding":"No source inspected yet","next_method":"Open the source","evidence_refs":[" "]}}"""
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(TaskMemoTool.definition("").parameters, invalidReview))
        assertEquals("review_requires_inspected_sources", TaskExecutionPlan.parse(invalidReview).issues.single().reason)
        repeat(3) { round ->
            fixture.model.roundResponses += listOf(
                call(0, "memo-$round", "task_memo", invalidReview),
                call(1, "open-$round", "android_act", """{"op":"open","app_name":"Source"}"""),
                call(2, "write-$round", "workspace", """{"op":"append","path":"work.md","text":"Must not run"}""")
            )
        }

        val result = coordinate(fixture, started)
        assertEquals(TurnFailureReason.GUARD_STOP, result.failureReason)
        assertEquals("TASK_PLAN_INVALID", result.stopDiagnostic)
        // This uses the production ledger and its strict all-tools-settled terminal contract.
        fixture.complete(started, result)

        val events = fixture.events(started)
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        assertTrue(fixture.toolExecutor.localCalls.isEmpty())
        val declarations = events.filter { it.eventKind == AgentEventKind.TOOL_CALL }
        assertEquals(9, declarations.size)
        declarations.forEach { declaration ->
            val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
            val finished = events.single {
                it.eventKind == AgentEventKind.TOOL_FINISHED && it.callId == declaration.callId
            }
            val receipt = Json.decodeFromString<ToolFinishedEventPayload>(finished.payloadJson).result
            assertEquals(ToolExecutionStatus.NOT_EXECUTED, receipt.executionStatus)
            assertEquals(if (call.name == "task_memo") "TASK_PLAN_INVALID" else "PRIOR_CALL_FAILED",
                receipt.diagnosticCode)
            if (call.name != "task_memo") assertTrue(receipt.outputText.contains("TASK_PLAN_INVALID"))
            assertEquals(declaration.attemptId, finished.attemptId)
            assertEquals(declaration.executionId, finished.executionId)
        }
        val terminal = Json.decodeFromString<TurnCompletedEventPayload>(
            events.single { it.eventKind == AgentEventKind.TURN_COMPLETED }.payloadJson)
        assertEquals("GUARD_STOP", terminal.diagnosticCode)
        assertFalse(events.any { it.payloadJson.contains("TURN_NOT_COMPLETED") })
    }

    @Test fun replanSettlesTheOldSuffixBeforeTheNextModelRequest() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("replan-suffix", "Inspect the requested source")
        val frame = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        fixture.toolExecutor.resultOverride = { call, _ ->
            when (call.name) {
                "android_act" -> ToolProtocolResult.notExecuted("OBSERVATION_RECOVERY_REQUIRED", true)
                "android_read" -> fixture.toolExecutor.stageObservation(
                    frame, "fresh-after-transition", "Observed the changed source")
                else -> null
            }
        }
        fixture.model.roundResponses += listOf(call(0, "memo", "task_memo", memo))
        fixture.model.roundResponses += listOf(
            call(0, "open", "android_act", """{"op":"open","app_name":"Source"}"""),
            call(1, "withheld-write", "workspace", """{"op":"append","path":"work.md","text":"Must not run"}""")
        )
        fixture.model.roundResponses += listOf(fixture.completionCall("finish",
            """{"status":"need_more_info","deliverables":[],"results":[],"unresolved_requirements":["Source unavailable"],"requirement_checks":[{"requirement_id":"r1","status":"incomplete","unresolved_index":0,"result_indices":[]}]}"""))

        val result = coordinate(fixture, started)
        fixture.complete(started, result)

        val events = fixture.events(started)
        val withheld = events.single {
            it.eventKind == AgentEventKind.TOOL_CALL &&
                Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == "withheld-write"
        }
        val receiptEvent = events.single {
            it.eventKind == AgentEventKind.TOOL_FINISHED && it.callId == withheld.callId
        }
        val receipt = Json.decodeFromString<ToolFinishedEventPayload>(receiptEvent.payloadJson).result
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, receipt.executionStatus)
        assertEquals("PRIOR_CALL_FAILED", receipt.diagnosticCode)
        assertTrue(receipt.outputText.contains("OBSERVATION_RECOVERY_REQUIRED"))
        assertEquals(withheld.attemptId, receiptEvent.attemptId)
        assertEquals(withheld.executionId, receiptEvent.executionId)
        assertTrue(receiptEvent.sequence < events.first {
            it.eventKind == AgentEventKind.MODEL_ROUND_STARTED && it.sequence > withheld.sequence
        }.sequence)
        assertFalse(fixture.toolExecutor.localCalls.any { it.name == "workspace" })
        assertEquals(1, fixture.toolExecutor.deviceCalls.count { it.name == "android_act" })
        val request = fixture.model.recordedRequests.last()
        val retained = request.filter { it.role == Role.USER }.mapNotNull {
            runCatching { Json.parseToJsonElement(it.content).jsonObject["settled_tool_result"]?.jsonObject }.getOrNull()
        }.single { it.getValue("call_id").jsonPrimitive.content == "withheld-write" }
        val sourceEvent = events.single { event -> event.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED &&
            Json.decodeFromString<DetailAttachmentAddedEventPayload>(event.payloadJson).observationId == "fresh-after-transition" }
        val source = Json.decodeFromString<DetailAttachmentAddedEventPayload>(sourceEvent.payloadJson)
        assertEquals(DetailAttachmentRole.CURRENT_OBSERVATION, source.role)
        assertEquals(withheld.callId, sourceEvent.callId)
        assertArrayEquals(frame, fixture.blobStore.read(source.contentRef, 100))
        assertTrue(sourceEvent.sequence < events.first {
            it.eventKind == AgentEventKind.MODEL_ROUND_STARTED && it.sequence > withheld.sequence
        }.sequence)
        val observationReference = "[observation id=${source.observationId} evidence_ref=${source.observationId} image_ref=${source.contentRef}]"
        assertEquals(receipt.outputText + "\n" + observationReference, retained.getValue("content").jsonPrimitive.content)
        val declared = request.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
        assertTrue(request.filter { it.role == Role.TOOL }.all { it.toolCallId in declared })
        assertEquals(TurnTerminal.NEED_MORE_INFO, result.terminal)
    }

    private suspend fun coordinate(fixture: TaskCoordinatorTestFixture, started: TaskCoordinatorTestFixture.StartedTurn) =
        fixture.coordinator.coordinateTask(taskGoal = "Inspect the requested source", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)

    private fun call(index: Int, id: String, name: String, arguments: String) =
        LLMStreamEvent.ToolCallChunk(index, id, name, arguments)

    private val memo = """{"record_path":"work.md","state":"working","output_constraints":[],"requested_deliverables":[],"protected_values":[],"requirements":["Inspect the requested source"],"steps":[]}"""
}
