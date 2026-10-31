// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleToolProtocolTest {
    @Test fun frozenSchemaAllowsNamingOnlyWhenTheRequestContextPermitsIt() {
        val tools = listOf(ToolDefinition("read", "Read facts", emptyMap()))
        assertFalse(ConversationTitleProtocol.addParameter(tools, "Naming policy")
            .single().parameters.getValue(ConversationTitleProtocol.ARGUMENT).required)
        assertEquals(setOf(ConversationTitleProtocol.ARGUMENT),
            ConversationTitleProtocol.addParameter(tools, "Naming policy").single().parameters.keys)
        assertTrue(tools.single().parameters.isEmpty())
    }

    @Test fun nativeMetadataIsValidatedAndRemovedBeforeExecution() {
        val call = ToolCall("call", "read", "{\"op\":\"device\",\"conversation_title\":\"  手机电量与充电状态  \"}")
        val result = ConversationTitleProtocol.extractToolCalls(listOf(call))
        assertEquals("手机电量与充电状态", result.title)
        assertEquals(buildJsonObject { put("op", "device") }, Json.parseToJsonElement(result.toolCalls.single().arguments))
        assertTrue(call.arguments.contains("conversation_title"))
    }

    @Test fun invalidMetadataNeverDamagesTheExecutableArguments() {
        listOf("null", "12", "\"\"", "{}", JsonPrimitive("x".repeat(37)).toString()).forEach { value ->
            val result = ConversationTitleProtocol.extractToolCalls(listOf(ToolCall("call", "read",
                "{\"op\":\"device\",\"conversation_title\":$value}")))
            assertNull(result.title)
            assertEquals(buildJsonObject { put("op", "device") }, Json.parseToJsonElement(result.toolCalls.single().arguments))
        }
    }

    @Test fun nativeTitlesRespectTheSameUnicodeLimitAsTextTitles() {
        val title = "😀".repeat(36)
        val result = ConversationTitleProtocol.extractToolCalls(listOf(ToolCall("call", "read",
            buildJsonObject { put("conversation_title", title); put("op", "device") }.toString())))
        assertEquals(title, result.title)
        assertEquals(buildJsonObject { put("op", "device") }, Json.parseToJsonElement(result.toolCalls.single().arguments))
    }

    @Test fun conflictingParallelTitlesAreIgnoredWhileMatchingTitlesCommitOnce() {
        fun call(id: String, title: String) = ToolCall(id, "read", buildJsonObject {
            put("conversation_title", title); put("op", "device")
        }.toString())
        val calls = listOf(call("one", "手机电量查询"), call("two", "手机电量查询"))
        assertEquals("手机电量查询", ConversationTitleProtocol.extractToolCalls(calls).title)
        val conflict = ConversationTitleProtocol.extractToolCalls(listOf(calls.first(), call("two", "另一目标")))
        assertNull(conflict.title)
        assertTrue(conflict.toolCalls.all { "conversation_title" !in it.arguments })
    }
}
