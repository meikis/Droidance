// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.ModelRoundScope
import com.droidance.core.agent.event.ModelRoundCompletedEventPayload
import com.droidance.core.agent.event.PreparedModelRoundCommit
import com.droidance.core.agent.event.PreparedModelRoundStart
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.event.TurnResult
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedModelRoundTest {
    private val stableHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun interruptedRoundPreservesTheHostDiagnosticWithoutCreatingModelOutput() {
        val prepared = prepareInterruptedExecution("TASK_CANCELLED")

        assertTrue(prepared.roundPlan.selectedCalls.isEmpty())
        assertTrue(prepared.roundPlan.rejectedCalls.isEmpty())
        assertEquals(RoundExecutionMode.NONE, prepared.roundPlan.executionMode)
        assertEquals(RoundFollowUp.TERMINATE_PROTOCOL, prepared.roundPlan.followUp)
        assertEquals("TASK_CANCELLED", prepared.roundPlan.terminalDiagnosticCode)
        assertTrue(prepared.roundPlan.terminalDiagnosticCode != RoundDiagnosticCode.EMPTY_MODEL_RESPONSE)
    }

    @Test
    fun blankInterruptionDiagnosticIsRejected() {
        val thrown = runCatching { prepareInterruptedExecution(" ") }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun normalEmptyProviderRoundStillUsesEmptyModelResponse() {
        val plan = RoundExecutionPlanner(emptyList()).plan(
            round = NormalizedModelRound.create(publicBlocks = emptyList(), toolCalls = emptyList()),
            startCommitted = true,
            repairPurpose = RepairPurpose.NONE
        )

        assertEquals(RoundDiagnosticCode.EMPTY_MODEL_RESPONSE, plan.terminalDiagnosticCode)
        assertEquals(RoundFollowUp.TERMINATE_PROTOCOL, plan.followUp)
    }

    @Test
    fun interruptedRoundCommitPersistsItsDiagnosticAndCancellationCompletion() = runBlocking {
        val threadId = "thread-cancellation-diagnostic"
        val turnId = "turn-cancellation-diagnostic"
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
        store.createThread(threadId, "Cancellation diagnostics")
        store.startTurn(turnScope, identity, "provider-config-under-test")
        store.recordUserMessage(turnScope, "message-cancellation", "Run the requested task")

        val roundScope = ModelRoundScope(threadId, turnId, "round-cancellation-diagnostic")
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
        val commit = PreparedModelRoundCommit.create(
            scope = roundScope,
            expectedStartCommitted = true,
            repairPurpose = RepairPurpose.NONE,
            submittedItems = submittedItems,
            roundDetailRef = "model-round:${roundScope.roundId}",
            normalizedRound = normalizedRound,
            preparedExecution = preparedExecution,
            presentationsByCallId = emptyMap()
        )
        val recorded = store.recordModelRoundPlan(commit)
        val roundPayload = Json.decodeFromString<ModelRoundCompletedEventPayload>(
            recorded.recorded.completed.payloadJson
        )

        assertEquals(diagnostic, roundPayload.terminalDiagnosticCode)
        assertTrue(roundPayload.terminalDiagnosticCode != RoundDiagnosticCode.EMPTY_MODEL_RESPONSE)

        val completion = store.completeTurn(
            threadId = threadId,
            turnId = turnId,
            result = TurnResult(
                status = "cancelled",
                systemNotice = "Task cancelled",
                diagnosticCode = diagnostic,
                cancellationOrigin = "user_request"
            )
        )
        val completionPayload = Json.decodeFromString<com.droidance.core.agent.event.TurnCompletedEventPayload>(
            completion.payloadJson
        )
        assertEquals(diagnostic, completionPayload.diagnosticCode)
        assertEquals("user_request", completionPayload.cancellationOrigin)
        assertEquals(
            1,
            store.eventsForTurn(turnId).count { it.eventKind == AgentEventKind.TURN_COMPLETED }
        )
    }
}
