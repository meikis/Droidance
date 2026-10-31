// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.harness.PlannedToolCall
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.*

/** Failed prerequisites stop a suffix. Local working-state writes do not gate independent actions. */
class OrderedToolSequence {
    private var stoppedBy: String? = null
    private var stoppedReason: String? = null
    private var workingStateFailure: Pair<String, String>? = null

    fun rejection(independentOfWorkingState: Boolean = false): ToolExecutionResult? {
        val failure = stoppedBy?.let { it to stoppedReason } ?: workingStateFailure?.takeUnless { independentOfWorkingState }
        return failure?.let { (callId, reason) ->
            ToolProtocolResult.notExecuted("PRIOR_CALL_FAILED", retryable = false,
                details = buildJsonObject {
                    put("failed_call_id", callId)
                    reason?.let { put("reason", it) }
                })
        }
    }

    fun record(callId: String, result: ToolExecutionResult, workingStateOnly: Boolean = false) {
        if (!allowsOrderedContinuation(result)) {
            if (workingStateOnly && result.stateChanged != true && result.executionStatus in
                setOf(ToolExecutionStatus.FAILED, ToolExecutionStatus.NOT_EXECUTED)) {
                if (workingStateFailure == null) workingStateFailure = callId to
                    (result.diagnosticCode ?: result.executionStatus.name)
                return
            }
            if (stoppedBy == null) {
                stoppedBy = callId
                stoppedReason = result.diagnosticCode ?: result.executionStatus.name
            }
        }
    }
}

/** Delivery permits continuation; it never establishes semantic completion. */
fun allowsOrderedContinuation(result: ToolExecutionResult): Boolean =
    result.executionStatus == ToolExecutionStatus.SUCCEEDED &&
        result.evidenceStatus != ToolEvidenceStatus.INVALID &&
        result.actionEvidence?.delivery !in setOf(ActionDeliveryEvidence.UNKNOWN, ActionDeliveryEvidence.NOT_DELIVERED) &&
        (result.isVerifiedSuccess || result.actionEvidence?.delivery == ActionDeliveryEvidence.ACTION_DELIVERED)

/** Only adjacent screen calls in one model decision share this inspected frame. */
class ScreenActionSequence(val observation: ObservationEnvelope)

class ScreenActionSequenceMember(
    val sequence: ScreenActionSequence,
    val first: Boolean,
    val last: Boolean
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScreenActionSequenceMember>
}

/** Adjacent single-primitive groups can share the inspected frame; larger groups own their lifecycle. */
fun screenActionSequenceMembers(
    calls: List<PlannedToolCall>, observation: ObservationEnvelope?
): Map<String, ScreenActionSequenceMember> {
    if (observation == null) return emptyMap()
    val ordered = calls.sortedBy { it.providerOrder }
    val start = ordered.indexOfFirst { it.contract.binding == ToolBinding.DEVICE_BOUND }
    if (start < 0) return emptyMap()
    val firstOrder = ordered[start].providerOrder
    val adjacent = ordered.drop(start).withIndex().takeWhile { (index, call) ->
        val actions = runCatching {
            Json.parseToJsonElement(call.providerCall.argumentsJson).jsonObject["actions"] as? JsonArray
        }.getOrNull()
        val primitive = actions?.singleOrNull() as? JsonObject
        call.providerOrder == firstOrder + index && call.providerCall.name == DeviceToolNames.SCREEN_ACT &&
            (primitive?.get("op") as? JsonPrimitive)?.contentOrNull in ScreenActionBatchProtocol.operations.filterNot {
                it in setOf("wait", "screenshot") }
    }.map { it.value }
    val boundary = adjacent.indexOfFirst {
        UnifiedDeviceToolProtocol.requestsFrameWait(it.providerCall.name, it.providerCall.argumentsJson)
    }
    val group = if (boundary < 0) adjacent else adjacent.take(boundary + 1)
    if (group.size < 2) return emptyMap()
    val sequence = ScreenActionSequence(observation)
    return group.mapIndexed { index, call ->
        call.callId to ScreenActionSequenceMember(sequence, index == 0, index == group.lastIndex)
    }.toMap()
}
