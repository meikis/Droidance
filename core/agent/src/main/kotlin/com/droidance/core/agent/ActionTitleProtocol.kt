// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Presentation metadata only: never executable arguments or completion evidence. Required in the schema so
 * step descriptions have a place other than the conversation; a missing title still never blocks a call.
 */
object ActionTitleProtocol {
    const val ARGUMENT = "action_title"
    private const val MAX_TITLE_LENGTH = 96

    enum class Status { ACCEPTED, ABSENT, INVALID_ARGUMENTS, INVALID_TYPE, EMPTY, FILTERED, TOO_LONG }

    data class Result(
        val toolCalls: List<ToolCall>,
        val titlesByProviderOrder: Map<Int, String>,
        val statusByProviderOrder: Map<Int, Status>
    )

    fun addParameter(tools: List<ToolDefinition>, description: String): List<ToolDefinition> {
        require(description.isNotBlank())
        return tools.map { tool ->
            require(ARGUMENT !in tool.parameters)
            tool.copy(parameters = tool.parameters + (ARGUMENT to ToolParameter(
                type = "string", description = description, required = true
            )))
        }
    }

    fun extract(calls: List<ToolCall>, sanitizePublicText: (String) -> String = { it }): Result {
        val titles = linkedMapOf<Int, String>()
        val statuses = linkedMapOf<Int, Status>()
        val stripped = calls.mapIndexed { order, call ->
            val arguments = runCatching { Json.parseToJsonElement(call.arguments) }.getOrNull()
                as? JsonObject ?: run {
                    statuses[order] = Status.INVALID_ARGUMENTS
                    return@mapIndexed call
                }
            val raw = (arguments[ARGUMENT] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val clean = raw?.let(sanitizePublicText)?.replace(Regex("[\\s\\p{Cc}]+"), " ")?.trim()
            val status = when {
                ARGUMENT !in arguments -> Status.ABSENT
                raw == null -> Status.INVALID_TYPE
                raw.isBlank() -> Status.EMPTY
                clean.isNullOrEmpty() -> Status.FILTERED
                clean.length > MAX_TITLE_LENGTH -> Status.TOO_LONG
                else -> Status.ACCEPTED
            }
            statuses[order] = status
            val title = clean.takeIf { status == Status.ACCEPTED }
            if (title != null) titles[order] = title
            if (ARGUMENT !in arguments) call else call.copy(
                arguments = JsonObject(arguments - ARGUMENT).toString()
            )
        }
        return Result(stripped, titles, statuses)
    }
}
