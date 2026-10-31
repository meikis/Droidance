// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionIncidentProjectorTest {
    @Test fun deliveredActionWithUnknownGoalEvidenceIsNotAFalseExecutionFailure() {
        val failed = callWithRejected(1, "stale", "tap", "PRE_ACTION_OBSERVATION_CHANGED")
        val delivered = callWithFinished(10, "delivered", "tap", status = ToolExecutionStatus.SUCCEEDED,
            evidence = ToolEvidenceStatus.UNKNOWN, diagnostic = "ACTION_DELIVERED",
            actionEvidence = ToolActionEvidence(ActionDeliveryEvidence.ACTION_DELIVERED,
                StateTransitionEvidence.UNKNOWN, GoalProgressEvidence.NOT_PROVEN))
        val rows = ExecutionIncidentProjector.project(failed + delivered, THREAD, TURN)
        assertEquals(1, rows.size)
        assertEquals("PRE_ACTION_OBSERVATION_CHANGED", rows.single().string("diagnostic_code"))
        assertEquals("event-delivered-tool_finished", rows.single().string("later_delivery_ref"))
        assertEquals("HISTORICAL_UNRESOLVED", rows.single().string("status"))
        assertTrue(ExecutionIncidentProjector.project(delivered, THREAD, TURN).isEmpty())
        val verifiedRead = callWithFinished(10, "read", "tap", status = ToolExecutionStatus.SUCCEEDED,
            evidence = ToolEvidenceStatus.CAPTURED, diagnostic = null)
        assertFalse("A successful receipt without delivery evidence is not a later delivery",
            ExecutionIncidentProjector.project(failed + verifiedRead, THREAD, TURN).single().containsKey("later_delivery_ref"))
    }
    @Test
    fun projectsOnlyModelRequestedDeviceBoundFinishedFailuresWithStableReferences() {
        val events = callWithFinished(
            baseSequence = 1,
            callId = "device-failure",
            tool = "tap",
            binding = ToolBinding.DEVICE_BOUND,
            source = ToolCallSource.MODEL_REQUESTED,
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED"
        ) + callWithFinished(
            baseSequence = 10,
            callId = "local-failure",
            tool = "local_read",
            binding = ToolBinding.TURN_LOCAL,
            source = ToolCallSource.MODEL_REQUESTED,
            status = ToolExecutionStatus.FAILED,
            diagnostic = "LOCAL_FAILED"
        ) + callWithFinished(
            baseSequence = 20,
            callId = "automatic-failure",
            tool = "automatic_probe",
            binding = ToolBinding.DEVICE_BOUND,
            source = ToolCallSource.HARNESS_AUTOMATIC,
            status = ToolExecutionStatus.FAILED,
            diagnostic = "AUTOMATIC_FAILED"
        )

        val rows = ExecutionIncidentProjector.project(events, THREAD, TURN)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("event-device-failure-tool_finished", row.string("incident_ref"))
        assertEquals("device-failure", row.string("call_ref"))
        assertEquals("tap", row.string("tool"))
        assertEquals("EXECUTION_FAILED", row.string("category"))
        assertEquals("FAILED", row.string("execution_status"))
        assertEquals("TAP_FAILED", row.string("diagnostic_code"))
        assertEquals("attempt-a", row.string("attempt_ref"))
        assertEquals("execution-a", row.string("execution_ref"))
        assertEquals(1L, row.long("consecutive_failures"))
    }

    @Test
    fun queryResultBarrierRejectionDoesNotBecomeAnExecutionIncident() {
        val events = callWithRejected(
            baseSequence = 1,
            callId = "blocked-action",
            tool = "tap",
            diagnostic = "QUERY_RESULT_REQUIRED"
        )

        assertTrue(ExecutionIncidentProjector.project(events, THREAD, TURN).isEmpty())
    }

    @Test
    fun nonQueryDeviceRejectionRemainsVisibleAsNotExecuted() {
        val events = callWithRejected(
            baseSequence = 1,
            callId = "invalid-action",
            tool = "tap",
            diagnostic = "ARGUMENT_SCHEMA_MISMATCH"
        )

        val row = ExecutionIncidentProjector.project(events, THREAD, TURN).single()

        assertEquals("NOT_EXECUTED", row.string("category"))
        assertEquals("NOT_EXECUTED", row.string("execution_status"))
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", row.string("diagnostic_code"))
        assertEquals(1L, row.long("consecutive_failures"))
    }

    @Test
    fun unknownActionEvidenceIsClassifiedAsUnknownEffect() {
        val events = callWithFinished(
            baseSequence = 1,
            callId = "uncertain-action",
            tool = "tap",
            status = ToolExecutionStatus.SUCCEEDED,
            evidence = ToolEvidenceStatus.UNKNOWN,
            diagnostic = "POST_ACTION_UNCONFIRMED",
            actionEvidence = ToolActionEvidence(
                delivery = ActionDeliveryEvidence.UNKNOWN,
                transition = StateTransitionEvidence.UNKNOWN,
                goalProgress = GoalProgressEvidence.NOT_PROVEN
            )
        )

        val row = ExecutionIncidentProjector.project(events, THREAD, TURN).single()

        assertEquals("EFFECT_UNKNOWN", row.string("category"))
        assertEquals("SUCCEEDED", row.string("execution_status"))
        assertEquals("UNKNOWN", row.string("evidence_status"))
    }

    @Test
    fun unrelatedSuccessDoesNotClearFailureAndSameScopeSuccessResetsNextCount() {
        val events = callWithFinished(
            baseSequence = 1,
            callId = "tap-1",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED"
        ) + callWithFinished(
            baseSequence = 10,
            callId = "scroll-1",
            tool = "scroll",
            status = ToolExecutionStatus.SUCCEEDED,
            diagnostic = null
        ) + callWithFinished(
            baseSequence = 20,
            callId = "tap-2",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED"
        ) + callWithFinished(
            baseSequence = 30,
            callId = "tap-recovery",
            tool = "tap",
            status = ToolExecutionStatus.SUCCEEDED,
            diagnostic = null
        ) + callWithFinished(
            baseSequence = 40,
            callId = "tap-3",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED"
        )

        val rows = ExecutionIncidentProjector.project(events, THREAD, TURN)

        assertEquals(3, rows.size)
        assertEquals(listOf(1L, 2L, 1L), rows.map { it.long("consecutive_failures") })
        assertEquals(listOf("tap-1", "tap-2", "tap-3"), rows.map { it.string("call_ref") })
    }

    @Test
    fun failuresInDifferentExecutionScopesDoNotShareCounts() {
        val events = callWithFinished(
            baseSequence = 1,
            callId = "first-scope",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED",
            attemptId = "attempt-a",
            executionId = "execution-a"
        ) + callWithFinished(
            baseSequence = 10,
            callId = "second-scope",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED",
            attemptId = "attempt-b",
            executionId = "execution-b"
        )

        val rows = ExecutionIncidentProjector.project(events, THREAD, TURN)

        assertEquals(listOf(1L, 1L), rows.map { it.long("consecutive_failures") })
        assertEquals(listOf("attempt-a", "attempt-b"), rows.map { it.string("attempt_ref") })
    }

    @Test
    fun interruptedAndCanceledActionsRemainEffectUnknown() {
        val events = callWithFinished(
            baseSequence = 1,
            callId = "interrupted",
            tool = "tap",
            status = ToolExecutionStatus.INTERRUPTED,
            diagnostic = "TURN_INTERRUPTED"
        ) + callWithFinished(
            baseSequence = 10,
            callId = "canceled",
            tool = "tap",
            status = ToolExecutionStatus.CANCELED,
            diagnostic = "TURN_CANCELED"
        )

        val rows = ExecutionIncidentProjector.project(events, THREAD, TURN)

        assertEquals(listOf("EFFECT_UNKNOWN", "EFFECT_UNKNOWN"), rows.map { it.string("category") })
    }

    @Test
    fun duplicateEventIdsAndRepeatedSettlementDoNotInflateIncidentCount() {
        val original = callWithFinished(
            baseSequence = 1,
            callId = "once",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED"
        )
        val duplicate = original.map { it.copy(sequence = it.sequence + 100, idempotencyKey = "duplicate-${it.eventId}") }
        val repeatedSettlement = original.last().copy(
            eventId = "event-once-finished-repeated",
            sequence = 50,
            idempotencyKey = "repeated-settlement"
        )

        val rows = ExecutionIncidentProjector.project(original + duplicate + repeatedSettlement, THREAD, TURN)

        assertEquals(1, rows.size)
        assertEquals(1L, rows.single().long("consecutive_failures"))
    }

    @Test
    fun completedTurnArchivesIncidentsAndPageKeepsOverflowRetrievable() {
        val failures = (0 until 3).flatMap { index ->
            callWithFinished(
                baseSequence = index * 10L + 1L,
                callId = "failure-$index",
                tool = "tap-$index",
                status = ToolExecutionStatus.FAILED,
                diagnostic = "FAILED_$index"
            )
        }
        val completed = event(
            sequence = 100,
            kind = AgentEventKind.TURN_COMPLETED,
            callId = null,
            payloadJson = Json.encodeToString(
                TurnCompletedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    status = "ERROR",
                    assistantReply = "done"
                )
            ),
            attemptId = null,
            executionId = null
        )

        val all = failures + completed
        val firstPage = ExecutionIncidentProjector.page(all, THREAD, TURN, limit = 2)
        assertEquals(3L, firstPage.long("total"))
        assertEquals(2, firstPage.getValue("items").jsonArray.size)
        assertEquals(1L, firstPage.long("remaining"))
        assertEquals("ARCHIVED_UNRESOLVED", firstPage.getValue("items").jsonArray[0].jsonObject.string("status"))

        val nextAfter = firstPage.getValue("next_after").jsonPrimitive.long
        val secondPage = ExecutionIncidentProjector.page(all, THREAD, TURN, after = nextAfter, limit = 2)
        assertEquals(1, secondPage.getValue("items").jsonArray.size)
        assertEquals(0L, secondPage.long("remaining"))
        assertTrue(secondPage.getValue("next_after").toString() == "null")
    }

    @Test
    fun incidentProjectionDoesNotCopyArgumentsOrFailureBody() {
        val secret = "secret-value-that-must-not-be-projected"
        val events = callWithFinished(
            baseSequence = 1,
            callId = "redacted",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED",
            argumentsJson = "{\"password\":\"$secret\"}",
            outputText = "backend body $secret"
        )

        val rowText = ExecutionIncidentProjector.project(events, THREAD, TURN).single().toString()

        assertFalse(rowText.contains(secret))
        assertFalse(rowText.contains("password"))
        assertTrue(rowText.contains("TAP_FAILED"))
    }

    @Test
    fun continuationContextRetainsAnOldIncidentAfterRecentCallWindowMovesOn() {
        val oldFailure = callWithFinished(
            baseSequence = 1,
            callId = "old-failure",
            tool = "tap",
            status = ToolExecutionStatus.FAILED,
            diagnostic = "TAP_FAILED"
        )
        val laterCalls = (0 until 9).flatMap { index ->
            callWithFinished(
                baseSequence = 10L + index * 10L,
                callId = "later-$index",
                tool = "read-$index",
                status = ToolExecutionStatus.SUCCEEDED,
                diagnostic = null
            )
        }

        val rendered = TaskContinuationContext.render(
            oldFailure + laterCalls + event(
                sequence = 200,
                kind = AgentEventKind.CONTEXT_CHECKPOINT,
                callId = null,
                payloadJson = "{}",
                attemptId = null,
                executionId = null
            ),
            THREAD,
            TURN
        )

        assertTrue(rendered.contains("execution_incidents"))
        assertTrue(rendered.contains("old-failure"))
        assertTrue(rendered.contains("HISTORICAL_UNRESOLVED"))
    }

    private fun callWithFinished(
        baseSequence: Long,
        callId: String,
        tool: String,
        binding: ToolBinding = ToolBinding.DEVICE_BOUND,
        source: ToolCallSource = ToolCallSource.MODEL_REQUESTED,
        status: ToolExecutionStatus,
        evidence: ToolEvidenceStatus = if (status == ToolExecutionStatus.SUCCEEDED) {
            ToolEvidenceStatus.CAPTURED
        } else {
            ToolEvidenceStatus.UNKNOWN
        },
        diagnostic: String?,
        actionEvidence: ToolActionEvidence? = null,
        observationId: String? = null,
        attemptId: String? = "attempt-a",
        executionId: String? = "execution-a",
        argumentsJson: String = "{}",
        outputText: String = "result"
    ): List<AgentEventEnvelope> {
        val result = ToolExecutionResult(
            outputText = outputText,
            executionStatus = status,
            evidenceStatus = evidence,
            observationId = observationId,
            diagnosticCode = diagnostic,
            actionEvidence = actionEvidence
        )
        return listOf(
            declaration(
                sequence = baseSequence,
                callId = callId,
                tool = tool,
                binding = binding,
                source = source,
                attemptId = attemptId,
                executionId = executionId,
                argumentsJson = argumentsJson
            ),
            event(
                sequence = baseSequence + 1,
                kind = AgentEventKind.TOOL_FINISHED,
                callId = callId,
                payloadJson = Json.encodeToString(ToolFinishedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    result = result,
                    roundDetailRef = "round-detail"
                )),
                attemptId = attemptId,
                executionId = executionId
            )
        )
    }

    private fun callWithRejected(
        baseSequence: Long,
        callId: String,
        tool: String,
        diagnostic: String,
        attemptId: String? = "attempt-a",
        executionId: String? = "execution-a"
    ): List<AgentEventEnvelope> = listOf(
        declaration(
            sequence = baseSequence,
            callId = callId,
            tool = tool,
            binding = ToolBinding.DEVICE_BOUND,
            source = ToolCallSource.MODEL_REQUESTED,
            attemptId = attemptId,
            executionId = executionId
        ),
        event(
            sequence = baseSequence + 1,
            kind = AgentEventKind.TOOL_CALL_REJECTED,
            callId = callId,
            payloadJson = Json.encodeToString(ToolCallRejectedEventPayload(
                contractVersion = 1,
                result = ToolExecutionResult(
                    outputText = "rejected",
                    executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    diagnosticCode = diagnostic
                ),
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                roundDetailRef = "round-detail"
            )),
            attemptId = attemptId,
            executionId = executionId
        )
    )

    private fun declaration(
        sequence: Long,
        callId: String,
        tool: String,
        binding: ToolBinding,
        source: ToolCallSource,
        attemptId: String?,
        executionId: String?,
        argumentsJson: String = "{}"
    ): AgentEventEnvelope = event(
        sequence = sequence,
        kind = AgentEventKind.TOOL_CALL,
        callId = callId,
        payloadJson = Json.encodeToString(ToolCallEventPayload(
            contractVersion = 1,
            providerOrder = 0,
            providerCallId = callId,
            name = tool,
            argumentsJson = argumentsJson,
            source = source,
            binding = binding,
            effect = ToolEffect.STATE_CHANGING,
            contractHash = HASH,
            presentation = ToolPresentationSnapshot(
                labelResourceKey = "tool.test",
                category = ToolPresentationCategory.PRIMARY,
                resultImageRole = ResultImageRole.BEFORE_AND_AFTER
            ),
            roundDetailRef = "round-detail"
        )),
        attemptId = attemptId,
        executionId = executionId
    )

    private fun event(
        sequence: Long,
        kind: AgentEventKind,
        callId: String?,
        payloadJson: String,
        attemptId: String?,
        executionId: String?
    ): AgentEventEnvelope = AgentEventEnvelope(
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventId = "event-${callId ?: kind.name.lowercase()}-${kind.name.lowercase()}",
        eventKind = kind,
        sequence = sequence,
        occurredAt = sequence,
        threadId = THREAD,
        turnId = TURN,
        roundId = "round",
        attemptId = attemptId,
        executionId = executionId,
        callId = callId,
        payloadHash = HASH,
        payloadJson = payloadJson,
        idempotencyKey = "key-$sequence-${kind.name}"
    )

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

    private companion object {
        const val THREAD = "thread"
        const val TURN = "turn"
        val HASH = "a".repeat(64)
    }
}
