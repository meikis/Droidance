// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.event.AGENT_EVENT_SCHEMA_VERSION
import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AgentEventReadModelProjector
import com.droidance.core.agent.event.ThreadCreatedEventPayload
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.agent.event.TurnStartedEventPayload
import com.droidance.core.agent.event.UserTextEventPayload
import com.droidance.core.agent.event.canonicalizeJson
import com.droidance.core.agent.prompt.sha256Hex
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import com.droidance.core.knowledge.memory.TurnMemoryCandidateProjector
import com.droidance.ui.execution.EventStoreExecutionTimelinePresenter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventReplayProjectionTest {
    @Test fun programArtifactsAreExportedWithoutDeclaringSemanticSuccess() {
        val original = completedTurn()
        val ref = "sha256:" + "a".repeat(64)
        val program = event(4, AgentEventKind.PROGRAM_EXECUTION, JSON.parseToJsonElement(
            """{"phase":"snapshot","data_ref":"$ref","artifacts":[{"content_ref":"$ref","size_bytes":123,"mime_type":"text/plain"}]}"""
        ), TURN_ID)
        val terminal = original.last().copy(sequence = 5, eventId = "event-5", idempotencyKey = "key-5")
        val events = original.dropLast(1) + program + terminal
        val replay = AgentEventReplayProjection.project(events, TURN_ID)
        assertEquals(1, replay.attachments.size)
        assertEquals(ref, replay.attachments.single().contentRef)
        assertEquals(123L, replay.attachments.single().sizeBytes)
        assertTrue(replay.attachments.single().archivePath.startsWith("program/"))
        assertEquals(null, TaskAnalysisMetrics.project(events).semanticSuccess)
        assertEquals(replay, AgentEventReplayProjection.project(events, TURN_ID))
    }

    @Test fun metricsDoNotConfuseTerminalOrInjectionWithSemanticSuccess() {
        val analysis = TaskAnalysisMetrics.project(completedTurn())
        assertEquals(null, analysis.semanticSuccess)
        assertEquals(null, analysis.modelRequestDurationMs)
        assertEquals(0, analysis.textDeliveryMetricsAvailable)
        assertEquals("model", analysis.modelIdentities.single()["modelId"])
        val extra = listOf(
            event(5, AgentEventKind.TOOL_FINISHED, JSON.parseToJsonElement(
                """{"result":{"executionStatus":"SUCCEEDED","metrics":{"text_input_delivery_verified":0}}}"""
            ), TURN_ID),
            event(6, AgentEventKind.TOOL_FINISHED, JSON.parseToJsonElement(
                """{"result":{"executionStatus":"SUCCEEDED","metrics":{"text_input_delivery_verified":1}}}"""
            ), TURN_ID),
            event(7, AgentEventKind.MODEL_ROUND_COMPLETED, JSON.parseToJsonElement(
                """{"finishMetadata":{"response_output_limit_reached":"true","requested_output_tokens":"8192","request_duration_ms":"500"}}"""
            ), TURN_ID)
        )
        val measured = TaskAnalysisMetrics.project(completedTurn() + extra)
        assertEquals(1, measured.verifiedTextDeliveries)
        assertEquals(1, measured.unverifiedTextDeliveries)
        assertEquals(1, measured.outputLimitFailures)
        assertEquals(500L, measured.modelRequestDurationMs)
        assertEquals(listOf(8192), measured.requestedOutputBudgets)
        assertEquals(null, measured.semanticSuccess)
    }

    @Test
    fun completedTurnProducesStableJsonlReadModelAndHarnessArtifacts() {
        val events = completedTurn()

        val first = AgentEventReplayProjection.project(events, TURN_ID)
        val repeated = AgentEventReplayProjection.project(events, TURN_ID)

        assertEquals(first, repeated)
        assertEquals(TURN_ID, first.turnId)
        assertEquals("执行任务", first.userInput)
        assertEquals(3, first.analysisTraceJsonl.lineSequence().count(String::isNotBlank))
        assertTrue(first.analysisTraceJsonl.contains("\"payload\":{") )
        assertTrue(first.analysisTraceJsonl.contains("\"kind\":\"USER_MESSAGE\""))
        assertTrue(!first.analysisTraceJsonl.contains("payloadJson"))
        assertTrue(first.threadContextJson.contains("\"text\": \"执行任务\""))
        assertEquals(3, first.eventsJsonl.lineSequence().count(String::isNotBlank))
        assertTrue(first.readModelJson.contains("\"terminalStatus\": \"done\""))
        assertTrue(first.readModelJson.contains("\"text\": \"已完成\""))
        assertTrue(first.providerHarnessJson.contains("\"turnResult\""))
        assertTrue(first.providerHarnessJson.contains("\"eventKind\": \"TURN_COMPLETED\""))
    }

    @Test
    fun oneEventStreamProducesConsistentUiReplayAndMemoryProjections() {
        val events = completedTurn()
        val readModel = AgentEventReadModelProjector.project(events)
        val timeline = EventStoreExecutionTimelinePresenter.present(
            requireNotNull(readModel.activityByTurn[TURN_ID])
        )
        val replay = AgentEventReplayProjection.project(events, TURN_ID)
        val candidate = requireNotNull(TurnMemoryCandidateProjector.project(events, TURN_ID))

        assertTrue(timeline.isEmpty())
        assertTrue(replay.readModelJson.contains("\"operationCount\": 0"))
        assertEquals(replay.userInput, candidate.userInput)
        assertEquals("已完成", candidate.resultText)
        assertEquals(4L, candidate.sourceThroughSequence)
        assertTrue(candidate.steps.isEmpty())
    }

    private fun completedTurn(): List<AgentEventEnvelope> = listOf(
        event(
            sequence = 1,
            kind = AgentEventKind.THREAD_CREATED,
            payload = ThreadCreatedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, "Replay")
        ),
        event(
            sequence = 2,
            kind = AgentEventKind.TURN_STARTED,
            turnId = TURN_ID,
            payload = TurnStartedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                modelSessionIdentity = ModelSessionIdentity(
                    threadId = THREAD_ID,
                    providerId = "provider",
                    transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
                    transportConfigHash = "1".repeat(64),
                    authScopeHash = "2".repeat(64),
                    modelId = "model",
                    stablePrefixHash = "3".repeat(64)
                ),
                providerConfigSnapshotRef = "provider-config"
            )
        ),
        event(
            sequence = 3,
            kind = AgentEventKind.USER_MESSAGE,
            turnId = TURN_ID,
            payload = UserTextEventPayload(
                AGENT_READ_EVENT_CONTRACT_VERSION,
                "message-1",
                "执行任务"
            )
        ),
        event(
            sequence = 4,
            kind = AgentEventKind.TURN_COMPLETED,
            turnId = TURN_ID,
            payload = TurnCompletedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                status = "done",
                assistantReply = "已完成"
            )
        )
    )

    private inline fun <reified T> event(
        sequence: Long,
        kind: AgentEventKind,
        payload: T,
        turnId: String? = null
    ): AgentEventEnvelope {
        val payloadJson = canonicalizeJson(JSON.encodeToString(payload))
        return AgentEventEnvelope(
            schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
            eventId = "event-$sequence",
            eventKind = kind,
            sequence = sequence,
            occurredAt = sequence * 10,
            threadId = THREAD_ID,
            turnId = turnId,
            roundId = null,
            attemptId = null,
            executionId = null,
            callId = null,
            payloadHash = sha256Hex(payloadJson),
            payloadJson = payloadJson,
            idempotencyKey = "key-$sequence"
        )
    }

    private companion object {
        const val THREAD_ID = "thread-1"
        const val TURN_ID = "turn-1"
        val JSON = Json { encodeDefaults = true }
    }
}
