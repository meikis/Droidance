// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.agent.event.MemoryContextLoadedEventPayload

object MemoryContextPromptRenderer {
    fun render(payload: MemoryContextLoadedEventPayload, maxChars: Int): String {
        require(maxChars >= 256)
        if (payload.items.isEmpty()) return ""
        val prefix = buildString {
            appendLine("<memory_index>")
        }
        val suffix = "</memory_index>"
        val lines = mutableListOf<String>()
        var used = prefix.length + suffix.length
        payload.items.forEach { item ->
            val source = item.sourceThroughSequence?.let { "; source_through_sequence=$it" }.orEmpty()
            val summary = item.summary.replace(Regex("\\s+"), " ").trim().take(MAX_RENDERED_SUMMARY_CHARS)
            val line = "- ref=${item.ref}; scope=${item.scope.name.lowercase()}$source: $summary\n"
            if (used + line.length <= maxChars) {
                lines += line
                used += line.length
            }
        }
        return buildString {
            append(prefix)
            lines.forEach(::append)
            append(suffix)
        }
    }

    private const val MAX_RENDERED_SUMMARY_CHARS = 240
}
