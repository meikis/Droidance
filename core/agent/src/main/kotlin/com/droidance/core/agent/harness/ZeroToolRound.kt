// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.model.llm.providerHttpException
import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan
import com.droidance.core.agent.attempt.ExecutionRoundPlanner

fun prepareZeroToolExecution(round: NormalizedModelRound): PreparedRoundExecutionPlan {
    require(round.toolCalls.isEmpty()) { "A zero-tool ModelRound cannot contain tool calls" }
    if (round.finishMetadata[ZERO_TOOL_DECISION_KEY] == ZeroToolRoundDecision.FAILED.wireName) {
        require(round.publicText == null) { "A failed zero-tool ModelRound cannot publish text" }
        val diagnosticCode = requireNotNull(round.finishMetadata[ZERO_TOOL_DIAGNOSTIC_KEY])
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
    return ExecutionRoundPlanner(
        registrations = emptyList(),
        capabilityRequirements = emptyList(),
        callIdFactory = { _, _ -> error("A zero-tool ModelRound cannot allocate a callId") }
    ).plan(round, startCommitted = false, repairPurpose = RepairPurpose.NONE)
}

enum class ZeroToolRoundDecision(val wireName: String) {
    DIRECT_REPLY("direct_reply"),
    ACTIVATE_ANDROID("activate_android"),
    ACTIVATE_LOCAL("activate_local"),
    FAILED("failed")
}

const val ZERO_TOOL_PHASE_KEY = "phase"
const val ZERO_TOOL_PHASE_VALUE = "zero_tool"
const val ZERO_TOOL_DECISION_KEY = "decision"
const val ZERO_TOOL_DIAGNOSTIC_KEY = "diagnostic_code"
const val ZERO_TOOL_OPENING_VERSION_KEY = "opening_contract_version"
const val ZERO_TOOL_OPENING_VERSION = "1"

/** Lifecycle classification uses explicit host metadata, never public text or tool vocabulary. */
fun NormalizedModelRound.isZeroToolActivation(): Boolean =
    finishMetadata[ZERO_TOOL_PHASE_KEY] == ZERO_TOOL_PHASE_VALUE &&
        finishMetadata[ZERO_TOOL_DECISION_KEY] in setOf(
            ZeroToolRoundDecision.ACTIVATE_ANDROID.wireName,
            ZeroToolRoundDecision.ACTIVATE_LOCAL.wireName
        )

fun zeroToolFinishMetadata(
    decision: ZeroToolRoundDecision,
    diagnosticCode: String?,
    failure: Exception?
): Map<String, String> = buildMap {
    require(failure == null || decision == ZeroToolRoundDecision.FAILED)
    put(ZERO_TOOL_PHASE_KEY, ZERO_TOOL_PHASE_VALUE)
    put(ZERO_TOOL_DECISION_KEY, decision.wireName)
    if (decision == ZeroToolRoundDecision.ACTIVATE_ANDROID || decision == ZeroToolRoundDecision.ACTIVATE_LOCAL) {
        put(ZERO_TOOL_OPENING_VERSION_KEY, ZERO_TOOL_OPENING_VERSION)
    }
    diagnosticCode?.let { put(ZERO_TOOL_DIAGNOSTIC_KEY, it) }
    // Exception messages may include endpoints, request text or credentials.
    failure?.let {
        put("exception_type", it.javaClass.name)
        it.providerHttpException()?.let { provider ->
            put("response_http_status", provider.statusCode.toString())
            put("response_error_category", provider.category.name)
            provider.providerCode?.let { code -> put("response_provider_code", code) }
        }
        it.stackTrace.firstOrNull()?.let { origin ->
            put("exception_origin", "${origin.className}.${origin.methodName}:${origin.lineNumber}")
        }
    }
}
