// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

/** A single model summarizes every source byte, in bounded requests if a tool result is large.
 * No checkpoint is committed until all requests complete and the resulting projection fits. */
class ContextSummaryRunner(private val estimateText: (String) -> Int) {
    suspend fun summarize(
        source: String,
        inputBudget: Int,
        promptTokens: Int,
        generate: suspend (String, String) -> String
    ): String {
        require(source.isNotBlank())
        var offset = 0
        var summary = ""
        while (offset < source.length) {
            val available = inputBudget - promptTokens - estimateText(summary)
            require(available > 0) { "Compaction summary leaves no source capacity; history retained" }
            var low = 0
            var high = source.length - offset
            while (low < high) {
                val mid = low + (high - low + 1) / 2
                if (estimateText(source.substring(offset, offset + mid)) <= available) low = mid
                else high = mid - 1
            }
            // Do not split a UTF-16 surrogate pair at the transport boundary.
            if (low > 0 && offset + low < source.length && source[offset + low - 1].isHighSurrogate()) low--
            require(low > 0) { "Compaction source cannot fit; history retained" }
            summary = generate(summary, source.substring(offset, offset + low)).trim()
            require(summary.isNotBlank()) { "Compaction returned empty content; history retained" }
            offset += low
        }
        return summary
    }
}
