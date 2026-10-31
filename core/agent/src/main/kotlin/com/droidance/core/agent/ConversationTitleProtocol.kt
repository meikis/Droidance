// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Optional presentation metadata, separate from the reply and execution authority. */
object ConversationTitleProtocol {
    const val OPEN = "<conversation_title>"
    const val CLOSE = "</conversation_title>"
    const val MAX_TITLE_CHARS = 36
    private val json = Json { ignoreUnknownKeys = false }

    @Serializable
    private data class Envelope(val title: String)

    data class Result(val title: String?, val body: String)

    const val ARGUMENT = "conversation_title"
    data class ToolResult(val toolCalls: List<ToolCall>, val title: String?)

    fun addParameter(tools: List<ToolDefinition>, description: String): List<ToolDefinition> {
        require(description.isNotBlank())
        return tools.map { tool ->
            require(ARGUMENT !in tool.parameters)
            tool.copy(parameters = tool.parameters + (ARGUMENT to ToolParameter(
                type = "string", description = description, required = false
            )))
        }
    }

    fun extractToolCalls(calls: List<ToolCall>): ToolResult {
        val titles = mutableSetOf<String>()
        val stripped = calls.map { call ->
            val args = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
                ?: return@map call
            val raw = (args[ARGUMENT] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            validTitle(raw)?.let(titles::add)
            if (ARGUMENT !in args) call
            else call.copy(arguments = JsonObject(args - ARGUMENT).toString())
        }
        return ToolResult(stripped, titles.singleOrNull())
    }

    fun extract(text: String): Result {
        val framed = text.trimStart()
        if (!framed.startsWith(OPEN)) return Result(null, text)
        val end = framed.indexOf(CLOSE, OPEN.length)
        if (end < 0) return Result(null, "")
        val body = framed.substring(end + CLOSE.length).trimStart()
        val envelope = runCatching {
            json.decodeFromString<Envelope>(framed.substring(OPEN.length, end))
        }.getOrNull()
        val title = validTitle(envelope?.title)
        // Multiple envelopes are ambiguous; ignore all naming metadata while retaining the reply.
        return Result(title.takeUnless { body.startsWith(OPEN) }, PublicAssistantText.sanitize(body))
    }

    private fun validTitle(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_TITLE_CHARS &&
            it.none(Char::isISOControl) && PublicAssistantText.sanitize(it) == it
    }
}
