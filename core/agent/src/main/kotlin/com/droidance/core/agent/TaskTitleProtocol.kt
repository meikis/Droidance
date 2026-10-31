// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One task name from the ordinary tool response; naming never grants execution authority. */
object TaskTitleProtocol {
    const val ARGUMENT = "task_title"
    private const val MAX_TITLE_LENGTH = 24

    data class Result(val toolCalls: List<ToolCall>, val title: String?)

    fun addParameter(tools: List<ToolDefinition>, description: String): List<ToolDefinition> {
        require(description.isNotBlank())
        return tools.map { tool ->
            require(ARGUMENT !in tool.parameters)
            tool.copy(parameters = tool.parameters + (ARGUMENT to ToolParameter(
                type = "string", description = description, required = false
            )))
        }
    }

    fun extract(calls: List<ToolCall>): Result {
        var title: String? = null
        val stripped = calls.map { call ->
            val arguments = runCatching { Json.parseToJsonElement(call.arguments) }.getOrNull()
                as? JsonObject ?: return@map call
            val raw = (arguments[ARGUMENT] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val candidate = raw?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= MAX_TITLE_LENGTH &&
                    it.none(Char::isISOControl) && PublicAssistantText.sanitize(it) == it
            }
            if (title == null) title = candidate
            if (ARGUMENT !in arguments) call else call.copy(arguments = JsonObject(arguments - ARGUMENT).toString())
        }
        return Result(stripped, title)
    }
}
