// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTitleProtocolTest {
    @Test
    fun diagnostics_distinguish_missing_invalid_filtered_and_accepted_without_recording_text() {
        val calls = listOf("{}", "{\"action_title\":42}", "{\"action_title\":\" \"}",
            "{\"action_title\":\"private\"}", "{\"action_title\":\"read\"}", "invalid")
            .mapIndexed { i, args -> ToolCall("$i", "read", args) }
        val result = ActionTitleProtocol.extract(calls) { if (it == "private") "" else it }
        assertEquals(listOf(ActionTitleProtocol.Status.ABSENT, ActionTitleProtocol.Status.INVALID_TYPE,
            ActionTitleProtocol.Status.EMPTY, ActionTitleProtocol.Status.FILTERED,
            ActionTitleProtocol.Status.ACCEPTED, ActionTitleProtocol.Status.INVALID_ARGUMENTS),
            result.statusByProviderOrder.values.toList())
        assertEquals(mapOf(4 to "read"), result.titlesByProviderOrder)
    }

    @Test
    fun model_schema_adds_a_presentation_field_without_changing_executor_fields() {
        val original = ToolDefinition("read", "Read state", mapOf("op" to ToolParameter("string", "Operation")))
        val exposed = ActionTitleProtocol.addParameter(listOf(original), "Public title").single()
        assertEquals(original.parameters["op"], exposed.parameters["op"])
        assertEquals("Public title", exposed.parameters.getValue("action_title").description)
        assertTrue(exposed.parameters.getValue("action_title").required)
        assertFalse(original.parameters.containsKey("action_title"))
    }

    @Test
    fun titles_are_bound_by_order_and_removed_before_execution() {
        val result = ActionTitleProtocol.extract(listOf(
            ToolCall("duplicate", "screen_act", """{"action_title":"打开筛选","actions":[{"op":"click","x":100,"y":200}]}"""),
            ToolCall("duplicate", "screen_act", """{"action_title":"  设置最低价50元\n ","actions":[{"op":"type_text","text":"50"}]}""")
        ))
        assertEquals(mapOf(0 to "打开筛选", 1 to "设置最低价50元"), result.titlesByProviderOrder)
        assertEquals(Json.parseToJsonElement("""{"actions":[{"op":"click","x":100,"y":200}]}"""),
            Json.parseToJsonElement(result.toolCalls[0].arguments))
        assertEquals(Json.parseToJsonElement("""{"actions":[{"op":"type_text","text":"50"}]}"""),
            Json.parseToJsonElement(result.toolCalls[1].arguments))
    }

    @Test
    fun unusable_titles_do_not_block_or_leak_into_executable_arguments() {
        listOf("null", "42", "{}", "[]", "\" \"", "\"${"x".repeat(97)}\"").forEach { value ->
            val result = ActionTitleProtocol.extract(listOf(
                ToolCall("", "android_read", """{"op":"screen","action_title":$value}""")
            ))
            assertTrue(result.titlesByProviderOrder.isEmpty())
            assertFalse(result.toolCalls.single().arguments.contains("action_title"))
            assertEquals("""{"op":"screen"}""", result.toolCalls.single().arguments)
        }
        val malformed = ToolCall("id", "screen_act", "not json")
        assertEquals(malformed, ActionTitleProtocol.extract(listOf(malformed)).toolCalls.single())
    }

    @Test
    fun public_text_filter_runs_before_titles_are_normalized_and_frozen() {
        val calls = listOf(ToolCall("id", "read", """{"action_title":"internal\n公开标题"}"""))
        val result = ActionTitleProtocol.extract(calls) { it.substringAfter('\n') }
        assertEquals(mapOf(0 to "公开标题"), result.titlesByProviderOrder)
        assertTrue(ActionTitleProtocol.extract(calls) { "" }.titlesByProviderOrder.isEmpty())
    }
}
