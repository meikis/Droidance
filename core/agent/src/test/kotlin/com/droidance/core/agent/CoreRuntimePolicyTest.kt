// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.*
import org.junit.Test

class CoreRuntimePolicyTest {
    @Test fun retriesUseExponentialJitterAndRespectRetryAfter() {
        assertEquals(2000L, ModelRoundRetryPolicy.backoffMs(2000, 1, random = 0.5))
        assertEquals(4000L, ModelRoundRetryPolicy.backoffMs(2000, 2, random = 0.5))
        assertEquals(1500L, ModelRoundRetryPolicy.backoffMs(2000, 1, random = 0.0))
        assertEquals(2500L, ModelRoundRetryPolicy.backoffMs(2000, 1, random = 1.0))
        val throttled = com.droidance.core.model.llm.ProviderHttpException.fromResponse(429, "fixture", "{}", "10")
        assertEquals(10000L, ModelRoundRetryPolicy.backoffMs(2000, 2, throttled, 0.5))
    }

    @Test fun aDecisionAndItsRetriesCannotMultiplyTheRecoveryBudget() {
        var now = 0L
        val budget = ModelRecoveryBudget(600000) { now }
        budget.start()
        assertEquals(600000L, budget.requestTimeoutMs(1200000))
        now += 180000L * 1000000
        budget.start()
        assertEquals(420000L, budget.requestTimeoutMs(600000))
        now += 420000L * 1000000
        assertEquals(0L, budget.requestTimeoutMs(600000))
        assertNull(budget.retryDelayMs(0, 1))
        budget.reset()
        budget.start()
        assertEquals(600000L, budget.remainingMs())
    }
    @Test fun disabledModelDeadlineStillHonorsAnExplicitTaskBudget() {
        assertEquals(0L, RoundTimeoutBudgetPolicy.forOutputBudget(0, 100, 200))
        assertEquals(0L, RoundTimeoutBudgetPolicy.bound(0, 0, 100_000, false))
        assertEquals(1_500L, RoundTimeoutBudgetPolicy.bound(0, 2_000, 500, false))
        assertEquals(0L, RoundTimeoutBudgetPolicy.bound(0, 2_000, 2_001, false))
    }

    @Test
    fun unconfirmedCompletionAndCompactionFailureKeepTheirSpecificTerminalMessage() {
        for (reason in listOf(TurnFailureReason.CONTEXT_COMPACTION_FAILED)) {
            assertEquals(RuntimeUiOutcome.NEED_MORE_INFO, RuntimeUiResultPolicy.outcome(TurnTerminal.NEED_MORE_INFO, reason))
            assertEquals(RuntimeUiOutcome.SYSTEM_ERROR, RuntimeUiResultPolicy.outcome(TurnTerminal.SYSTEM_ERROR, reason))
        }
    }

    @Test
    fun invalidCompletionIsASystemFailureNotARequestForMoreUserInformation() {
        assertEquals(RuntimeUiOutcome.SYSTEM_ERROR, RuntimeUiResultPolicy.outcome(
            TurnTerminal.SYSTEM_ERROR, TurnFailureReason.MODEL_PROTOCOL_ERROR))
        assertEquals(RuntimeUiOutcome.SYSTEM_ERROR, RuntimeUiResultPolicy.outcome(
            TurnTerminal.NEED_MORE_INFO, TurnFailureReason.MODEL_PROTOCOL_ERROR))
        assertEquals("error", RuntimeTerminalProjection.from(TurnTerminal.SYSTEM_ERROR).reportStatus)
    }

    @Test
    fun failureNoticePreservesExplicitLocalProvenanceButNeverAnUnacceptedCandidate() {
        val candidate = TurnResult(false, summary = "Unsupported candidate",
            terminal = TurnTerminal.SYSTEM_ERROR, failureReason = TurnFailureReason.MODEL_PROTOCOL_ERROR)
        assertEquals("Local fallback", RuntimeUiResultPolicy.failureSummary(candidate) { "Local fallback" })
        assertEquals("Unverified page notes", RuntimeUiResultPolicy.failureSummary(
            candidate.copy(runtimeFailureSummary = "Unverified page notes")) { "Local fallback" })
        assertEquals("Partial findings; missing source", RuntimeUiResultPolicy.failureSummary(
            candidate.copy(summary = "Partial findings; missing source", taskDoneByModel = true,
                taskDoneStatus = "failed")) { "Local fallback" })
    }

