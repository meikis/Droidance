// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenAIResponsesRuntimeContextTest {
    @Test fun initialAndContinuedRequestsBothDeliverCurrentNamingState() {
        val current = "<conversation_title_context>{\"update_allowed\":false,\"title_state\":\"generated\"}</conversation_title_context>"
        listOf(false, true).forEach { continued ->
            val state = OpenAIResponsesTurnState().apply { if (continued) commitResponse("previous") }
            val request = request(listOf(ChatMessage(Role.SYSTEM, current), ChatMessage(Role.USER, "Continue")), state)
            val input = request.getValue("input").jsonArray
            assertEquals(2, input.size)
            assertEquals("user", input.first().jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals("<runtime_control>\n$current\n</runtime_control>", text(input.first()))
            assertEquals("Continue", text(input.last()))
        }
    }

    @Test fun continuationPreservesNewControlOrderWithoutReplayingPreviousNamingState() {
        val request = request(listOf(
            ChatMessage(Role.SYSTEM, "old naming state"),
            ChatMessage(Role.USER, "original task"),
            ChatMessage(Role.ASSISTANT, "previous response"),
            ChatMessage(Role.TOOL, "tool receipt", toolCallId = "call_1"),
            ChatMessage(Role.SYSTEM, "current naming state"),
            ChatMessage(Role.USER, "new user goal")
        ), OpenAIResponsesTurnState().apply { commitResponse("previous") })
        val input = request.getValue("input").jsonArray
        assertEquals("previous", request.getValue("previous_response_id").jsonPrimitive.content)
        assertEquals(3, input.size)
        assertEquals("function_call_output", input[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("tool receipt", input[0].jsonObject.getValue("output").jsonPrimitive.content)
        assertEquals("<runtime_control>\ncurrent naming state\n</runtime_control>", text(input[1]))
        assertEquals("new user goal", text(input[2]))
        assertFalse(input.toString().contains("old naming state"))
        assertFalse(input.toString().contains("previous response"))
    }

    private fun text(item: JsonElement) = item.jsonObject.getValue("content").jsonArray.single()
        .jsonObject.getValue("text").jsonPrimitive.content

    private fun request(messages: List<ChatMessage>, state: OpenAIResponsesTurnState) =
        OpenAIResponsesProtocol.buildRequest("test-model", messages, emptyList(), "Configured policy",
            supportsVision = false, maxOutputTokens = null, state = state)
}
