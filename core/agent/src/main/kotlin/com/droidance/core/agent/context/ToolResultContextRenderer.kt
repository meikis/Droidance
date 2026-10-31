// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A finite request view of a retained receipt, not a replacement for its durable bytes. */
object ToolResultContextRenderer {
    // Keep existing bounded step reads and escaped 12K artifact pages directly readable.
    const val MAX_INLINE_CHARS = 32_000

    fun render(item: ToolResultAgentItem): String {
        val full = fullText(item)
        if (full.length <= MAX_INLINE_CHARS) return full
        // AgentItems do not carry sensitive-field declarations. Never invent a safe preview;
        // the original event's scoped artifact reader remains responsible for redaction.
        return buildJsonObject {
            put("kind", "retained_tool_result")
            put("event_ref", item.itemId)
            put("call_ref", item.callId)
            put("execution_status", item.result.executionStatus.name)
            put("evidence_status", item.result.evidenceStatus.name)
            item.result.diagnosticCode?.takeIf { SAFE_DIAGNOSTIC_CODE.matches(it) }
                ?.let { put("diagnostic_code", it) }
            put("original_text_chars", full.length)
            put("context_text_omitted", true)
            put("retained_source_unchanged", true)
            put("read_tool", "session_read")
            put("read_arguments", buildJsonObject {
                put("mode", "artifact")
                put("ref", item.itemId)
                put("offset", 0)
            })
        }.toString()
    }

    /** Compaction summarizes the retained source, with its own full-text chunk budget. */
    fun fullText(item: ToolResultAgentItem): String = item.result.asReceiptText()

    private val SAFE_DIAGNOSTIC_CODE = Regex("[A-Z][A-Z0-9_]{0,95}")
}
