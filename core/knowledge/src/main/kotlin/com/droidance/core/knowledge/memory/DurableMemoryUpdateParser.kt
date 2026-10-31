// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import kotlinx.serialization.json.*

/** Shared argument contract for explicit and deferred memory writes. */
object DurableMemoryUpdateParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("```")) {
            val firstNewline = trimmed.indexOf('\n')
            if (firstNewline != -1) {
                val content = trimmed.substring(firstNewline + 1)
                val lastFence = content.lastIndexOf("```")
                return (if (lastFence != -1) content.substring(0, lastFence) else content).trim()
            }
        }
        return trimmed
    }

    fun parse(arguments: String): List<DurableMemoryUpdate> {
        val cleanArgs = stripCodeFences(arguments)
        val root = json.parseToJsonElement(cleanArgs).jsonObject
        val rawUpdates = root["updates"]
        val items: JsonArray = when {
            rawUpdates is JsonArray -> rawUpdates
            rawUpdates is JsonObject -> JsonArray(listOf(rawUpdates))
            root.containsKey("scope") && (root.containsKey("key") || root.containsKey("operation")) -> JsonArray(listOf(root))
            else -> throw IllegalArgumentException("Missing or invalid 'updates' element")
        }
        require(items.isNotEmpty()) { "Updates array cannot be empty" }

        return items.map { item ->
            val value = item.jsonObject
            fun text(vararg keys: String): String {
                for (key in keys) {
                    val primitive = value[key] as? JsonPrimitive
                    if (primitive != null && primitive !is JsonNull && primitive.isString && primitive.content.isNotBlank()) {
                        return primitive.content.trim()
                    }
                }
                return ""
            }
            fun required(vararg keys: String): String = text(*keys).also {
                require(it.isNotBlank()) { "Missing required field: ${keys.joinToString()}" }
            }

            val scope = when (required("scope")) {
                "user_profile" -> DurableMemoryScope.USER_PROFILE
                "long_term" -> DurableMemoryScope.LONG_TERM
                else -> error("Invalid scope")
            }

            val operation = when (required("operation")) {
                "upsert" -> DurableMemoryOperation.UPSERT
                "delete" -> DurableMemoryOperation.DELETE
                else -> error("Invalid operation")
            }

            val key = required("key")
            val category = text("category").ifBlank { "general" }
            val content = text("content")
            val sourceQuote = required("source_quote", "sourceQuote")
            val rawRevision = required("expected_revision", "expectedRevision")
            val expectedRevision = if (rawRevision.contains("absent", ignoreCase = true) || rawRevision.contains("new key", ignoreCase = true)) {
                "absent"
            } else {
                rawRevision
            }
            val expiresAt = text("expires_at", "expiresAt").ifBlank { null }

            DurableMemoryUpdate(
                scope = scope,
                operation = operation,
                key = key,
                category = category,
                content = content,
                sourceQuote = sourceQuote,
                expectedRevision = expectedRevision,
                expiresAt = expiresAt
            ).also(DurableMemoryDocument::validate)
        }
    }
}
