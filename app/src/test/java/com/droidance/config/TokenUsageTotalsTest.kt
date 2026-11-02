// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.model.llm.ModelRequestUsage
import org.junit.Assert.*
import org.junit.Test

class TokenUsageTotalsTest {
    @Test fun retriesKeepKnownConsumptionAndMissingUsageDistinctFromZero() {
        val known = TokenUsageTotals().addRequest(ModelRequestUsage("p", "m", 10_000, 2_800, 8_000))
        val retried = known.addRequest(ModelRequestUsage("p", "m"))
            .addRequest(ModelRequestUsage("p", "fallback", 3_000, 500))
        assertEquals(16_300L, retried.totalTokens)
        assertEquals(8_000L, retried.cachedInputTokens)
        assertEquals(3L, retried.calls)
        assertEquals(2L, retried.inputReportedCalls)
        assertFalse(retried.isComplete)
        assertTrue(retried.hasReportedTokens)
        assertFalse(TokenUsageTotals().addRequest(ModelRequestUsage("p", "m")).hasReportedTokens)
        assertTrue(TokenUsageTotals().addRequest(ModelRequestUsage("p", "m", 0, 0)).isComplete)
    }

    @Test fun partialFieldsRemainUnknownAndCacheCannotExceedKnownInput() {
        val partial = TokenUsageTotals().addRequest(ModelRequestUsage("p", "m", inputTokens = 100, cachedInputTokens = 200))
        assertEquals(100L, partial.totalTokens)
        assertEquals(100L, partial.cachedInputTokens)
        assertEquals(0L, partial.outputReportedCalls)
        assertFalse(partial.isComplete)
        val sum = partial + TokenUsageTotals().addRequest(ModelRequestUsage("q", "n", 25, 15, 0))
        assertEquals(140L, sum.totalTokens)
        assertEquals(2L, sum.calls)
        assertFalse(sum.isComplete)
    }
}
