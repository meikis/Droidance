// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.ModelRoundScope
import com.droidance.core.agent.event.PreparedModelRoundCommit
import com.droidance.core.agent.event.PreparedModelRoundStart
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.event.TurnResult
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.prepareInterruptedExecution
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventReplayCancellationOriginTest {
    private val stableHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun cancelledTurnKeepsCancellationOriginInCompletionPayloadAndTaskSummaryExport() = runBlocking {
        val threadId = "thread-replay-cancellation"
        val turnId = "turn-replay-cancellation"
        val turnScope = TurnEventScope(threadId, turnId)
        val identity = ModelSessionIdentity(
            threadId = threadId,
            providerId = "provider-under-test",
            transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
            transportConfigHash = stableHash,
            authScopeHash = stableHash,
            modelId = "model-under-test",
            stablePrefixHash = stableHash
        )
        val store = InMemoryAgentEventStore()
        store.createThread(threadId, "Replay cancellation")
        store.startTurn(turnScope, identity, "provider-config-under-test")
        store.recordUserMessage(turnScope, "message-replay-cancellation", "Run the requested task")

        val roundScope = ModelRoundScope(threadId, turnId, "round-replay-cancellation")
        val submittedItems = store.submittedItemRangeForTurn(turnId)
        store.recordModelRoundStarted(
            PreparedModelRoundStart.create(
                scope = roundScope,
                repairPurpose = RepairPurpose.NONE,
                startCommitted = true,
                submittedItems = submittedItems,
                roundDetailRef = "model-round:${roundScope.roundId}"
            )
        )
        val diagnostic = "TASK_CANCELLED"
        val preparedExecution = prepareInterruptedExecution(diagnostic)
        val normalizedRound = NormalizedModelRound.create(
            publicBlocks = emptyList(),
            toolCalls = emptyList(),
            finishMetadata = mapOf("diagnostic_code" to diagnostic)
        )
        store.recordModelRoundPlan(
            PreparedModelRoundCommit.create(
                scope = roundScope,
                expectedStartCommitted = true,
                repairPurpose = RepairPurpose.NONE,
                submittedItems = submittedItems,
                roundDetailRef = "model-round:${roundScope.roundId}",
                normalizedRound = normalizedRound,
                preparedExecution = preparedExecution,
                presentationsByCallId = emptyMap()
            )
        )
        store.completeTurn(
            threadId = threadId,
            turnId = turnId,
            result = TurnResult(
                status = "cancelled",
                systemNotice = "Task cancelled",
                diagnosticCode = diagnostic,
                cancellationOrigin = "user_request"
            )
        )

        val completedEvent = store.eventsForTurn(turnId).single { it.eventKind == AgentEventKind.TURN_COMPLETED }
        val completionPayload = Json.decodeFromString<TurnCompletedEventPayload>(completedEvent.payloadJson)
        assertEquals("user_request", completionPayload.cancellationOrigin)
        assertEquals(diagnostic, completionPayload.diagnosticCode)

        val artifacts = AgentEventReplayExporter(store).turnArtifacts(turnId)
        assertNotNull(artifacts)
        val summary = Json.parseToJsonElement(requireNotNull(artifacts).readModelJson).jsonObject
        assertEquals("user_request", summary["cancellationOrigin"]?.jsonPrimitive?.content)
        assertTrue(requireNotNull(artifacts).eventsJsonl.contains("cancellationOrigin"))
        assertTrue(requireNotNull(artifacts).readModelJson.contains("user_request"))
    }
}
