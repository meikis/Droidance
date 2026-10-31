// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

/**
 * Deterministic, task-local state passed between Operator rounds.
 * It contains only closed evidence and recovery constraints; no screen history is retained here.
 */
data class TaskWorkingState(
    val checkpoint: String,
    val latestClosedObservationId: String?,
    val semanticContextKey: String?,
    val verifiedEvidence: String,
    val recentActions: List<String>,
    val blockedActions: List<String>
) {
    fun render(maxChars: Int = DEFAULT_MAX_CHARS): String {
        val limit = maxChars.coerceAtLeast(0)
        if (limit == 0) return ""
        val output = StringBuilder(limit)
        // The evidence ledger is the only state the model can use to close a
        // research task. Keep it at the front of the bounded projection; when
        // it was appended last, long action/checkpoint sections reduced it to
        // a dangling "- [f" and the model could not see its own facts.
        val evidenceSectionLimit = (limit * EVIDENCE_SECTION_RATIO).toInt().coerceAtLeast(1)
        val latestSectionLimit = (limit * LATEST_SECTION_RATIO).toInt().coerceAtLeast(1)
        val recentSectionLimit = (limit * RECENT_SECTION_RATIO).toInt().coerceAtLeast(1)
        val blockedSectionLimit = (limit * BLOCKED_SECTION_RATIO).toInt().coerceAtLeast(1)

        fun appendSection(section: String, sectionLimit: Int = limit) {
            val clean = section.trim()
            if (clean.isBlank() || output.length >= limit) return
            if (output.isNotEmpty()) output.append('\n')
            val remaining = limit - output.length
            if (remaining > 0) output.append(clean.take(minOf(remaining, sectionLimit)))
        }

        verifiedEvidence.trim().takeIf(String::isNotBlank)?.let {
            appendSection("verified_evidence:\n$it", evidenceSectionLimit)
        }
        appendSection(buildString {
            latestClosedObservationId?.trim()?.takeIf(String::isNotBlank)?.let {
                appendLine("latest_closed_observation_id: $it")
            }
            semanticContextKey?.trim()?.takeIf(String::isNotBlank)?.let {
                appendLine("semantic_context_key: $it")
            }
        }, latestSectionLimit)
        appendSection(
            listSection("recent_actions", recentActions.takeLast(MAX_RECENT_ITEMS).asReversed()),
            recentSectionLimit
        )
        appendSection(
            listSection("blocked_actions", blockedActions.distinct().takeLast(MAX_BLOCKED_ITEMS).asReversed()),
            blockedSectionLimit
        )
        checkpoint.trim().takeIf(String::isNotBlank)?.let(::appendSection)
        return output.toString().trim()
    }

    private fun listSection(name: String, values: List<String>): String {
        val items = values.map(String::trim).filter(String::isNotBlank)
        if (items.isEmpty()) return ""
        return buildString {
            appendLine("$name:")
            items.forEach { appendLine("- $it") }
        }
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 4_000
        const val MAX_RECENT_ITEMS = 8
        const val MAX_BLOCKED_ITEMS = 8
        const val EVIDENCE_SECTION_RATIO = 0.45
        const val LATEST_SECTION_RATIO = 0.10
        const val RECENT_SECTION_RATIO = 0.20
        const val BLOCKED_SECTION_RATIO = 0.15
    }
}
