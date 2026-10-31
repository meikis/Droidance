// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus

/** Execution facts are independent of model-visible text, including text read from a device. */
object ToolResultStatus {
    fun eventStatus(result: ToolExecutionResult): ToolEventStatus = when {
        result.executionStatus == ToolExecutionStatus.NOT_EXECUTED -> ToolEventStatus.UNVERIFIED
        result.executionStatus != ToolExecutionStatus.SUCCEEDED -> ToolEventStatus.FAILED
        result.evidenceStatus in setOf(ToolEvidenceStatus.UNKNOWN, ToolEvidenceStatus.INVALID) -> ToolEventStatus.UNVERIFIED
        else -> ToolEventStatus.SUCCEEDED
    }

    fun isSuccess(result: ToolExecutionResult): Boolean = result.isVerifiedSuccess
    fun isNotExecuted(result: ToolExecutionResult): Boolean = result.executionStatus == ToolExecutionStatus.NOT_EXECUTED
    fun diagnosticCode(result: ToolExecutionResult): String? = result.diagnosticCode
    fun isLaunchAccepted(result: ToolExecutionResult): Boolean =
        result.executionStatus == ToolExecutionStatus.SUCCEEDED &&
            result.actionEvidence?.delivery == ActionDeliveryEvidence.ACTION_DELIVERED
}
