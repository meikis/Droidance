// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionGroundingGateTest {
    @Test
    fun successfulReadOnlyDeviceExecutionDoesNotRequireTerminalScreen() {
        val gate = CompletionGroundingGate()

        gate.recordExecutionStart(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.READ_ONLY
        )
        gate.recordExecutedResult(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.READ_ONLY,
            result = result()
        )

        assertFalse(
            gate.requiresTerminalObservation(
                fullCompletionRequested = true,
                takeoverManaged = false
            )
        )
    }

    @Test
    fun stateChangingDeviceExecutionRequiresTerminalScreenForDone() {
        val gate = CompletionGroundingGate()

        gate.recordExecutionStart(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING
        )

        assertTrue(
            gate.requiresTerminalObservation(
                fullCompletionRequested = true,
                takeoverManaged = false
            )
        )
        assertFalse(
            gate.requiresTerminalObservation(
                fullCompletionRequested = false,
                takeoverManaged = false
            )
        )
        assertFalse(
            gate.requiresTerminalObservation(
                fullCompletionRequested = true,
                takeoverManaged = true
            )
        )
    }

    @Test
    fun failedStateChangingResultBlocksCompletionUntilFreshObservation() {
        val gate = CompletionGroundingGate()

        gate.recordExecutedResult(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            result = result(
                outputText = "The body contains [ACTION_FAILED] as documentation.",
                executionStatus = ToolExecutionStatus.FAILED,
                diagnosticCode = "ACTION_FAILED"
            )
        )

        assertTrue(gate.requiresFreshObservationBeforeCompletion())
        gate.recordFreshGroundedObservation()
        assertFalse(gate.requiresFreshObservationBeforeCompletion())
    }

    @Test
    fun unverifiedRetryDoesNotClearTheGateWithoutObservation() {
        val gate = CompletionGroundingGate()
        gate.recordExecutedResult(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            result = result(evidenceStatus = ToolEvidenceStatus.UNKNOWN)
        )

        gate.recordExecutedResult(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            result = result()
        )

        assertTrue(gate.requiresFreshObservationBeforeCompletion())
    }

    @Test
    fun nonDeviceOrReadOnlyResultsDoNotOpenTheGate() {
        val gate = CompletionGroundingGate()

        gate.recordExecutedResult(
            callId = "call",
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.STATE_CHANGING,
            result = result(executionStatus = ToolExecutionStatus.FAILED)
        )
        gate.recordExecutedResult(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.READ_ONLY,
            result = result(executionStatus = ToolExecutionStatus.FAILED)
        )

        assertFalse(gate.requiresFreshObservationBeforeCompletion())
    }

    @Test
    fun headlessStateChangingDeviceExecutionDoesNotRequireTerminalScreen() {
        val gate = CompletionGroundingGate()

        gate.recordExecutionStart(
            callId = "call",
            binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.STATE_CHANGING,
            mutatesScreen = false
        )

        assertFalse(
            gate.requiresTerminalObservation(
                fullCompletionRequested = true,
                takeoverManaged = false
            )
        )
    }

    @Test fun nonvisualFailureDoesNotDemandGuiButUnknownEffectsRequireFreshEvidence() {
        val gate = CompletionGroundingGate()
        gate.recordExecutedResult("call", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING,
            result(executionStatus = ToolExecutionStatus.FAILED), mutatesScreen = false)
        assertFalse(gate.requiresFreshObservationBeforeCompletion())
        gate.recordExecutedResult("call", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING,
            result(executionStatus = ToolExecutionStatus.INTERRUPTED, evidenceStatus = ToolEvidenceStatus.UNKNOWN),
            mutatesScreen = true)
        assertTrue(gate.requiresFreshObservationBeforeCompletion())
    }

    private fun result(
        outputText: String = "tool completed",
        executionStatus: ToolExecutionStatus = ToolExecutionStatus.SUCCEEDED,
        evidenceStatus: ToolEvidenceStatus = ToolEvidenceStatus.NOT_REQUIRED,
        diagnosticCode: String? = null
    ): ToolExecutionResult = ToolExecutionResult(
        outputText = outputText,
        executionStatus = executionStatus,
        evidenceStatus = evidenceStatus,
        diagnosticCode = diagnosticCode
    )

    @Test fun rejectedLaunchDoesNotLeaveASuccessScreenRequirement() {
        val gate = CompletionGroundingGate()
        gate.recordExecutionStart("launch", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING)
        gate.recordExecutedResult("launch", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING,
            com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("TEST_REJECTED", true))
        assertFalse(gate.requiresTerminalObservation(true, false))
        assertFalse(gate.requiresFreshObservationBeforeCompletion())
    }

    @Test fun rejectedRetryCannotEraseAnEarlierDeliveredOrUnknownMutation() {
        val gate = CompletionGroundingGate()
        gate.recordExecutionStart("first", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING)
        gate.recordExecutedResult("first", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING,
            result(executionStatus = ToolExecutionStatus.INTERRUPTED, evidenceStatus = ToolEvidenceStatus.UNKNOWN, diagnosticCode = "TEST_INTERRUPTED"))
        gate.recordExecutionStart("retry", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING)
        gate.recordExecutedResult("retry", ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING,
            com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("TEST_REJECTED", true))
        assertTrue(gate.requiresTerminalObservation(true, false))
        assertTrue(gate.requiresFreshObservationBeforeCompletion())
    }
}