    @Test
    fun expandedOutputTimeStillRespectsRemainingTaskBudget() {
        val expanded = RoundTimeoutBudgetPolicy.forOutputBudget(30000, 1024, 4096)
        assertEquals(120000L, expanded)
        assertEquals(15000L, RoundTimeoutBudgetPolicy.bound(expanded, 60000, 45000, false))
        assertEquals(0L, RoundTimeoutBudgetPolicy.bound(expanded, 60000, 60000, false))
        assertEquals(30000L, RoundTimeoutBudgetPolicy.forOutputBudget(30000, 1024, 1024))
        assertEquals(Long.MAX_VALUE, RoundTimeoutBudgetPolicy.forOutputBudget(Long.MAX_VALUE, 1, Int.MAX_VALUE))
    }

    @Test
    fun taskTimeLimitTurnResultIsSystemError() {
        assertEquals(RuntimeUiOutcome.SYSTEM_ERROR, RuntimeUiResultPolicy.outcome(
            terminal = TurnTerminal.FAILED,
            failureReason = TurnFailureReason.TASK_TIME_LIMIT
        ))
    }

    @Test
    fun failedGuardStopRemainsSystemError() {
        assertEquals(RuntimeUiOutcome.SYSTEM_ERROR, RuntimeUiResultPolicy.outcome(
            terminal = TurnTerminal.FAILED,
            failureReason = TurnFailureReason.GUARD_STOP
        ))
    }

    @Test
    fun groundedGuardStopPreservesNeedMoreInfo() {
        assertEquals(RuntimeUiOutcome.NEED_MORE_INFO, RuntimeUiResultPolicy.outcome(
            terminal = TurnTerminal.NEED_MORE_INFO,
            failureReason = TurnFailureReason.GUARD_STOP
        ))
    }

    @Test
    fun emptyTransientModelRoundRetriesOnlyWithinConfiguredBudget() {
        assertTrue(ModelRoundRetryPolicy.shouldRetry(
            hasActionableOutput = false,
            permanentFailure = false,
            retriesUsed = 0,
            retryLimit = 2
        ))
        assertFalse(ModelRoundRetryPolicy.shouldRetry(
            hasActionableOutput = false,
            permanentFailure = false,
            retriesUsed = 2,
            retryLimit = 2
        ))
        assertFalse(ModelRoundRetryPolicy.shouldRetry(
            hasActionableOutput = true,
            permanentFailure = false,
            retriesUsed = 0,
            retryLimit = 2
        ))
        assertFalse(ModelRoundRetryPolicy.shouldRetry(
            hasActionableOutput = false,
            permanentFailure = true,
            retriesUsed = 0,
            retryLimit = 2
        ))
    }

    @Test
    fun modelRoundTimeoutCannotExceedRemainingActiveTaskWall() {
        assertEquals(
            30_000L,
            RoundTimeoutBudgetPolicy.bound(
                requestedTimeoutMs = 90_000L,
                taskWallMs = 240_000L,
                activeElapsedMs = 210_000L,
                finalReply = false
            )
        )
        assertEquals(
            90_000L,
            RoundTimeoutBudgetPolicy.bound(
                requestedTimeoutMs = 90_000L,
                taskWallMs = 0L,
                activeElapsedMs = 210_000L,
                finalReply = false
            )
        )
        assertEquals(
            0L,
            RoundTimeoutBudgetPolicy.bound(
                requestedTimeoutMs = 90_000L,
                taskWallMs = 240_000L,
                activeElapsedMs = 240_000L,
                finalReply = false
            )
        )
        assertEquals(
            60_000L,
            RoundTimeoutBudgetPolicy.bound(
                requestedTimeoutMs = 60_000L,
                taskWallMs = 240_000L,
                activeElapsedMs = 240_000L,
                finalReply = true
            )
        )
    }

