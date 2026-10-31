// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import com.droidance.core.agent.event.AGENT_EVENT_SCHEMA_VERSION
import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.MODEL_ROUND_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallSource
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.agent.event.UserTextEventPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCandidateTest {
    @Test
    fun typedTurnProducesStableRedactedCandidateWithEvidenceRefs() {
        val first = completedTurn("turn-1")
        val second = completedTurn("turn-2")

        val candidate = requireNotNull(TurnMemoryCandidateProjector.project(first, "turn-1"))
        val repeated = requireNotNull(TurnMemoryCandidateProjector.project(second, "turn-2"))

        assertEquals(MEMORY_CANDIDATE_VERSION, candidate.version)
        assertEquals(candidate.candidateHash, repeated.candidateHash)
        assertEquals(listOf("android_act"), candidate.steps.map(MemoryCandidateStep::toolName))
        assertEquals(listOf("observation:screen-1"), candidate.verificationEvidenceRefs)
        assertTrue(candidate.hasVerifiedStateChange)
        assertFalse(candidate.userInput.contains("123456"))
        assertFalse(candidate.resultText.contains("secret-value"))
        assertFalse(candidate.steps.single().argumentsJson.contains("argument-secret"))
        assertTrue(candidate.steps.single().argumentsJson.contains("[REDACTED]"))
        assertTrue(candidate.userInput.contains("[REDACTED]"))
        assertTrue(candidate.resultText.contains("[REDACTED]"))
    }

    @Test
    fun unfinishedTurnDoesNotProduceMemoryCandidate() {
        assertNull(TurnMemoryCandidateProjector.project(completedTurn("turn-1").dropLast(1), "turn-1"))
    }

    @Test
    fun stateChangeWithoutCapturedEvidenceCannotBePromoted() {
        val candidate = requireNotNull(
            TurnMemoryCandidateProjector.project(
                completedTurn(
                    turnId = "turn-1",
                    evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                    observationId = null
                ),
                "turn-1"
            )
        )

        assertFalse(candidate.hasVerifiedStateChange)
        assertTrue(candidate.verificationEvidenceRefs.isEmpty())
    }

    @Test
    fun consolidationPreservesEvidenceRefsFromRepeatedVerifiedTurns() {
        val first = requireNotNull(
            TurnMemoryCandidateProjector.project(completedTurn("turn-1", observationId = "screen-1"), "turn-1")
        )
        val second = requireNotNull(
            TurnMemoryCandidateProjector.project(completedTurn("turn-2", observationId = "screen-2"), "turn-2")
        )

        val merged = JSON.decodeFromString<MemoryCandidate>(
            MemoryCandidateJsonConsolidator.merge(
                JSON.encodeToString(first),
                JSON.encodeToString(second)
            )
        )

        assertEquals(listOf("observation:screen-1", "observation:screen-2"), merged.verificationEvidenceRefs)
        assertTrue(merged.hasVerifiedStateChange)
    }

    @Test
    fun readOnlyTurnWithSourceRefProducesPromotableCandidateWithEvidenceRefs() {
        val events = listOf(
            event(
                sequence = 1,
                turnId = "turn-read",
                kind = AgentEventKind.USER_MESSAGE,
                payload = UserTextEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    "msg-1",
                    "Query SMS records"
                )
            ),
            event(
                sequence = 2,
                turnId = "turn-read",
                roundId = "round-1",
                callId = "call-read",
                kind = AgentEventKind.TOOL_CALL,
                payload = ToolCallEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    providerOrder = 0,
                    providerCallId = "provider-1",
                    name = "content_query",
                    argumentsJson = """{"uri":"content://sms"}""",
                    source = ToolCallSource.MODEL_REQUESTED,
                    binding = ToolBinding.TURN_LOCAL,
                    effect = ToolEffect.READ_ONLY,
                    contractHash = "a".repeat(64),
                    presentation = ToolPresentationSnapshot(
                        labelResourceKey = "tool.content_query",
                        category = ToolPresentationCategory.PRIMARY,
                        resultImageRole = ResultImageRole.NONE
                    ),
                    roundDetailRef = "detail-1"
                )
            ),
            event(
                sequence = 3,
                turnId = "turn-read",
                roundId = "round-1",
                callId = "call-read",
                kind = AgentEventKind.TOOL_FINISHED,
                payload = ToolFinishedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    result = ToolExecutionResult(
                        outputText = "2 messages found",
                        executionStatus = ToolExecutionStatus.SUCCEEDED,
                        evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                        sourceRef = "data_sms_789"
                    ),
                    roundDetailRef = "detail-1"
                )
            ),
            event(
                sequence = 4,
                turnId = "turn-read",
                kind = AgentEventKind.TURN_COMPLETED,
                payload = TurnCompletedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    status = "done",
                    assistantReply = "Found 2 SMS messages"
                )
            )
        )

        val candidate = requireNotNull(TurnMemoryCandidateProjector.project(events, "turn-read"))
        assertEquals(listOf("data_sms_789"), candidate.verificationEvidenceRefs)
        assertFalse(candidate.hasVerifiedStateChange)
        assertEquals(listOf("content_query"), candidate.steps.map(MemoryCandidateStep::toolName))
    }

    @Test
    fun sensitiveVerificationCodesAreRedactedFromMemoryCandidate() {
        val events = listOf(
            event(
                sequence = 1,
                turnId = "turn-otp",
                kind = AgentEventKind.USER_MESSAGE,
                payload = UserTextEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    "msg-1",
                    "我的短信验证码是 482910，请帮我处理"
                )
            ),
            event(
                sequence = 2,
                turnId = "turn-otp",
                roundId = "round-1",
                callId = "call-otp",
                kind = AgentEventKind.TOOL_CALL,
                payload = ToolCallEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    providerOrder = 0,
                    providerCallId = "provider-1",
                    name = "content_query",
                    argumentsJson = """{"uri":"content://sms","verification_code":"482910"}""",
                    source = ToolCallSource.MODEL_REQUESTED,
                    binding = ToolBinding.TURN_LOCAL,
                    effect = ToolEffect.READ_ONLY,
                    contractHash = "a".repeat(64),
                    presentation = ToolPresentationSnapshot(
                        labelResourceKey = "tool.content_query",
                        category = ToolPresentationCategory.PRIMARY,
                        resultImageRole = ResultImageRole.NONE
                    ),
                    roundDetailRef = "detail-1"
                )
            ),
            event(
                sequence = 3,
                turnId = "turn-otp",
                roundId = "round-1",
                callId = "call-otp",
                kind = AgentEventKind.TOOL_FINISHED,
                payload = ToolFinishedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    result = ToolExecutionResult(
                        outputText = "Found code: otp=482910",
                        executionStatus = ToolExecutionStatus.SUCCEEDED,
                        evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
                        sourceRef = "data_sms_otp"
                    ),
                    roundDetailRef = "detail-1"
                )
            ),
            event(
                sequence = 4,
                turnId = "turn-otp",
                kind = AgentEventKind.TURN_COMPLETED,
                payload = TurnCompletedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    status = "done",
                    assistantReply = "Your verification code 482910 has been processed"
                )
            )
        )

        val candidate = requireNotNull(TurnMemoryCandidateProjector.project(events, "turn-otp"))
        assertFalse(candidate.userInput.contains("482910"))
        assertTrue(candidate.userInput.contains("[REDACTED]"))
        assertFalse(candidate.resultText.contains("482910"))
        assertTrue(candidate.resultText.contains("[REDACTED]"))
        assertFalse(candidate.steps.single().argumentsJson.contains("482910"))
        assertTrue(candidate.steps.single().argumentsJson.contains("[REDACTED]"))
    }

    private fun completedTurn(
        turnId: String,
        evidenceStatus: ToolEvidenceStatus = ToolEvidenceStatus.CAPTURED,
        observationId: String? = "screen-1"
    ): List<AgentEventEnvelope> = listOf(
        event(
            sequence = 1,
            turnId = turnId,
            kind = AgentEventKind.USER_MESSAGE,
            payload = UserTextEventPayload(
                AGENT_READ_EVENT_CONTRACT_VERSION,
                "message-$turnId",
                "set alarm password=123456"
            )
        ),
        event(
            sequence = 2,
            turnId = turnId,
            roundId = "round-$turnId",
            callId = "call-$turnId",
            kind = AgentEventKind.TOOL_CALL,
            payload = ToolCallEventPayload(
                contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                providerOrder = 0,
                providerCallId = "provider-$turnId",
                name = "android_act",
                argumentsJson = """{"op":"open","password":"argument-secret"}""",
                source = ToolCallSource.MODEL_REQUESTED,
                binding = ToolBinding.TURN_LOCAL,
                effect = ToolEffect.STATE_CHANGING,
                contractHash = "a".repeat(64),
                presentation = ToolPresentationSnapshot(
                    labelResourceKey = "tool.android_act",
                    category = ToolPresentationCategory.PRIMARY,
                    resultImageRole = ResultImageRole.NONE
                ),
                roundDetailRef = "detail-$turnId"
            )
        ),
        event(
            sequence = 3,
            turnId = turnId,
            roundId = "round-$turnId",
            callId = "call-$turnId",
            kind = AgentEventKind.TOOL_FINISHED,
            payload = ToolFinishedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                result = ToolExecutionResult(
                    outputText = "done api_key=secret-value",
                    executionStatus = ToolExecutionStatus.SUCCEEDED,
                    evidenceStatus = evidenceStatus,
                    observationId = observationId
                ),
                roundDetailRef = "detail-$turnId"
            )
        ),
        event(
            sequence = 4,
            turnId = turnId,
            kind = AgentEventKind.TURN_COMPLETED,
            payload = TurnCompletedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                status = "done",
                assistantReply = "completed secret=secret-value"
            )
        )
    )

    private inline fun <reified T> event(
        sequence: Long,
        turnId: String,
        kind: AgentEventKind,
        payload: T,
        roundId: String? = null,
        callId: String? = null
    ): AgentEventEnvelope {
        val payloadJson = JSON.encodeToString(payload)
        return AgentEventEnvelope(
            schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
            eventId = "event-$turnId-$sequence",
            eventKind = kind,
            sequence = sequence,
            occurredAt = sequence * 10,
            threadId = "thread-1",
            turnId = turnId,
            roundId = roundId,
            attemptId = null,
            executionId = null,
            callId = callId,
            payloadHash = "b".repeat(64),
            payloadJson = payloadJson,
            idempotencyKey = "key-$turnId-$sequence"
        )
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
