// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Immutable model-authored output and scope; contains no task-specific interpretation. */
data class CompletionReviewCandidate(
    val toolName: String,
    val scope: String,
    val entries: Map<String, JsonElement>,
    val originalArguments: JsonObject,
    val fileReference: JsonObject? = null
) {
    val id: String = digest(scope + entries.keys.sorted().joinToString("\n"))

    companion object {
        fun from(tool: String, arguments: String, fileReference: JsonObject? = null): CompletionReviewCandidate {
            val args = Json.parseToJsonElement(arguments).jsonObject
            val bodyKey = if (tool == "present_result_card") "items" else "results"
            val body = (args[bodyKey] as? JsonArray).orEmpty()
            val scope = digest(canonical(JsonObject(args.filterKeys {
                it !in setOf(bodyKey, "action_title", "terminal_observation_ref", "evidence_refs")
            })).toString() + tool)
            // The position is part of public output, so reordering also requires review.
            val entries = body.mapIndexed { index, entry ->
                digest("$index:" + canonical(entry)) to entry
            }.toMap()
            return CompletionReviewCandidate(tool, scope, entries, args, fileReference)
        }

        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { (key, entry) ->
                if (key == "evidence_refs" && entry is JsonArray)
                    JsonArray(com.droidance.core.agent.harness.PublicResultEntry.references(entry).sorted().map(::JsonPrimitive))
                else canonical(entry)
            })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
