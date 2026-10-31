// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.ModelRoundCompletedEventPayload
import com.droidance.core.model.llm.ProviderHttpException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BillingFailureNoticeIntegrationTest {
    @Test fun firstBillingFailureKeepsItsSafeNoticeWithoutRetryOrExplanationRequest() = runBlocking {
        for (wrapped in listOf(false, true)) {
            val fixture = TaskCoordinatorTestFixture(stoppedReplyEnabled = true)
            val started = fixture.startTurn("billing-$wrapped", "Inspect the requested source")
            val failure = ProviderHttpException.fromResponse(402, "private-provider-name",
                """{"error":{"code":402,"message":"private-response-body credential-sentinel"}}""")
            fixture.model.nextStreamFailure = if (wrapped) IllegalStateException("wrapped", failure) else failure

            val result = fixture.coordinator.coordinateTask(
                taskGoal = "Inspect the requested source", additionalContext = "",
                durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
                executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode
            )

            assertEquals(TurnFailureReason.MODEL_UNAVAILABLE, result.failureReason)
            assertEquals(TurnTerminal.SYSTEM_ERROR, result.terminal)
            assertFalse(result.taskDoneByModel)
            assertEquals(1, fixture.model.recordedRequests.size)
            assertEquals(emptyList<String>(), result.toolNames)
            val publicText = RuntimeUiResultPolicy.failureSummary(result) { "unexpected-generic-fallback" }
            assertEquals("termination_model_billing", publicText)
            assertEquals(publicText, result.runtimeFailureSummary)
            for (privateText in listOf("private-provider-name", "private-response-body", "credential-sentinel", "402")) {
                assertFalse(publicText.contains(privateText))
            }
            val rounds = fixture.events(started).filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            assertEquals(1, rounds.size)
            val completed = Json.decodeFromString<ModelRoundCompletedEventPayload>(rounds.single().payloadJson)
            assertEquals("BILLING", completed.finishMetadata["response_error_category"])
            assertEquals("402", completed.finishMetadata["response_http_status"])
        }
    }
}
