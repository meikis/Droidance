// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesPromptCacheKeyTest {
    private val request = buildJsonObject { put("model", "fixture-model"); put("input", JsonPrimitive("goal")) }
    private val scope = ModelDiagnosticScope("thread-a", "turn-1", "round-1")

    @Test fun documentedEndpointsReceiveOneDigestPerStablePrefix() {
        val ark = "https://ark.cn-beijing.volces.com/api/coding/v3/responses"
        val first = ResponsesPromptCacheKey.apply(request, ark, scope).getValue("prompt_cache_key").jsonPrimitive.content
        val nextRound = ResponsesPromptCacheKey.apply(request, ark, scope.copy(turnId = "turn-2", roundId = "round-9"))
            .getValue("prompt_cache_key").jsonPrimitive.content
        val otherThread = ResponsesPromptCacheKey.apply(request, ark, scope.copy(threadId = "thread-b"))
            .getValue("prompt_cache_key").jsonPrimitive.content
        val otherInstructions = ResponsesPromptCacheKey.apply(JsonObject(request + ("instructions" to JsonPrimitive("other"))), ark, scope)
            .getValue("prompt_cache_key").jsonPrimitive.content

        assertEquals(first, nextRound)
        assertEquals(first, otherThread)
        assertNotEquals(first, otherInstructions)
        assertTrue(first.startsWith("droidance-"))
        assertFalse(first.contains("thread-a"))
        assertTrue(ResponsesPromptCacheKey.supports("https://api.openai.com/v1/responses"))
    }

    @Test fun otherEndpointsAndExplicitKeysAreUnchanged() {
        assertSame(request, ResponsesPromptCacheKey.apply(request, "https://gateway.example/v1/responses", scope))
        assertSame(request, ResponsesPromptCacheKey.apply(request, "https://ark.cn-beijing.volces.com/api/v3/responses", null))
        assertFalse(ResponsesPromptCacheKey.supports("http://ark.cn-beijing.volces.com/api/v3/responses"))
        val explicit = buildJsonObject { put("prompt_cache_key", "caller") }
        assertSame(explicit, ResponsesPromptCacheKey.apply(explicit, "https://api.openai.com/v1/responses", scope))
    }
}
