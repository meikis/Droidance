// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import com.droidance.core.knowledge.memory.ExperienceUseRecord
import kotlinx.serialization.json.*

/** Recent task-Agent assessments of one exact method revision, never a success guarantee. */
data class SkillCandidateFeedback private constructor(
    val revision: String,
    val windowLimit: Int,
    val recentCount: Int,
    val succeeded: Int,
    val failed: Int,
    val unknown: Int,
    val unassessed: Int,
    val latestOutcome: String?,
    val latestSummary: String?
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("revision", revision)
        put("window_limit", windowLimit)
        put("recent_count", recentCount)
        put("succeeded", succeeded); put("failed", failed)
        put("unknown", unknown); put("unassessed", unassessed)
        latestOutcome?.let { put("latest_outcome", it) }
        latestSummary?.let { put("latest_summary", it) }
        put("assessment_authority", "task_agent")
    }

    fun promptSummary(maxChars: Int): String {
        require(maxChars >= 0)
        return buildString {
            append("Recent Agent assessments ($recentCount/$windowLimit): ")
            append("succeeded=$succeeded, failed=$failed, unknown=$unknown, unassessed=$unassessed")
            latestOutcome?.let { append("; latest=$it") }
            latestSummary?.takeIf(String::isNotBlank)?.let { append(": $it") }
        }.take(maxChars)
    }

    companion object {
        const val DEFAULT_WINDOW_LIMIT = 5
        private const val MAX_WINDOW_LIMIT = 20
        private const val MAX_SUMMARY_CHARS = 120

        fun fromSkillAssessments(
            revision: String,
            records: Iterable<JsonObject>,
            windowLimit: Int = DEFAULT_WINDOW_LIMIT
        ): SkillCandidateFeedback = summarize(revision, records.mapNotNull { record ->
            if (record["revision"]?.jsonPrimitive?.contentOrNull != revision) return@mapNotNull null
            val outcome = record["outcome"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Observation(outcome, record["assessed_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                record["summary"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }, windowLimit)

        fun fromExperienceUses(
            ref: String,
            records: Iterable<ExperienceUseRecord>,
            windowLimit: Int = DEFAULT_WINDOW_LIMIT
        ): SkillCandidateFeedback = summarize(ref, records.filter { it.experienceRef == ref }.map { record ->
            Observation(if (record.outcome == "adopted") "unassessed" else record.outcome,
                record.assessedAt ?: record.recordedAt, record.summary)
        }, windowLimit)

        private data class Observation(val outcome: String, val recordedAt: Long, val summary: String)

        private fun summarize(revision: String, records: List<Observation>, windowLimit: Int): SkillCandidateFeedback {
            require(revision.isNotBlank())
            require(windowLimit in 1..MAX_WINDOW_LIMIT)
            val recent = records.filter { it.outcome in setOf("succeeded", "failed", "unknown", "unassessed") }
                .sortedWith(compareByDescending<Observation> { it.recordedAt }.thenBy { it.outcome }.thenBy { it.summary })
                .take(windowLimit)
            return SkillCandidateFeedback(revision, windowLimit, recent.size,
                recent.count { it.outcome == "succeeded" }, recent.count { it.outcome == "failed" },
                recent.count { it.outcome == "unknown" }, recent.count { it.outcome == "unassessed" },
                recent.firstOrNull()?.outcome,
                recent.firstOrNull()?.summary?.replace(Regex("\\s+"), " ")?.take(MAX_SUMMARY_CHARS))
        }
    }
}
