// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.model.llm.ModelRequestUsage
import kotlinx.serialization.Serializable

@Serializable
data class TokenUsageTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val calls: Long = 0,
    val inputReportedCalls: Long = 0,
    val outputReportedCalls: Long = 0,
    val cacheReportedCalls: Long = 0
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    val hasReportedTokens: Boolean get() = inputReportedCalls > 0 || outputReportedCalls > 0
    val isComplete: Boolean get() = calls > 0 && inputReportedCalls == calls && outputReportedCalls == calls

    fun addRequest(usage: ModelRequestUsage): TokenUsageTotals {
        val input = usage.inputTokens?.coerceAtLeast(0)
        val output = usage.outputTokens?.coerceAtLeast(0)
        val cached = usage.cachedInputTokens?.coerceAtLeast(0)?.let {
            if (input != null) it.coerceAtMost(input) else it
        }
        return this + TokenUsageTotals(
            inputTokens = input ?: 0,
            outputTokens = output ?: 0,
            cachedInputTokens = cached ?: 0,
            calls = 1,
            inputReportedCalls = if (input != null) 1 else 0,
            outputReportedCalls = if (output != null) 1 else 0,
            cacheReportedCalls = if (cached != null) 1 else 0
        )
    }

    operator fun plus(other: TokenUsageTotals) = TokenUsageTotals(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cachedInputTokens + other.cachedInputTokens,
        calls + other.calls,
        inputReportedCalls + other.inputReportedCalls,
        outputReportedCalls + other.outputReportedCalls,
        cacheReportedCalls + other.cacheReportedCalls
    )
}
