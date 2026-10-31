// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolRegistryTest {

    private fun createDummyTool(toolName: String): Tool = object : Tool {
        override val name: String = toolName
        override val definition: ToolDefinition = ToolDefinition(name, "description", emptyMap())
        override suspend fun execute(arguments: String): ToolExecutionResult =
            ToolProtocolResult.success("ok")
    }

    @Test
    fun registerAndLookupToolsSuccessfully() {
        val registry = ToolRegistry()
        val tool1 = createDummyTool("click")
        val tool2 = createDummyTool("scroll")

        registry.register(tool1)
        registry.register(tool2)

        assertTrue(registry.hasTool("click"))
        assertTrue(registry.hasTool("scroll"))
        assertFalse(registry.hasTool("unknown"))
        assertEquals(tool1, registry.getTool("click"))
        assertEquals(tool2, registry.getTool("scroll"))
        assertNull(registry.getTool("unknown"))
        assertEquals(2, registry.allTools().size)
    }

    @Test
    fun duplicateRegistrationWithSameInstanceIsIdempotent() {
        val registry = ToolRegistry()
        val tool = createDummyTool("click")

        registry.register(tool)
        registry.register(tool) // Same instance re-registered

        assertEquals(1, registry.allTools().size)
        assertEquals(tool, registry.getTool("click"))
    }

    @Test
    fun duplicateRegistrationWithDifferentInstanceThrowsConflictException() {
        val registry = ToolRegistry()
        val toolA = createDummyTool("click")
        val toolB = createDummyTool("click")

        registry.register(toolA)
        try {
            registry.register(toolB)
            fail("Expected IllegalArgumentException on duplicate tool name registration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Duplicate tool registration conflict for 'click'") == true)
        }
    }

    @Test
    fun clearRemovesAllRegisteredTools() {
        val registry = ToolRegistry()
        registry.register(createDummyTool("tool_1"))
        registry.register(createDummyTool("tool_2"))

        assertEquals(2, registry.allTools().size)
        registry.clear()
        assertEquals(0, registry.allTools().size)
        assertFalse(registry.hasTool("tool_1"))
    }
}
