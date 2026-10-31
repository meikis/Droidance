// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenUsageRepositoryTest {
    @Test
    fun requestCountersKeepTokenAndCacheRateInputsDistinct() {
        val initial = TokenUsageRepository.DailyUsage(
            date = "2026-08-22",
            provider = "provider",
            model = "model",
            promptTokens = 0,
            completionTokens = 0,
            calls = 0
        )

        val cacheHit = initial.addRequest(
            promptTokens = 100,
            completionTokens = 20,
            cachedInputTokens = 60,
            cacheFieldsReported = true
        )
        val usageMissing = cacheHit.addRequest(
            promptTokens = 0,
            completionTokens = 0,
            cachedInputTokens = 0,
            cacheFieldsReported = false
        )
        val cacheUnreported = usageMissing.addRequest(
            promptTokens = 40,
            completionTokens = 10,
            cachedInputTokens = 20,
            cacheFieldsReported = false
        )

        assertEquals(3L, cacheUnreported.calls)
        assertEquals(140L, cacheUnreported.promptTokens)
        assertEquals(30L, cacheUnreported.completionTokens)
        assertEquals(60L, cacheUnreported.cachedInputTokens)
        assertEquals(100L, cacheUnreported.cacheReportedInputTokens)
        assertEquals(1L, cacheUnreported.cacheReportedCalls)
    }
}
