// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

enum class PromptCacheReportStatus(val wireName: String) {
    HIT("hit"),
    MISS("miss"),
    CACHE_FIELDS_NOT_REPORTED("cache_fields_not_reported"),
    USAGE_NOT_OBSERVED("usage_not_observed")
}

data class PromptCacheUsageObservation(
    val promptTokens: Int,
    val completionTokens: Int,
    val hitTokens: Int,
    val missTokens: Int,
    val status: PromptCacheReportStatus,
    val cacheWriteTokens: Int? = null
)

/** Chat Completions 的 finish_reason 早于最终 usage chunk 时继续收尾读取。 */
class ChatCompletionsUsageState {
    var observation: PromptCacheUsageObservation = PromptCacheUsageParser.parse(null)
        private set
    var terminalChoiceReceived: Boolean = false
        private set

    val usageObserved: Boolean
        get() = observation.status != PromptCacheReportStatus.USAGE_NOT_OBSERVED

    val needsFinalUsageChunk: Boolean
        get() = terminalChoiceReceived && !usageObserved

    fun observe(usage: JsonObject?, terminalChoice: Boolean) {
        if (usage != null) observation = PromptCacheUsageParser.parse(usage)
        if (terminalChoice) terminalChoiceReceived = true
    }
}

object PromptCacheUsageParser {
    fun parse(usage: JsonObject?): PromptCacheUsageObservation {
        if (usage == null) {
            return PromptCacheUsageObservation(
                promptTokens = 0,
                completionTokens = 0,
                hitTokens = 0,
                missTokens = 0,
                status = PromptCacheReportStatus.USAGE_NOT_OBSERVED
            )
        }

        val promptTokens = usage.int("prompt_tokens").coerceAtLeast(0)
        val completionTokens = usage.int("completion_tokens").coerceAtLeast(0)
        val cacheWrite = (usage["prompt_tokens_details"] as? JsonObject)
            ?.optionalInt("cache_write_tokens")?.coerceAtLeast(0)
        val splitCacheHit = usage.optionalInt("prompt_cache_hit_tokens")
        val splitCacheMiss = usage.optionalInt("prompt_cache_miss_tokens")
        if (splitCacheHit != null || splitCacheMiss != null) {
            val hit = splitCacheHit.orZero().coerceAtLeast(0)
            return PromptCacheUsageObservation(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                hitTokens = hit,
                missTokens = splitCacheMiss.orZero().coerceAtLeast(0),
                status = if (hit > 0) PromptCacheReportStatus.HIT else PromptCacheReportStatus.MISS,
                cacheWriteTokens = cacheWrite
            )
        }

        val promptDetails = usage["prompt_tokens_details"] as? JsonObject
        val openAiCached = promptDetails?.optionalInt("cached_tokens")
        if (openAiCached != null) {
            val hit = openAiCached.coerceAtLeast(0)
            return PromptCacheUsageObservation(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                hitTokens = hit,
                missTokens = (promptTokens - hit).coerceAtLeast(0),
                status = if (hit > 0) PromptCacheReportStatus.HIT else PromptCacheReportStatus.MISS,
                cacheWriteTokens = cacheWrite
            )
        }

        return PromptCacheUsageObservation(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            hitTokens = 0,
            missTokens = 0,
            status = PromptCacheReportStatus.CACHE_FIELDS_NOT_REPORTED,
            cacheWriteTokens = cacheWrite
        )
    }

    private fun JsonObject.int(key: String): Int = optionalInt(key).orZero()

    private fun JsonObject.optionalInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun Int?.orZero(): Int = this ?: 0
}
