// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect

/**
 * Keeps a failed or unverified device mutation from being mistaken for a completed task.
 *
 * The caller only records results for tool calls that actually ran. A later grounded
 * observation is the sole way to clear the gate, so a successful retry still has to be
 * observed before the Agent can claim completion.
 */
class CompletionGroundingGate {
    private var freshObservationRequired = false
    private var stateChangingDeviceExecutionStarted = false
    private val pendingMutations = mutableSetOf<String>()

    fun recordExecutionStart(
        callId: String,
        binding: ToolBinding?,
        effect: ToolEffect?,
        mutatesScreen: Boolean = true
    ) {
        if (binding == ToolBinding.DEVICE_BOUND && effect == ToolEffect.STATE_CHANGING && mutatesScreen) {
            pendingMutations += callId
        }
    }

    fun recordExecutedResult(
        callId: String,
        binding: ToolBinding?,
        effect: ToolEffect?,
        result: ToolExecutionResult,
        mutatesScreen: Boolean = true
    ) {
        pendingMutations.remove(callId)
        if (result.executionStatus == com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED ||
            binding != ToolBinding.DEVICE_BOUND || effect != ToolEffect.STATE_CHANGING || !mutatesScreen) {
            return
        }
        stateChangingDeviceExecutionStarted = true
        if (ToolResultStatus.eventStatus(result) != ToolEventStatus.SUCCEEDED) {
            freshObservationRequired = true
        }
    }

    fun recordFreshGroundedObservation() {
        freshObservationRequired = false
    }

    fun requiresTerminalObservation(
        fullCompletionRequested: Boolean,
        takeoverManaged: Boolean
    ): Boolean = (stateChangingDeviceExecutionStarted || pendingMutations.isNotEmpty()) &&
        fullCompletionRequested &&
        !takeoverManaged

    fun requiresFreshObservationBeforeCompletion(): Boolean = freshObservationRequired
}
