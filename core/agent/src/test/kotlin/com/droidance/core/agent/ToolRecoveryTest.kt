// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.ToolProtocolResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRecoveryTest {
    @Test
    fun workspaceReviewRequirementUsesRecordRecoveryWithoutChangingOtherRecoveryClasses() {
        val recordRequired = ToolProtocolResult.notExecuted("WORKSPACE_REVIEW_REQUIRED", retryable = true)
        val staleObservation = ToolProtocolResult.notExecuted("STALE_OBSERVATION", retryable = true)
        val acceptedReceipt = recordRequired.copy(executionStatus = ToolExecutionStatus.SUCCEEDED)

        assertEquals(ToolExecutionStatus.NOT_EXECUTED, recordRequired.executionStatus)
        assertEquals(ToolRecovery.RECORD, ToolRecovery.classify(recordRequired))
        assertEquals(ToolRecovery.OBSERVE, ToolRecovery.classify(staleObservation))
        assertEquals(ToolRecovery.NONE, ToolRecovery.classify(acceptedReceipt))
    }

    @Test
    fun deliveredActionWithUnavailableFinalObservationStillRequiresObservationRecovery() {
        val deliveredButUnobserved = ToolExecutionResult(
            outputText = "Action delivery succeeded, but the final frame is unavailable.",
            executionStatus = ToolExecutionStatus.SUCCEEDED,
            evidenceStatus = ToolEvidenceStatus.UNKNOWN,
            diagnosticCode = "POST_ACTION_OBSERVATION_UNAVAILABLE"
        )

        assertEquals(ToolExecutionStatus.SUCCEEDED, deliveredButUnobserved.executionStatus)
        assertEquals(ToolEvidenceStatus.UNKNOWN, deliveredButUnobserved.evidenceStatus)
        assertEquals(ToolRecovery.OBSERVE, ToolRecovery.classify(deliveredButUnobserved))
    }
}
