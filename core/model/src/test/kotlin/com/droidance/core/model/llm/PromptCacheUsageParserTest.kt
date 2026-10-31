// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheUsageParserTest {
    @Test fun cacheWritesAreReportedIndependentlyOfReadsAndMissingValuesStayUnknown() {
        assertEquals(null, PromptCacheUsageParser.parse(null).cacheWriteTokens)
        assertEquals(null, PromptCacheUsageParser.parse(buildJsonObject { put("prompt_tokens", 100) }).cacheWriteTokens)
        val observed = PromptCacheUsageParser.parse(buildJsonObject {
            put("prompt_tokens", 100)
            putJsonObject("prompt_tokens_details") { put("cached_tokens", 60); put("cache_write_tokens", 30) }
        })
        assertEquals(60, observed.hitTokens)
        assertEquals(30, observed.cacheWriteTokens)
        val writeOnly = PromptCacheUsageParser.parse(buildJsonObject {
            putJsonObject("prompt_tokens_details") { put("cache_write_tokens", 0) }
        })
        assertEquals(0, writeOnly.cacheWriteTokens)
        assertEquals(PromptCacheReportStatus.CACHE_FIELDS_NOT_REPORTED, writeOnly.status)
    }

    @Test
    fun missing_usage_is_distinct_from_a_reported_cache_miss() {
        val parsed = PromptCacheUsageParser.parse(null)

        assertEquals(PromptCacheReportStatus.USAGE_NOT_OBSERVED, parsed.status)
    }

    @Test
    fun usage_without_cache_fields_is_reported_as_unsupported_telemetry() {
        val parsed = PromptCacheUsageParser.parse(buildJsonObject { put("prompt_tokens", 100) })

        assertEquals(PromptCacheReportStatus.CACHE_FIELDS_NOT_REPORTED, parsed.status)
        assertEquals(0, parsed.missTokens)
    }

    @Test
    fun openai_zero_cached_tokens_is_a_real_cache_miss() {
        val parsed = PromptCacheUsageParser.parse(buildJsonObject {
            put("prompt_tokens", 100)
            putJsonObject("prompt_tokens_details") { put("cached_tokens", 0) }
        })

        assertEquals(PromptCacheReportStatus.MISS, parsed.status)
        assertEquals(0, parsed.hitTokens)
        assertEquals(100, parsed.missTokens)
    }

    @Test
    fun provider_cache_hit_and_miss_tokens_are_preserved() {
        val parsed = PromptCacheUsageParser.parse(buildJsonObject {
            put("prompt_tokens", 100)
            put("prompt_cache_hit_tokens", 80)
            put("prompt_cache_miss_tokens", 20)
        })

        assertEquals(PromptCacheReportStatus.HIT, parsed.status)
        assertEquals(80, parsed.hitTokens)
        assertEquals(20, parsed.missTokens)
    }

    @Test
    fun terminal_choice_waits_for_the_following_usage_chunk() {
        val state = ChatCompletionsUsageState()

        state.observe(usage = null, terminalChoice = true)

        assertTrue(state.needsFinalUsageChunk)
        assertFalse(state.usageObserved)

        state.observe(
            usage = buildJsonObject {
                put("prompt_tokens", 120)
                put("completion_tokens", 30)
                putJsonObject("prompt_tokens_details") { put("cached_tokens", 80) }
            },
            terminalChoice = false
        )

        assertFalse(state.needsFinalUsageChunk)
        assertTrue(state.usageObserved)
        assertEquals(120, state.observation.promptTokens)
        assertEquals(30, state.observation.completionTokens)
        assertEquals(80, state.observation.hitTokens)
    }
}
