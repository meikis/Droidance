// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.*

/** Shared result/evidence contract; a navigation link is presentation metadata. */
data class PublicResultEntry(val text: String, val evidenceRefs: List<String> = emptyList(), val deeplink: String? = null) {
    companion object {
        fun schema(textDescription: String, evidenceDescription: String, evidenceRequired: Boolean = false) = ToolParameter(
            "object", "Public result: text with optional evidence_refs and deeplink.", properties = linkedMapOf(
                "text" to ToolParameter("string", textDescription),
                "evidence_refs" to ToolParameter("array", evidenceDescription, required = evidenceRequired,
                    items = ToolParameter("string", "Exact original source reference.", minLength = 1)),
                "deeplink" to ToolParameter("string", "Optional exact navigation URI established by an original tool receipt. Omit when no verified destination is needed; never invent a link while formatting or repairing a result.", required = false)
            ), additionalProperties = false
        )

        fun parse(value: JsonElement): PublicResultEntry {
            val row = value as? JsonObject ?: throw IllegalArgumentException("expected_object_entry")
            require(row.keys.contains("text") &&
                row.keys.all { it in setOf("text", "evidence_refs", "deeplink") }) { "invalid_result_fields" }
            fun string(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("expected_string:$key")
            val text = string("text")
            require(text.isNotBlank()) { "expected_nonblank_text" }
            val evidenceRefs = if ("evidence_refs" in row) references(row.getValue("evidence_refs")) else emptyList()
            return PublicResultEntry(text, evidenceRefs, if ("deeplink" in row) string("deeplink") else null)
        }

        fun references(value: JsonElement): List<String> {
            val array = value as? JsonArray ?: throw IllegalArgumentException("expected_array:evidence_refs")
            return array.map { entry ->
                (entry as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    ?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("expected_reference_string")
            }.distinct()
        }
    }

    fun markdown(): String {
        if (deeplink.isNullOrBlank()) return text
        val firstLine = text.substringBefore('\n')
        return if (firstLine.isBlank()) text else "[$firstLine]($deeplink)" + text.removePrefix(firstLine)
    }
}
