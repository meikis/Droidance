// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InterruptedToolTurnTest {
    private val firstCall = ToolCall("call-1", "observe_screen", "{}")
    private val secondCall = ToolCall("call-2", "tap", "{\"x\":10,\"y\":20}")

    @Test
    fun removesAssistantTurnWhenNoPlannedCallWasResolved() {
        val messages = mutableListOf(
            ChatMessage(role = Role.USER, content = "continue"),
            ChatMessage(
                role = Role.ASSISTANT,
                content = "I will inspect the page.",
                toolCalls = listOf(firstCall, secondCall)
            )
        )

        InterruptedToolTurn.discardPendingCalls(
            messages = messages,
            assistantIndex = 1,
            completedCallCount = 0
        )

        assertEquals(1, messages.size)
        assertEquals(Role.USER, messages.single().role)
    }

    @Test
    fun retainsOnlyCallsThatAlreadyHaveToolResults() {
        val messages = mutableListOf(
            ChatMessage(
                role = Role.ASSISTANT,
                content = "I will inspect and act.",
                toolCalls = listOf(firstCall, secondCall)
            ),
            ChatMessage(role = Role.TOOL, content = "screen", toolCallId = firstCall.id)
        )

        InterruptedToolTurn.discardPendingCalls(
            messages = messages,
            assistantIndex = 0,
            completedCallCount = 1
        )

        assertEquals(listOf(firstCall), messages.first().toolCalls)
        assertEquals(firstCall.id, messages[1].toolCallId)
    }

    @Test
    fun leavesUnrelatedHistoryUntouched() {
        val userMessage = ChatMessage(role = Role.USER, content = "continue")
        val messages = mutableListOf(userMessage)

        InterruptedToolTurn.discardPendingCalls(
            messages = messages,
            assistantIndex = 0,
            completedCallCount = 0
        )

        assertEquals(1, messages.size)
        assertSame(userMessage, messages.single())
    }
}
