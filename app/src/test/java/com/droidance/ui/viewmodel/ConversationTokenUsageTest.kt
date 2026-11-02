// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.config.TokenUsageRepository.TurnUsage
import com.droidance.config.TokenUsageTotals
import com.droidance.core.model.llm.ModelRequestUsage
import org.junit.Assert.*
import org.junit.Test

class ConversationTokenUsageTest {
    @Test fun conversationTotalsNeverIncludeAnotherThreadOrDuplicateGuidanceSegments() {
        val totals = TokenUsageTotals().addRequest(ModelRequestUsage("p", "m", 100, 50))
        val messages = listOf(
            UIMessage("reply", false, "", turnId = "turn-a", messageId = "old", isCurrentSegment = false),
            UIMessage("continued", false, "", turnId = "turn-a", messageId = "new"),
            UIMessage("old reply", false, "", turnId = "unrecorded")
        )
        val usage = conversationTokenUsage("thread-a", messages, listOf(
            TurnUsage("thread-a", "turn-a", totals), TurnUsage("thread-b", "turn-a", totals)))
        assertEquals(150L, usage.total.totalTokens)
        assertEquals(1L, usage.total.calls)
        assertTrue(usage.hasUnrecordedTurns)
        assertTrue(usage.hasReplies)
        assertFalse(conversationTokenUsage("thread-a", emptyList(), emptyList()).hasReplies)
    }
}
