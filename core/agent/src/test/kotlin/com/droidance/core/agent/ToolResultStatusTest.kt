// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.event.GoalProgressEvidence
import com.droidance.core.agent.event.StateTransitionEvidence
import com.droidance.core.agent.event.ToolActionEvidence
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.droidance.core.agent.event.ToolFinishedEventPayload

class ToolResultStatusTest {
    @Test
    fun arbitraryKnowledgeOrSkillBodyMarkersDoNotChangeTypedSuccess() {
        val result = result(
            outputText = """
                ## Instructions
                Documentation examples: [APP_RESET_FAILED], [RETRY_TIMEOUT],
                [SKILL_SCREEN_EVIDENCE_UNAVAILABLE], and [ACTION_CONFIRMED].
            """.trimIndent()
        )

        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals(ToolEventStatus.SUCCEEDED, ToolResultStatus.eventStatus(result))
        assertTrue(ToolResultStatus.isSuccess(result))
    }

    @Test
    fun typedFailureStatusIsAuthoritativeOverBodyMarkersAndSuccessWords() {
        val result = result(
            outputText = "The body says [ACTION_CONFIRMED], while [APP_RESET_FAILED] is documented.",
            executionStatus = ToolExecutionStatus.FAILED,
            diagnosticCode = "ACTION_FAILED"
        )

        assertEquals(ToolEventStatus.FAILED, ToolResultStatus.eventStatus(result))
        assertFalse(ToolResultStatus.isSuccess(result))
        assertEquals("ACTION_FAILED", ToolResultStatus.diagnosticCode(result))
    }

    @Test
    fun notExecutedRemainsUnverifiedWithoutBecomingAnExecutedFailure() {
        val result = result(
            outputText = "Policy documentation contains [POLICY_REJECTED].",
            executionStatus = ToolExecutionStatus.NOT_EXECUTED,
            diagnosticCode = "POLICY_REJECTED"
        )

        assertTrue(ToolResultStatus.isNotExecuted(result))
        assertEquals(ToolEventStatus.UNVERIFIED, ToolResultStatus.eventStatus(result))
        assertFalse(ToolResultStatus.isSuccess(result))
        assertEquals("POLICY_REJECTED", ToolResultStatus.diagnosticCode(result))
    }

    @Test
    fun durableSettlementPreservesEveryTypedStatusIncludingWithheldInvocation() {
        ToolExecutionStatus.entries.forEach { status ->
            val payload = ToolFinishedEventPayload(1,
                result(executionStatus = status, diagnosticCode = "TEST_RESULT"), "round")
            val restored = Json.decodeFromString<ToolFinishedEventPayload>(Json.encodeToString(payload))
            assertEquals(status, restored.result.executionStatus)
        }
    }

    @Test
    fun succeededWithUnknownEvidenceIsUnverifiedAndCannotClaimSuccess() {
        val result = result(
            outputText = "Accepted [ACTION_CONFIRMED]; pending [TOOL_EXECUTION_FAILED] evidence.",
            evidenceStatus = ToolEvidenceStatus.UNKNOWN
        )

        assertEquals(ToolEventStatus.UNVERIFIED, ToolResultStatus.eventStatus(result))
        assertFalse(ToolResultStatus.isSuccess(result))
    }

    @Test
    fun actionDeliveryEvidenceControlsLaunchAcceptanceIndependently() {
        val deliveredButUnverified = result(
            evidenceStatus = ToolEvidenceStatus.UNKNOWN,
            actionEvidence = actionEvidence(ActionDeliveryEvidence.ACTION_DELIVERED)
        )
        assertEquals(ToolEventStatus.UNVERIFIED, ToolResultStatus.eventStatus(deliveredButUnverified))
        assertFalse(ToolResultStatus.isSuccess(deliveredButUnverified))
        assertTrue(ToolResultStatus.isLaunchAccepted(deliveredButUnverified))

        val notDelivered = deliveredButUnverified.copy(
            actionEvidence = actionEvidence(ActionDeliveryEvidence.NOT_DELIVERED)
        )
        assertFalse(ToolResultStatus.isLaunchAccepted(notDelivered))

        val failedButDelivered = result(
            executionStatus = ToolExecutionStatus.FAILED,
            actionEvidence = actionEvidence(ActionDeliveryEvidence.ACTION_DELIVERED)
        )
        assertFalse(ToolResultStatus.isLaunchAccepted(failedButDelivered))
    }

    @Test
    fun invalidEvidenceOnSucceededExecutionIsUnverified() {
        val result = result(evidenceStatus = ToolEvidenceStatus.INVALID)

        assertEquals(ToolEventStatus.UNVERIFIED, ToolResultStatus.eventStatus(result))
        assertFalse(ToolResultStatus.isSuccess(result))
    }

    private fun result(
        outputText: String = "tool completed",
        executionStatus: ToolExecutionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus: ToolEvidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
        diagnosticCode: String? = null,
        actionEvidence: ToolActionEvidence? = null
    ): ToolExecutionResult = ToolExecutionResult(
        outputText = outputText,
        executionStatus = executionStatus,
        evidenceStatus = evidenceStatus,
        diagnosticCode = diagnosticCode,
        actionEvidence = actionEvidence
    )

    private fun actionEvidence(delivery: ActionDeliveryEvidence): ToolActionEvidence = ToolActionEvidence(
        delivery = delivery,
        transition = StateTransitionEvidence.UNKNOWN,
        goalProgress = GoalProgressEvidence.NOT_PROVEN
    )
}
