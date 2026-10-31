// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan

/** Host settlement is not a provider response and must retain its actual diagnostic. */
fun prepareInterruptedExecution(diagnosticCode: String): PreparedRoundExecutionPlan {
    require(diagnosticCode.isNotBlank())
    return PreparedRoundExecutionPlan(
        roundPlan = RoundExecutionPlan(
            publicMessage = null,
            selectedCalls = emptyList(),
            rejectedCalls = emptyList(),
            executionMode = RoundExecutionMode.NONE,
            followUp = RoundFollowUp.TERMINATE_PROTOCOL,
            terminalDiagnosticCode = diagnosticCode
        ),
        attemptBinding = null,
        candidateCapabilitySnapshotRef = null,
        candidateCapabilitySnapshotHash = null,
        toolStarts = emptyList(),
        toolRejections = emptyList()
    )
}
