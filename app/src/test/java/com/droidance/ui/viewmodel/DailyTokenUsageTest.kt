// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyTokenUsageTest {
    @Test
    fun cacheHitRateUsesOnlyProviderReportedInputTokens() {
        val usage = DailyTokenUsage(
            date = "2026-08-31",
            promptTokens = 140,
            completionTokens = 30,
            cachedInputTokens = 60,
            cacheReportedInputTokens = 100,
            cacheReportedCalls = 1
        )

        assertEquals(0.6f, usage.cacheHitRate!!, 0.0001f)
    }

    @Test
    fun cacheHitRateIsUnavailableWhenProviderDidNotReportCacheFields() {
        val usage = DailyTokenUsage(
            date = "2026-08-31",
            promptTokens = 140,
            completionTokens = 30,
            cachedInputTokens = 20,
            cacheReportedInputTokens = 0,
            cacheReportedCalls = 0
        )

        assertNull(usage.cacheHitRate)
    }
}
