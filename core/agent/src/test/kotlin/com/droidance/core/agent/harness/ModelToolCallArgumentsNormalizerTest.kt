// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelToolCallArgumentsNormalizerTest {
    private val screenAct = mapOf("actions" to ToolParameter("array", "Actions", items = ToolParameter("object", "Action",
        properties = com.droidance.core.agent.tools.ScreenActionBatchProtocol.parameters("Clear"))))

    @Test
    fun ime_input_without_target_reuses_the_editor_click_before_it() {
        val call = ToolCall("call-1", "screen_act",
            """{"actions":[{"op":"click","x":300,"y":90},{"op":"type_text","text":"手机","clear":true,"method":"ime"},{"op":"press_key","key":"ENTER"}]}""")

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = false, parameters = screenAct)

        assertTrue(result.repaired)
        assertEquals(
            """{"actions":[{"op":"click","x":300,"y":90},{"op":"type_text","text":"手机","clear":true,"method":"ime","x":300,"y":90},{"op":"press_key","key":"ENTER"}]}""",
            result.toolCall.arguments
        )
        val unrelated = ToolCall("call-2", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"},{"op":"type_text","text":"a","clear":true,"method":"ime"}]}""")
        assertFalse(ModelToolCallArgumentsNormalizer.normalize(unrelated, false, screenAct).repaired)
    }

    @Test
    fun any_tool_schema_drives_lossless_scalar_canonicalization() {
        val parameters = mapOf(
            "limit" to ToolParameter("integer", "Limit"),
            "ratio" to ToolParameter("number", "Ratio"),
            "visible" to ToolParameter("boolean", "Visible"),
            "label" to ToolParameter("string", "Label"),
            "extras" to ToolParameter("object", "Extras", additionalPropertySchema = ToolParameter("integer", "Value"))
        )
        val call = ToolCall("call-1", "android_read",
            """{"limit":"20","ratio":"0.5","visible":"TRUE","label":12,"extras":{"a":"7","b":"x7"},"other":"9"}""")

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = false, parameters = parameters)

        assertEquals("""{"limit":20,"ratio":0.5,"visible":true,"label":"12","extras":{"a":7,"b":"x7"},"other":"9"}""",
            result.toolCall.arguments)
    }
    @Test
    fun quoted_plain_integers_become_integers_only_for_integer_action_fields() {
        val call = ToolCall("call-1", "screen_act",
            """{"actions":[{"op":"click","x":"300","y":"132"},{"op":"type_text","text":"300","clear":true},{"op":"click","x":"30.5","y":"0x10"}]}""")

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = false, parameters = screenAct)

        assertTrue(result.repaired)
        assertEquals(
            """{"actions":[{"op":"click","x":300,"y":132},{"op":"type_text","text":"300","clear":true},{"op":"click","x":"30.5","y":"0x10"}]}""",
            result.toolCall.arguments
        )
    }

    @Test
    fun strict_json_is_left_unchanged() {
        val call = ToolCall("call-1", "read", "{\"value\":1}")

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = true)

        assertFalse(result.repaired)
        assertEquals(call, result.toolCall)
    }

    @Test
    fun missing_array_close_before_parent_close_is_repaired_without_inventing_values() {
        val call = ToolCall(
            "call-1",
            "custom_tool",
            "{\"items\":[{\"key\":\"a\",\"fields\":{\"name\":\"A\"}}}}"
        )

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = true)

        assertTrue(result.repaired)
        assertEquals(
            "{\"items\":[{\"key\":\"a\",\"fields\":{\"name\":\"A\"}}]}",
            result.toolCall.arguments
        )
    }

    @Test
    fun missing_array_close_before_next_object_property_is_repaired() {
        val call = ToolCall(
            "call-1",
            "custom_tool",
            "{\"items\":[{\"key\":\"a\",\"fields\":{\"name\":\"A\"}}, \"scope\":\"source\", \"coverage\":\"complete\"}"
        )

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = true)

        assertTrue(result.repaired)
        assertTrue(result.toolCall.arguments.contains("\"items\":["))
        assertTrue(result.toolCall.arguments.contains("\"scope\":\"source\""))
    }

    @Test
    fun one_unmatched_array_close_before_root_close_is_removed() {
        val call = ToolCall(
            "call-1",
            "custom_tool",
            "{\"scope\":\"source\",\"items\":[{\"key\":\"a\",\"fields\":{\"name\":\"A\"}}]]}"
        )

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = true)

        assertTrue(result.repaired)
        assertEquals(
            "{\"scope\":\"source\",\"items\":[{\"key\":\"a\",\"fields\":{\"name\":\"A\"}}]}",
            result.toolCall.arguments
        )
    }

    @Test
    fun multiple_unmatched_array_closes_are_not_guessed_away() {
        val call = ToolCall("call-1", "custom_tool", "{\"items\":[]]]}")

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = true)

        assertFalse(result.repaired)
        assertEquals(call.arguments, result.toolCall.arguments)
    }

    @Test
    fun a_property_value_missing_only_its_opening_quote_is_restored() {
        val call = ToolCall("call-1", "done",
            """{"status":"done","results":[{"text":"first"},{"text": **Risks**: one\\n- two"}],"unresolved_requirements":[]}""")

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = true)

        assertTrue(result.repaired)
        assertEquals("""{"status":"done","results":[{"text":"first"},{"text":"**Risks**: one\\n- two"}],"unresolved_requirements":[]}""",
            result.toolCall.arguments)
        val unterminated = ToolCall("call-2", "done", """{"text": abc, "status": "done"}""")
        assertFalse(ModelToolCallArgumentsNormalizer.normalize(unterminated, allowStructuralRepair = true).repaired)
    }

    @Test
    fun device_bound_calls_are_never_repaired_when_repair_is_not_allowed() {
        val call = ToolCall("call-1", "screen_act", "{\"items\":[1}")

        val result = ModelToolCallArgumentsNormalizer.normalize(call, allowStructuralRepair = false)

        assertFalse(result.repaired)
        assertEquals(call.arguments, result.toolCall.arguments)
    }

    @Test
    fun numeric_text_is_canonicalized_without_changing_the_value() {
        val call = ToolCall(
            "call-1",
            "screen_act",
            "{\"actions\":[{\"op\":\"type_text\",\"x\":400,\"y\":480,\"text\":2000}]}"
        )

        val result = ModelToolCallArgumentsNormalizer.normalize(
            call,
            allowStructuralRepair = false,
            parameters = screenAct
        )

        assertTrue(result.repaired)
        assertEquals(
            "{\"actions\":[{\"op\":\"type_text\",\"x\":400,\"y\":480,\"text\":\"2000\"}]}",
            result.toolCall.arguments
        )
    }

    @Test
    fun quoted_boolean_clear_is_canonicalized() {
        val call = ToolCall(
            "call-1",
            "screen_act",
            "{\"actions\":[{\"op\":\"type_text\",\"x\":400,\"y\":480,\"text\":\"2000\",\"clear\":\"False\"}]}"
        )

        val result = ModelToolCallArgumentsNormalizer.normalize(
            call,
            allowStructuralRepair = false,
            parameters = screenAct
        )

        assertTrue(result.repaired)
        assertEquals(
            "{\"actions\":[{\"op\":\"type_text\",\"x\":400,\"y\":480,\"text\":\"2000\",\"clear\":false}]}",
            result.toolCall.arguments
        )
    }
}
