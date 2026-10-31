// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.*
import java.net.URI
import com.droidance.core.model.llm.models.ChatMessage

/** Static and settled-history breakpoints plus automatic caching of the current tail. */
object OpenRouterPromptCache {
    fun supports(endpoint: String, model: String): Boolean {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "openrouter.ai" && model.startsWith("anthropic/")
    }

    fun apply(request: JsonObject, endpoint: String, history: List<ChatMessage> = emptyList()): JsonObject {
        val model = (request["model"] as? JsonPrimitive)?.contentOrNull ?: return request
        if (!supports(endpoint, model)) return request
        // User-specified automatic caching takes precedence, including an explicit null.
        if ("cache_control" in request) return request
        // OpenRouter also supports Claude automatic caching on the Responses protocol.
        // Per-block Anthropic markers are not part of that protocol.
        if (request["input"] is JsonArray) return JsonObject(request +
            ("cache_control" to buildJsonObject { put("type", "ephemeral") }))
        val messages = request["messages"] as? JsonArray ?: return request
        val first = messages.firstOrNull() as? JsonObject ?: return request
        if ((first["role"] as? JsonPrimitive)?.contentOrNull != "system") return request
        val text = (first["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return request
        if (text.isBlank()) return request
        val content = buildJsonArray { add(buildJsonObject {
            put("type", "text"); put("text", text)
            put("cache_control", buildJsonObject { put("type", "ephemeral") })
        }) }
        // The automatic breakpoint covers a mutable decision tail. Write a separate
        // boundary before that tail so the next round can reuse settled history.
        val boundary = history.indexOfLast { it.cacheablePrefix && it.content.isNotBlank() }
            .takeIf { it >= 0 }?.plus(1)
        val marked = messages.mapIndexed { index, message ->
            if (index == 0) JsonObject(first + ("content" to content))
            else if (index == boundary) {
                val item = message as? JsonObject
                val body = item?.get("content")
                if (body is JsonPrimitive && body.isString && body.content.isNotBlank()) {
                    JsonObject(item + ("content" to buildJsonArray { add(buildJsonObject {
                        put("type", "text"); put("text", body.content)
                        put("cache_control", buildJsonObject { put("type", "ephemeral") })
                    }) }))
                } else if (body is JsonArray) {
                    val lastText = body.indexOfLast { block ->
                        (block as? JsonObject)?.get("type") == JsonPrimitive("text")
                    }
                    JsonObject(item + ("content" to JsonArray(body.mapIndexed { blockIndex, block ->
                        if (blockIndex == lastText) JsonObject(block.jsonObject +
                            ("cache_control" to buildJsonObject { put("type", "ephemeral") })) else block
                    })))
                } else message
            } else message
        }
        return JsonObject(request + mapOf(
            "messages" to JsonArray(marked),
            "cache_control" to buildJsonObject { put("type", "ephemeral") }
        ))
    }
}
