// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessLifecyclePolicyTest {
    @Test
    fun technicalStopIsNotProjectedAsUserTaskFailure() {
        TurnFailureReason.entries
            .filterNot { it == TurnFailureReason.NONE }
            .forEach { reason ->
                assertEquals(
                    reason.name,
                    RuntimeUiOutcome.SYSTEM_ERROR,
                    RuntimeUiResultPolicy.outcome(
                        terminal = TurnTerminal.FAILED,
                        failureReason = reason
                    )
                )
            }
    }

    @Test
    fun explicitModelFailureRemainsAUserTaskFailure() {
        assertEquals(
            RuntimeUiOutcome.FAILED,
            RuntimeUiResultPolicy.outcome(
                terminal = TurnTerminal.FAILED,
                failureReason = TurnFailureReason.NONE
            )
        )
        assertTrue(
            RuntimeUiResultPolicy.isFailed(
                terminal = TurnTerminal.FAILED,
                failureReason = TurnFailureReason.NONE
            )
        )
        assertFalse(
            RuntimeUiResultPolicy.isFailed(
                terminal = TurnTerminal.FAILED,
                failureReason = TurnFailureReason.GUARD_STOP
            )
        )
    }

    @Test
    fun oneCanonicalProjectionFeedsReportTraceAndExecutionEvent() {
        val projection = RuntimeTerminalProjection.from(TurnTerminal.SYSTEM_ERROR)

        assertEquals("error", projection.reportStatus)
        assertEquals(ExecutionTerminalStatus.ERROR, projection.executionTerminalStatus)
        assertEquals("error", projection.diagnosticVerdict)
    }

    @Test
    fun turnResultPreservesTheTechnicalStopCause() {
        val result = TurnResult(
            hasToolCalls = false,
            summary = "channel unavailable",
            operatorOutput = "channel unavailable",
            terminal = TurnTerminal.SYSTEM_ERROR,
            failureReason = TurnFailureReason.CHANNEL_DOWN
        )

        assertEquals(TurnFailureReason.CHANNEL_DOWN, result.failureReason)
    }
}
