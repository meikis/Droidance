// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.ProviderHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionFailureNoticeIntegrationTest {
    private fun runDenied(body: String): Pair<TurnResult, Int> = runBlocking {
        val fixture = TaskCoordinatorTestFixture(stoppedReplyEnabled = true)
        val started = fixture.startTurn("denied", "Inspect the requested source")
        fixture.model.nextStreamFailure = ProviderHttpException.fromResponse(403, "provider", body)
        val result = fixture.coordinator.coordinateTask(
            taskGoal = "Inspect the requested source", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = started.expectedBackend,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode
        )
        result to fixture.model.recordedRequests.size
    }

    @Test fun deniedRequestStopsWithoutRetryAndUsesTheReasonNotice() {
        val (result, requests) = runDenied("""{"error":{"message":"Plan excludes this model"}}""")
        assertEquals(TurnFailureReason.MODEL_UNAVAILABLE, result.failureReason)
        assertEquals(TurnTerminal.SYSTEM_ERROR, result.terminal)
        assertEquals(1, requests)
        assertEquals("termination_model_permission_denied_reason", result.runtimeFailureSummary)
        assertEquals(result.runtimeFailureSummary, RuntimeUiResultPolicy.failureSummary(result) { "wrong fallback" })
    }

    @Test fun deniedRequestWithoutAReasonUsesThePlainNotice() {
        val (result, requests) = runDenied("<html>403</html>")
        assertEquals(1, requests)
        assertEquals("termination_model_permission_denied", result.runtimeFailureSummary)
    }
}
