// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AGENT_EVENT_SCHEMA_VERSION
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallRejectedEventPayload
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.ToolCallSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoppedTaskToolEvidenceTest {
    private val threadId = "stopped-failures-thread"
    private val turnId = "stopped-failures-turn"

    @Test fun rawReadSurvivesLaterRejectedCompletionsWithoutTurningItIntoGoalSuccess() {
        val events = buildList {
            addAll(settledSuccess(1, "shell", """{"stdout":"Physical size: 1080x2400","exit_code":0}"""))
            (1..3).forEach { addAll(rejectedFailure(2L * it + 1, "done", "INVALID_TOOL_ARGUMENTS", "schema repair required")) }
            addAll(settledFailure(9, "shell", "READ_FAILED", "later read unavailable"))
        }
        val retained = StoppedTaskToolEvidence.retainedReceipts(events, 1600)
        assertEquals(1, retained.size)
        val row = retained.single().jsonObject
        assertEquals("shell", row.getValue("tool").jsonPrimitive.content)
        assertTrue(row.getValue("receipt_excerpt").jsonPrimitive.content.contains("1080x2400"))
        assertEquals("call-shell-1", row.getValue("call_id").jsonPrimitive.content)
        assertEquals("true", row.getValue("execution_success_is_not_goal_success").jsonPrimitive.content)
        assertTrue(retained.toString().length <= 1600)
        assertEquals(0, StoppedTaskToolEvidence.retainedReceipts(events, 2).size)
    }

    @Test fun distinctReadsThroughTheSameToolRetainBothValuesAfterDeliveryRepairsFail() {
        val events = buildList {
            addAll(settledSuccess(1, "shell", """{"stdout":"Physical size: 1080x2400"}""", """{"command":"wm size"}"""))
            addAll(settledSuccess(3, "shell", """{"stdout":"Override density: 480"}""", """{"command":"wm density"}"""))
            addAll(rejectedFailure(5, "done", "COMPLETION_EVIDENCE_REQUIRED", "completion delivery blocked"))
        }
        val retained = StoppedTaskToolEvidence.retainedReceipts(events, 4000)
        assertEquals(2, retained.size)
        assertTrue(retained.toString().contains("1080x2400"))
        assertTrue(retained.toString().contains("480"))
    }

    @Test
    fun keepsChronologicalSuccessAndFailureReceiptsWithToolNamesAndReasons() {
        val events = buildList {
            addAll(settledFailure(1, "old-tool", "OLD_FAILURE"))
            addAll(settledFailure(3, "workspace", "WORKSPACE_ERROR", "workspace write failed"))
            addAll(settledSuccess(5, "successful-tool"))
            addAll(rejectedFailure(7, "screen_act", "WORKSPACE_REVIEW_REQUIRED", "browse review required"))
            (1..4).forEach { index ->
                addAll(settledFailure(9L + index * 2L, "other-$index", "OTHER_FAILURE_$index"))
            }
        }

        val projected = StoppedTaskToolEvidence.project(events, maxChars = 20_000)
        assertEquals(8, projected.size)
        val rows = projected.map { it.jsonObject }
        assertEquals(
            listOf("old-tool", "workspace", "successful-tool", "screen_act", "other-1", "other-2", "other-3", "other-4"),
            rows.map { it.getValue("tool").jsonPrimitive.content }
        )
        assertEquals(
            listOf(
                "OLD_FAILURE",
                "WORKSPACE_ERROR",
                null,
                "WORKSPACE_REVIEW_REQUIRED",
                "OTHER_FAILURE_1",
                "OTHER_FAILURE_2",
                "OTHER_FAILURE_3",
                "OTHER_FAILURE_4"
            ),
            rows.map { it.getValue("diagnostic_code").jsonPrimitive.contentOrNull }
        )
        assertTrue(rows[1].getValue("receipt_excerpt").jsonPrimitive.content.contains("workspace write failed"))
        assertTrue(rows[3].getValue("receipt_excerpt").jsonPrimitive.content.contains("browse review required"))
        assertEquals("call-successful-tool-5", rows[2].getValue("call_id").jsonPrimitive.content)
    }

    @Test
    fun maxCharsDropsOldestRowsButKeepsNewestRecoveryFact() {
        val events = buildList {
            addAll(settledFailure(1, "old-tool", "OLD_FAILURE", "old".repeat(400)))
            addAll(settledSuccess(3, "recovered-tool"))
            addAll(rejectedFailure(5, "new-tool", "NEW_FAILURE", "new"))
        }

        val all = StoppedTaskToolEvidence.project(events, maxChars = 100_000)
        val twoRowsBudget = kotlinx.serialization.json.JsonArray(all.takeLast(2)).toString().length
        val projected = StoppedTaskToolEvidence.project(events, maxChars = twoRowsBudget)
        val rows = projected.map { it.jsonObject }
        assertTrue(projected.toString().length <= twoRowsBudget)
        assertEquals(
            listOf("recovered-tool", "new-tool"),
            rows.map { it.getValue("tool").jsonPrimitive.content }
        )

        val oneRowBudget = kotlinx.serialization.json.JsonArray(listOf(all.last())).toString().length
        val latestOnly = StoppedTaskToolEvidence.project(events, maxChars = oneRowBudget)
        assertTrue(latestOnly.toString().length <= oneRowBudget)
        assertEquals(listOf("new-tool"), latestOnly.map { it.jsonObject.getValue("tool").jsonPrimitive.content })
        assertEquals("NEW_FAILURE", latestOnly.single().jsonObject.getValue("diagnostic_code").jsonPrimitive.content)
    }

    private fun settledFailure(
        sequence: Long,
        toolName: String,
        diagnosticCode: String,
        receipt: String = "failed"
    ): List<AgentEventEnvelope> {
        val callId = "call-$toolName-$sequence"
        val roundId = "round-$sequence"
        val result = ToolExecutionResult(
            outputText = receipt,
            executionStatus = ToolExecutionStatus.FAILED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
            diagnosticCode = diagnosticCode
        )
        return listOf(
            callEvent(sequence, callId, roundId, toolName),
            event(
                sequence + 1,
                AgentEventKind.TOOL_FINISHED,
                callId,
                roundId,
                Json.encodeToString(ToolFinishedEventPayload(1, result, roundId)),
                "finish-$callId"
            )
        )
    }

    private fun rejectedFailure(
        sequence: Long,
        toolName: String,
        diagnosticCode: String,
        receipt: String
    ): List<AgentEventEnvelope> {
        val callId = "call-$toolName-$sequence"
        val roundId = "round-$sequence"
        val result = ToolExecutionResult(
            outputText = receipt,
            executionStatus = ToolExecutionStatus.NOT_EXECUTED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
            diagnosticCode = diagnosticCode
        )
        return listOf(
            callEvent(sequence, callId, roundId, toolName),
            event(
                sequence + 1,
                AgentEventKind.TOOL_CALL_REJECTED,
                callId,
                roundId,
                Json.encodeToString(ToolCallRejectedEventPayload(1, result, null, null, roundId)),
                "rejected-$callId"
            )
        )
    }

    private fun settledSuccess(sequence: Long, toolName: String, receipt: String = "success", arguments: String = "{}"): List<AgentEventEnvelope> {
        val callId = "call-$toolName-$sequence"
        val roundId = "round-$sequence"
        val result = ToolExecutionResult(
            outputText = receipt,
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
        )
        return listOf(
            callEvent(sequence, callId, roundId, toolName, arguments),
            event(
                sequence + 1,
                AgentEventKind.TOOL_FINISHED,
                callId,
                roundId,
                Json.encodeToString(ToolFinishedEventPayload(1, result, roundId)),
                "finish-$callId"
            )
        )
    }

    private fun callEvent(
        sequence: Long,
        callId: String,
        roundId: String,
        toolName: String,
        arguments: String = "{}"
    ) = event(
        sequence,
        AgentEventKind.TOOL_CALL,
        callId,
        roundId,
        Json.encodeToString(
            ToolCallEventPayload(
                contractVersion = 1,
                providerOrder = 0,
                providerCallId = callId,
                name = toolName,
                argumentsJson = arguments,
                source = ToolCallSource.MODEL_REQUESTED,
                binding = ToolBinding.TURN_LOCAL,
                effect = ToolEffect.STATE_CHANGING,
                contractHash = "a".repeat(64),
                presentation = ToolPresentationSnapshot(
                    labelResourceKey = "tool.$toolName",
                    category = ToolPresentationCategory.PRIMARY,
                    resultImageRole = ResultImageRole.NONE
                ),
                roundDetailRef = roundId
            )
        ),
        "call-$callId"
    )

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        callId: String,
        roundId: String,
        payload: String,
        key: String
    ) = AgentEventEnvelope(
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventId = "event-$sequence",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence,
        threadId = threadId,
        turnId = turnId,
        roundId = roundId,
        attemptId = null,
        executionId = null,
        callId = callId,
        payloadHash = "a".repeat(64),
        payloadJson = payload,
        idempotencyKey = key
    )
}