    @Test
    fun overlayTakeoverRejectsOnlyTheImmediatePostResumeTransition() {
        assertFalse(ManualTakeoverTransitionPolicy.shouldAcceptOverlayTakeover(
            isPaused = false,
            lastResumeElapsedMs = 10_000L,
            nowElapsedMs = 10_800L,
            debounceMs = 1_500L
        ))
        assertTrue(ManualTakeoverTransitionPolicy.shouldAcceptOverlayTakeover(
            isPaused = false,
            lastResumeElapsedMs = 10_000L,
            nowElapsedMs = 11_500L,
            debounceMs = 1_500L
        ))
        assertTrue(ManualTakeoverTransitionPolicy.shouldAcceptOverlayTakeover(
            isPaused = true,
            lastResumeElapsedMs = 10_000L,
            nowElapsedMs = 10_100L,
            debounceMs = 1_500L
        ))
        assertTrue(ManualTakeoverTransitionPolicy.shouldAcceptOverlayTakeover(
            isPaused = false,
            lastResumeElapsedMs = 0L,
            nowElapsedMs = 100L,
            debounceMs = 1_500L
        ))
    }

    @Test
    fun explicitDoneIsSuccessfulWithoutRuntimeFailure() {
        assertEquals(RuntimeUiOutcome.DONE, RuntimeUiResultPolicy.outcome(
            terminal = TurnTerminal.DONE,
            failureReason = TurnFailureReason.NONE
        ))
        assertFalse(RuntimeUiResultPolicy.isFailed(
            terminal = TurnTerminal.DONE,
            failureReason = TurnFailureReason.NONE
        ))
    }

    @Test
    fun failedTerminalIsRuntimeFailure() {
        assertEquals(RuntimeUiOutcome.FAILED, RuntimeUiResultPolicy.outcome(
            terminal = TurnTerminal.FAILED,
            failureReason = TurnFailureReason.NONE
        ))
        assertTrue(RuntimeUiResultPolicy.isFailed(
            terminal = TurnTerminal.FAILED,
            failureReason = TurnFailureReason.NONE
        ))
    }

    @Test
    fun needMoreInfoTerminalIsPreservedAsItsOwnOutcome() {
        assertEquals(RuntimeUiOutcome.NEED_MORE_INFO, RuntimeUiResultPolicy.outcome(
            terminal = TurnTerminal.NEED_MORE_INFO,
            failureReason = TurnFailureReason.NONE
        ))
        assertFalse(RuntimeUiResultPolicy.isFailed(
            terminal = TurnTerminal.NEED_MORE_INFO,
            failureReason = TurnFailureReason.NONE
        ))
    }

    @Test
    fun verified_partial_result_survives_a_final_reply_timeout() {
        assertEquals(
            RuntimeUiOutcome.NEED_MORE_INFO,
            RuntimeUiResultPolicy.outcome(
                terminal = TurnTerminal.NEED_MORE_INFO,
                failureReason = TurnFailureReason.MODEL_TIMEOUT
            )
        )
    }

    @Test
    fun exhaustedModelServiceFailureIsNotClassifiedAsDeviceChannelFailure() {
        assertEquals(
            RuntimeUiOutcome.SYSTEM_ERROR,
            RuntimeUiResultPolicy.outcome(
                terminal = TurnTerminal.SYSTEM_ERROR,
                failureReason = TurnFailureReason.MODEL_UNAVAILABLE
            )
        )
    }

    @Test
    fun runtimeFailureIsSeparatedFromModelDoneStatus() {
        assertEquals(RuntimeUiOutcome.SYSTEM_ERROR, RuntimeUiResultPolicy.outcome(
            terminal = TurnTerminal.DONE,
            failureReason = TurnFailureReason.GUARD_STOP
        ))
    }

}
