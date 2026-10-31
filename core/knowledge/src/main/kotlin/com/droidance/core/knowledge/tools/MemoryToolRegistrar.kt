// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.knowledge.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.knowledge.memory.DurableMemoryApplyResult
import com.droidance.core.knowledge.memory.DurableMemoryDocument
import com.droidance.core.knowledge.memory.DurableMemoryOperation
import com.droidance.core.knowledge.memory.DurableMemoryScope
import com.droidance.core.knowledge.memory.DurableMemoryUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Persists memory inside the ordinary tool/result loop, before the model finishes the Turn. */
class MemoryToolRegistrar(
    private val registerTool: (Tool) -> Unit,
    private val applyUpdates: suspend (List<DurableMemoryUpdate>) -> Result<DurableMemoryApplyResult>
) {
    fun registerAll() = registerTool(object : Tool {
        override val name = "update_memory"
        override val tier = ToolTier.CORE
        override val category = ToolCategory.META
        override val definition = ToolDefinition(
            name, "Apply user-grounded durable memory changes and return the actual persistence result.",
            mapOf("updates" to ToolParameter(
                "array", "Durable memory updates.",
                items = ToolParameter("object", "One memory change.", properties = linkedMapOf(
                    "scope" to ToolParameter("string", "Memory scope.", enumValues = listOf("user_profile", "long_term")),
                    "operation" to ToolParameter("string", "Change operation.", enumValues = listOf("upsert", "delete")),
                    "key" to ToolParameter("string", "Stable lowercase semantic key."),
                    "category" to ToolParameter("string", "Stable lowercase ASCII category tag (a-z, 0-9, '.', '_', '-'); reuse the retrieved tag when updating. Omit for general.", false),
                    "content" to ToolParameter("string", "Memory content; omitted for deletion.", false),
                    "expected_revision" to ToolParameter("string", "Exact durable item revision from session_search/expansion, never index_revision; absent only for a confirmed new key."),
                    "expires_at" to ToolParameter("string", "Optional ISO-8601 expiration with offset.", false),
                    "source_quote" to ToolParameter("string", "Exact supporting user-authored text.")
                ), additionalProperties = false)
            ))
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val updates = runCatching {
                com.droidance.core.knowledge.memory.DurableMemoryUpdateParser.parse(arguments)
            }.getOrElse { return ToolProtocolResult.invalidArgument("updates", "invalid_value") }
            val applied = try {
                applyUpdates(updates).getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ToolProtocolResult.failed("MEMORY_UPDATE_FAILED", retryable = true,
                    details = buildJsonObject {
                        put("persistence_confirmed", false)
                        put("partial_writes_possible", true)
                    })
            }
            val details = buildJsonObject {
                put("items", buildJsonArray {
                    applied.items.forEach { item -> add(buildJsonObject {
                        put("key", item.key); put("status", item.status)
                        put("revision", item.revision); put("error", item.error)
                    }) }
                })
                put("persistence_confirmed", applied.items.none { it.status == "failed" || it.status == "rejected" })
                put("requested", applied.requested)
                put("grounded", applied.grounded)
                put("changed", applied.changed)
                put("rejected", applied.requested - applied.grounded)
            }
            if (applied.items.any { it.status == "failed" }) {
                return ToolProtocolResult.failed("MEMORY_COMMIT_INCOMPLETE", retryable = false, details = details)
                    .copy(stateChanged = true.takeIf { applied.changed > 0 })
            }
            if (applied.grounded == 0) {
                return ToolProtocolResult.notExecuted("MEMORY_SOURCE_REJECTED", false, details)
            }
            return ToolProtocolResult.success(buildJsonObject {
                put("execution_status", "SUCCEEDED")
                put("code", when {
                    applied.grounded < applied.requested -> "MEMORY_PARTIALLY_APPLIED"
                    applied.changed == 0 -> "MEMORY_UNCHANGED"
                    else -> "MEMORY_UPDATED"
                })
                put("details", details)
            }.toString()).copy(stateChanged = applied.changed > 0)
        }
    })
}
