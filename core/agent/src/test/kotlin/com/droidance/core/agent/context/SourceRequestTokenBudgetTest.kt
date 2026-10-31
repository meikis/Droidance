// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import org.junit.Assert.*
import org.junit.Test

class SourceRequestTokenBudgetTest {
    private val estimator = StructuralContextTokenEstimator.forSession("GENERIC_CHAT_COMPLETIONS", "llm-router-v1", "model")
    private fun tokens(messages: List<ChatMessage>, tools: List<ToolDefinition> = emptyList(), system: String = "System") =
        estimator.estimateRequest(system, tools, messages)

    @Test fun requiredImagesConsumeCapacityWithoutChargingBase64AsTextOrLosingLabels() {
        val source = ChatMessage(Role.USER, "historical source identity")
        val baseline = tokens(listOf(source))
        val one = tokens(listOf(source.copy(imageBase64 = "a")))
        val two = tokens(listOf(source.copy(imageBase64List = listOf("a", "b"))))
        assertEquals(StructuralContextTokenEstimator.DEFAULT_IMAGE_TOKENS, one - baseline)
        assertEquals(2 * StructuralContextTokenEstimator.DEFAULT_IMAGE_TOKENS, two - baseline)
        assertEquals(one, tokens(listOf(source.copy(imageBase64 = "pixels".repeat(100_000)))))
        assertTrue(tokens(listOf(source.copy(content = "source identity ".repeat(100)))) > baseline)
        val hardBoundary = baseline + StructuralContextTokenEstimator.DEFAULT_IMAGE_TOKENS / 2
        assertTrue(baseline < hardBoundary)
        assertTrue(one > hardBoundary)
    }

    @Test fun actualToolSchemaSystemAndAllRequestTailsConsumeTheBudget() {
        val messages = listOf(ChatMessage(Role.USER, "candidate"))
        val baseline = tokens(messages)
        val tools = listOf(ToolDefinition("review", "Actual offered schema", mapOf(
            "replacement" to ToolParameter("string", "A complete replacement ".repeat(500)))))
        assertTrue(tokens(messages, tools) > baseline + 1_000)
        assertTrue(tokens(messages, system = "Frozen operator rules ".repeat(500)) > baseline + 1_000)
        assertTrue(tokens(messages + listOf(ChatMessage(Role.SYSTEM, "title request ".repeat(300)),
            ChatMessage(Role.USER, "capacity footer ".repeat(300)))) > baseline + 1_000)
    }
}
