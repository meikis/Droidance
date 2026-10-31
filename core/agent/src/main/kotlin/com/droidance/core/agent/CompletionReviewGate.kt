// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.json.*

/** Binds a same-agent review opportunity to exact output, never certifies semantic truth. */
class CompletionReviewGate(private val enabled: Boolean) {
    private var taskScope: String? = null
    private var scope: String? = null
    private var candidate: CompletionReviewCandidate? = null
    private var requested = false
    private val delivered = linkedSetOf<String>()
    private val deliveredEver = linkedSetOf<String>()
    private val pending = linkedSetOf<String>()
    private val reviewedEntries = linkedSetOf<String>()
    private var reviewedEmptyCandidate = false
    private var reviewRoundCompleted = false

    fun pendingSources(): Set<String> = pending - delivered
    fun candidateSourceRefs(): Set<String> = candidate?.entries.orEmpty().values.flatMap { entry ->
        ((entry as? JsonObject)?.get("evidence_refs") as? JsonArray)
            ?.let(com.droidance.core.agent.harness.PublicResultEntry::references).orEmpty()
    }.map(String::trim).filter(String::isNotEmpty).toSet() + pending

    fun candidateId(): String? = candidate?.id
    fun candidateTool(): String? = candidate?.toolName
    fun reviewedResultIds(): Set<String> = reviewedEntries.toSet()

    /** Call only after a successful model request using the actual delivered image inventory. */
    fun recordDeliveredSources(sources: Map<String, String>): Boolean {
        if (!requested) return false
        val before = deliveredEver.size
        delivered.addAll(sources.keys.intersect(pending))
        deliveredEver.addAll(sources.filterKeys { it in pending }.values)
        reviewRoundCompleted = true
        if (pendingSources().isEmpty()) {
            reviewedEntries.addAll(candidate?.entries.orEmpty().keys)
            reviewedEmptyCandidate = candidate?.entries?.isEmpty() == true
        }
        return deliveredEver.size > before
    }

    fun request(originalRequest: String, instruction: String, imageSources: Set<String>,
                submitted: CompletionReviewCandidate, userUpdates: List<String> = emptyList(),
                confirmation: JsonObject? = null): ToolExecutionResult? {
        if (!enabled) return null
        val effectiveTask = JsonArray((listOf(originalRequest) + userUpdates).map(::JsonPrimitive)).toString()
        if (taskScope != effectiveTask) {
            reset()
            taskScope = effectiveTask
        }
        val first = !requested
        if (scope != submitted.scope) {
            scope = submitted.scope
            candidate = null
            reviewRoundCompleted = false
            reviewedEmptyCandidate = false
            // Rewording checks must not renew the evidence-delivery progress budget.
        }
        val changed = submitted.entries.keys - reviewedEntries
        val candidateChanged = candidate?.id != submitted.id
        if (candidateChanged) {
            pending.clear()
            pending.addAll(imageSources)
            reviewRoundCompleted = false
        } else {
            pending.addAll(imageSources)
        }
        candidate = submitted
        if (confirmation?.get("candidate_id")?.jsonPrimitive?.content == submitted.id &&
            requested && reviewRoundCompleted && pendingSources().isEmpty() && changed.isEmpty() &&
            (submitted.entries.isNotEmpty() || reviewedEmptyCandidate)) return null
        requested = true
        return ToolProtocolResult.notExecuted(if (first) CODE else SOURCE_CODE, retryable = true,
            details = buildJsonObject {
                put("original_request", originalRequest)
                put("user_updates", JsonArray(userUpdates.map(::JsonPrimitive)))
                put("candidate_id", submitted.id)
                put("candidate_tool", submitted.toolName)
                put("required_source_images", JsonArray(pendingSources().map(::JsonPrimitive)))
                put("changed_result_ids", JsonArray(changed.map(::JsonPrimitive)))
                // The first review also receives the exact candidate; no blind reconstruction.
                put("candidate_to_verify", submitted.fileReference?.get("native_submission") ?: submitted.originalArguments)
                submitted.fileReference?.get("deliverables_to_verify")?.let { put("deliverables_to_verify", it) }
                put("required_review_requirement_ids", JsonArray((submitted.originalArguments["requirement_checks"] as? JsonArray).orEmpty().map {
                    it.jsonObject.getValue("requirement_id") }))
                put("recovery_condition", instruction)
            })
    }

    fun onToolSettled(binding: ToolBinding?, effect: ToolEffect?, status: ToolExecutionStatus,
                      stateChanged: Boolean? = null): Boolean {
        // Device mutations can alter the reviewed external state, including partial effects.
        // Local reads and working-record updates do not alter it. Deliverable revisions are
        // checked against the candidate's frozen versions by CompletionSubmissionProtocol.
        val invalidated = binding == ToolBinding.DEVICE_BOUND && effect == ToolEffect.STATE_CHANGING &&
            status != ToolExecutionStatus.NOT_EXECUTED && stateChanged != false
        if (invalidated) reset()
        return invalidated
    }

    fun reset() {
        requested = false; taskScope = null; scope = null; candidate = null
        delivered.clear(); deliveredEver.clear(); pending.clear(); reviewedEntries.clear()
        reviewedEmptyCandidate = false; reviewRoundCompleted = false
    }

    companion object {
        const val CODE = "COMPLETION_REVIEW_REQUIRED"
        const val SOURCE_CODE = "COMPLETION_SOURCE_REVIEW_REQUIRED"
        fun isReview(code: String?) = code == CODE || code == SOURCE_CODE
    }
}
