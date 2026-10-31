// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMessageProjectionTest {
    @Test
    fun runtimeSystemMessagesRemainVisibleToOpenAiCompatibleTransports() {
        val projected = ProviderMessageProjection.forOpenAiCompatible(
            listOf(
                ChatMessage(Role.USER, "task"),
                ChatMessage(Role.SYSTEM, "fresh observation required"),
                ChatMessage(Role.TOOL, "receipt", toolCallId = "call_1")
            )
        )

        assertEquals(listOf(Role.USER, Role.USER, Role.TOOL), projected.map(ChatMessage::role))
        assertTrue(projected[1].content.contains("<runtime_control>"))
        assertTrue(projected[1].content.contains("fresh observation required"))
        assertTrue(projected[1].content.contains("</runtime_control>"))
        assertEquals("call_1", projected[2].toolCallId)
    }
}
