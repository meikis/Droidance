// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TaskTitleProtocolTest {
    @Test fun stableSchemaAllowsOptionalTaskNameWithoutChangingExecutorFields() {
        val tool = ToolDefinition("read", "Read", mapOf("op" to ToolParameter("string", "Operation")))
        val exposed = TaskTitleProtocol.addParameter(listOf(tool), "Fixed task name").single()
        assertFalse(exposed.parameters.getValue("task_title").required)
        assertEquals(tool.parameters["op"], exposed.parameters["op"])
        assertFalse("task_title" in tool.parameters)
    }

    @Test fun theFirstValidNameWinsAcrossCallsAndIsRemovedFromEveryExecutorArgument() {
        val result = TaskTitleProtocol.extract(listOf(
            ToolCall("duplicate", "read", """{"op":"screen","task_title":" 外卖搜索 "}"""),
            ToolCall("duplicate", "read", """{"op":"screen","task_title":"点击页面"}""")))
        assertEquals("外卖搜索", result.title)
        assertTrue(result.toolCalls.all { it.arguments == """{"op":"screen"}""" })
        assertEquals(listOf("duplicate", "duplicate"), result.toolCalls.map { it.id })
    }

    @Test fun invalidOptionalNamesNeverBlockCallsOrReachTheExecutor() {
        for (value in listOf("null", "42", "[]", "{}", "\" \"", "\"line\\nbreak\"",
            "\"${"x".repeat(25)}\"", "\"<think>hidden</think>\"")) {
            val result = TaskTitleProtocol.extract(listOf(ToolCall("id", "read", """{"op":"screen","task_title":$value}""")))
            assertNull(value, result.title)
            assertEquals(Json.parseToJsonElement("""{"op":"screen"}"""), Json.parseToJsonElement(result.toolCalls.single().arguments))
        }
        val malformed = ToolCall("id", "read", "not json")
        assertEquals(listOf(malformed), TaskTitleProtocol.extract(listOf(malformed)).toolCalls)
    }

    @Test fun anInvalidOrAbsentFirstNameDoesNotHideALaterValidName() {
        val result = TaskTitleProtocol.extract(listOf(
            ToolCall("a", "read", "{}"), ToolCall("b", "read", """{"task_title":false}"""),
            ToolCall("c", "read", """{"task_title":"Food search"}""")))
        assertEquals("Food search", result.title)
        assertTrue(result.toolCalls.all { it.arguments == "{}" })
    }
}
