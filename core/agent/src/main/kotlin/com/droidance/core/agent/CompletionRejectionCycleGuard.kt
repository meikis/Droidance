// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.serialization.json.*

class CompletionRejectionCycleGuard(val maxRejectedCompletions: Int) {
    init { require(maxRejectedCompletions > 0) }
    private var rejectedCompletions = 0
    private var stalledReviews = 0
    private var previousFingerprint: String? = null
    private var previousDefects: Set<String> = emptySet()
    private val seenDefects = mutableSetOf<String>()
    private val reviewedContentRefs = mutableSetOf<String>()

    fun isRepeated(
        toolName: String,
        completionStatus: String?,
        claimedResults: String?,
        claimedEvidenceRefs: String?,
        claimedTerminalObservationRef: String?,
        availableEvidenceRefs: String,
        visibleTerminalObservationRef: String?,
        rejectionResult: String
    ): Boolean {
        val fingerprint = listOf(
            toolName.trim().lowercase(),
            completionStatus.orEmpty().trim().lowercase(),
            claimedResults.orEmpty(),
            normalizeReferences(claimedEvidenceRefs),
            claimedTerminalObservationRef.orEmpty().trim(),
            normalizeReferences(availableEvidenceRefs),
            visibleTerminalObservationRef.orEmpty().trim(),
            rejectionResult.trim()
        ).joinToString("\u0000")
        val defects = structuralDefects(rejectionResult)
        val fewerDefects = previousDefects.containsAll(defects) && defects.size < previousDefects.size
        val nextValidationField = previousDefects.isNotEmpty() && defects.none { it in previousDefects } &&
            defects.all { it !in seenDefects }
        if (defects.isNotEmpty() && (fewerDefects || nextValidationField)) {
            rejectedCompletions = 0
            previousFingerprint = null
        }
        seenDefects.addAll(defects)
        previousDefects = defects
        val repeated = fingerprint == previousFingerprint || ++rejectedCompletions >= maxRejectedCompletions
        previousFingerprint = fingerprint
        return repeated
    }

    fun reviewRequired(): Boolean = ++stalledReviews > maxRejectedCompletions

    fun rejectMissingSignal(): Boolean = ++rejectedCompletions >= maxRejectedCompletions

    /** Only first delivery of original source content renews the review budget in this user epoch. */
    fun recordReviewedSources(sources: Map<String, String>, requiredSourceIds: Set<String>) {
        val contentRefs = sources.filterKeys { it in requiredSourceIds }.values
        val newContent = contentRefs.filter(String::isNotBlank).count { reviewedContentRefs.add(it) }
        if (newContent > 0) {
            resetCounters()
        }
    }

    fun reset() {
        reviewedContentRefs.clear()
        resetCounters()
    }

    private fun resetCounters() {
        previousFingerprint = null
        rejectedCompletions = 0
        stalledReviews = 0
        previousDefects = emptySet()
        seenDefects.clear()
    }

    private fun structuralDefects(receipt: String): Set<String> {
        val result = runCatching { Json.parseToJsonElement(receipt).jsonObject }.getOrNull() ?: return emptySet()
        val details = result["details"] as? JsonObject ?: return emptySet()
        val code = result["code"].toString()
        val indexed = setOf("unbound_requirement_indices", "invalid_requirement_indices", "invalid_result_indices",
            "unresolved_evidence_refs", "unresolved_requirement_evidence_refs").flatMap { key ->
            (details[key] as? JsonArray).orEmpty().map { "$code:$key:$it" }
        }.toSet()
        if (indexed.isNotEmpty()) return indexed
        val reason = (details["reason"] as? JsonPrimitive)?.contentOrNull ?: return emptySet()
        val field = details["field"] ?: details["parameter"] ?: return emptySet()
        return setOf("$code:$reason:$field")
    }

    private fun normalizeReferences(raw: String?): String = raw
        .orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .joinToString(",")
}
