// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The only strategy-level loop guard kept by the harness.
 *
 * It does not infer progress, relevance, routes, or user intent. It only prevents the exact same
 * state-changing call from being executed twice against the same grounded observation.
 */
class ExactActionRepeatGuard {
    private val executed = mutableSetOf<String>()

    @Synchronized
    fun wasExecuted(observationId: String, toolName: String, arguments: String): Boolean =
        key(observationId, toolName, arguments) in executed

    @Synchronized
    fun record(observationId: String, toolName: String, arguments: String) {
        executed += key(observationId, toolName, arguments)
    }

    /** Only an explicit non-delivery receipt releases a reserved call. Unknown effects remain blocked. */
    @Synchronized
    fun settle(observationId: String, toolName: String, arguments: String,
        status: com.droidance.core.agent.event.ToolExecutionStatus) {
        if (status == com.droidance.core.agent.event.ToolExecutionStatus.NOT_EXECUTED) {
            executed.remove(key(observationId, toolName, arguments))
        }
    }

    @Synchronized
    fun reset() {
        executed.clear()
    }

    private fun key(observationId: String, toolName: String, arguments: String): String =
        listOf(observationId.trim(), toolName.trim(), canonicalArguments(arguments))
            .joinToString("\u0000")

    private fun canonicalArguments(arguments: String): String = runCatching {
        canonicalize(Json.parseToJsonElement(arguments)).toString()
    }.getOrElse {
        arguments.trim().replace(Regex("\\s+"), " ")
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }.associate { (key, value) ->
                key to canonicalize(value)
            }
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}
