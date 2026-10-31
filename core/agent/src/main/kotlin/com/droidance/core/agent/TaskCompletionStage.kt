// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Evidence admission helpers shared by structured completion processing. */
internal fun TaskTurnSession.completionEvidenceRejection(control: ActionControlProtocol.Control?, visibleReference: String?): ToolExecutionResult {
    val routeIssues = evidenceLedger.routeVerificationIssues()
    val evidenceRefs = evidenceLedger.completionEvidenceReferences()
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
    return ToolProtocolResult.notExecuted(
        code = "COMPLETION_EVIDENCE_REQUIRED",
        retryable = true,
        details = buildJsonObject {
            put("unresolved_evidence_refs", kotlinx.serialization.json.JsonArray(
                evidenceLedger.unresolvedCompletionReferences(ActionControlProtocol.completionEvidenceReferences(control))
                    .map(::JsonPrimitive)
            ))
            put("available_evidence_refs", kotlinx.serialization.json.JsonArray(
                evidenceRefs.map(::JsonPrimitive)
            ))
            put("visible_terminal_observation_ref", JsonPrimitive(visibleReference))
            put("claimed_terminal_observation_ref", JsonPrimitive(control?.terminalObservationRef))
            put("route_verification_issue_count", JsonPrimitive(routeIssues.size))
            if (routeIssues.isNotEmpty()) {
                put("route_verification_issues", JsonPrimitive(routeIssues.joinToString(" | ")))
            }
        },
        evidenceRefs = evidenceRefs
    )
}

internal fun completionFreshObservationRequired(): ToolExecutionResult =
    ToolProtocolResult.notExecuted(
        code = "FRESH_OBSERVATION_REQUIRED",
        retryable = true
    )

internal fun completionDeviceExecutionRequired(): ToolExecutionResult =
    ToolProtocolResult.notExecuted(
        code = "DEVICE_EXECUTION_REQUIRED",
        retryable = true
    )

/** Per-turn state avoids capturing dozens of mutable locals into every coroutine. */
