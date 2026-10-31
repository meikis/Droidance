// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenRouterPromptCacheTest {
    @Test fun settledHistoryHasItsOwnBoundaryBeforeImagesAndMutableTail() {
        val history = listOf(
            com.droidance.core.model.llm.models.ChatMessage(com.droidance.core.model.llm.models.Role.USER,
                "settled", cacheablePrefix = true),
            com.droidance.core.model.llm.models.ChatMessage(com.droidance.core.model.llm.models.Role.USER, "current")
        )
        val raw = Json.parseToJsonElement("""{"model":"anthropic/example","messages":[
            {"role":"system","content":"rules"},
            {"role":"user","content":[{"type":"text","text":"settled"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]},
            {"role":"user","content":"current"}],"tools":[]}""").jsonObject
        val cached = OpenRouterPromptCache.apply(raw, endpoint, history)
        val before = raw.getValue("messages").jsonArray
        val after = cached.getValue("messages").jsonArray
        val blocks = after[1].jsonObject.getValue("content").jsonArray
        assertTrue("cache_control" in blocks[0].jsonObject)
        assertEquals(before[1].jsonObject.getValue("content").jsonArray[1], blocks[1])
        assertEquals(before[2], after[2])
        assertEquals(raw["tools"], cached["tools"])
        assertEquals(cached, OpenRouterPromptCache.apply(cached, endpoint, history))
        val disabled = OpenRouterPromptCache.apply(raw, endpoint, history.map { it.copy(cacheablePrefix = false) })
        assertEquals(before[1], disabled.getValue("messages").jsonArray[1])
    }

    private val endpoint = "https://openrouter.ai/api/v1/chat/completions"
    private val request = Json.parseToJsonElement("""{"model":"anthropic/example","messages":[{"role":"system","content":"immutable rules"},{"role":"user","content":"changing screen"}],"tools":[]}""").jsonObject

    @Test fun cachesStaticPrefixAndAdvancingHistoryWithoutChangingMessagesOrTools() {
        val cached = OpenRouterPromptCache.apply(request, endpoint)
        val messages = cached.getValue("messages").jsonArray
        val content = messages[0].jsonObject.getValue("content").jsonArray.single().jsonObject
        assertEquals("immutable rules", content.getValue("text").jsonPrimitive.content)
        assertEquals("ephemeral", content.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(request.getValue("messages").jsonArray[1], messages[1])
        assertEquals(request["tools"], cached["tools"])
        assertEquals("ephemeral", cached.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(cached, OpenRouterPromptCache.apply(cached, endpoint))
    }

    @Test fun respectsExplicitConfigurationAndLeavesOtherProtocolsUntouched() {
        for (url in listOf("https://other.example/api", "http://openrouter.ai/api", "https://openrouter.ai.other.example/api"))
            assertSame(request, OpenRouterPromptCache.apply(request, url))
        val other = JsonObject(request + ("model" to JsonPrimitive("openai/example")))
        assertSame(other, OpenRouterPromptCache.apply(other, endpoint))
        val configured = JsonObject(request + ("cache_control" to JsonNull))
        assertSame(configured, OpenRouterPromptCache.apply(configured, endpoint))
        val noSystem = JsonObject(request + ("messages" to JsonArray(request.getValue("messages").jsonArray.drop(1))))
        assertSame(noSystem, OpenRouterPromptCache.apply(noSystem, endpoint))
    }

    @Test fun responsesUsesAutomaticCachingWithoutAnthropicInputMarkers() {
        val responses = Json.parseToJsonElement("""{"model":"anthropic/example","instructions":"rules","input":[{"role":"user","content":[{"type":"input_text","text":"goal"}]}]}""").jsonObject
        val cached = OpenRouterPromptCache.apply(responses, endpoint)
        assertEquals(responses["input"], cached["input"])
        assertEquals(responses["instructions"], cached["instructions"])
        assertEquals("ephemeral", cached.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(cached, OpenRouterPromptCache.apply(cached, endpoint))
    }
}
