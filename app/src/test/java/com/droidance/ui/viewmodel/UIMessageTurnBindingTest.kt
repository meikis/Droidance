// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking
import com.droidance.core.agent.event.ConversationEntryKind
import com.droidance.core.agent.event.ConversationEntryReadModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageTurnBindingTest {
    @Test fun lateCompletionStaysAboveNewInputAndKeepsItsComposeIdentity() {
        val first = UIMessage("Opening", false, "", turnId = "first")
        val newer = UIMessage("Really?", true, "", turnId = "second")
        val second = UIMessage("Second reply", false, "", turnId = "second")
        val updated = listOf(first, newer, second).replaceAssistantInPlace(
            first.copy(text = "Opening\n\nCompleted"))
        assertEquals(listOf(first.messageId, newer.messageId, second.messageId), updated.map { it.messageId })
        assertEquals("Opening\n\nCompleted", updated[0].text)
        assertEquals("Second reply", updated[2].text)
        assertEquals(updated, updated.replaceAssistantInPlace(first.copy(turnId = "cleared")))
    }

    @Test fun historyReloadPlacesContinuationAfterGuidanceAndKeepsOpening() = runBlocking {
        fun entry(id: String, turn: String, kind: ConversationEntryKind, text: String, order: Long) =
            ConversationEntryReadModel(id, "event-$id", "thread", turn, order, order, kind, text)
        val messages = projectConversationMessages(listOf(
            entry("user-1", "first", ConversationEntryKind.USER_MESSAGE, "Remember my name", 1),
            entry("opening", "first", ConversationEntryKind.ASSISTANT_START, "I will remember it", 2),
            entry("steer", "first", ConversationEntryKind.USER_STEER, "Really?", 3),
            entry("user-2", "second", ConversationEntryKind.USER_MESSAGE, "Another question", 4),
            entry("reply-2", "second", ConversationEntryKind.TURN_ASSISTANT_REPLY, "Second answer", 5),
            entry("reply-1", "first", ConversationEntryKind.TURN_ASSISTANT_REPLY, "Remembered", 6)
        )) { null }
        assertEquals(listOf("user-1", "assistant:first", "steer", "assistant:first:steer", "user-2", "assistant:second"), messages.map { it.messageId })
        assertEquals("I will remember it", messages[1].text)
        assertEquals("Remembered", messages[3].text)
        assertEquals(3L, messages[1].completedAtMs)
    }

    @Test fun lateFirstReplyStaysWithItsOriginalQuestionAfterReload() = runBlocking {
        fun entry(id: String, turn: String, kind: ConversationEntryKind, order: Long) =
            ConversationEntryReadModel(id, "event-$id", "thread", turn, order, order, kind, id)
        val messages = projectConversationMessages(listOf(
            entry("user-1", "first", ConversationEntryKind.USER_MESSAGE, 1),
            entry("user-2", "second", ConversationEntryKind.USER_MESSAGE, 2),
            entry("reply-2", "second", ConversationEntryKind.TURN_ASSISTANT_REPLY, 3),
            entry("reply-1", "first", ConversationEntryKind.TURN_ASSISTANT_REPLY, 4)
        )) { null }
        assertEquals(listOf("user-1", "assistant:first", "user-2", "assistant:second"), messages.map { it.messageId })
    }

    @Test
    fun sameTimestampNeverLetsAnotherTurnReplaceTheMessagePresentation() {
        val first = UIMessage("first", false, "09:01", turnId = "turn:first", timestampMs = 100)
        val second = first.copy(text = "second", turnId = "turn:second")

        assertTrue(first.showsPresentationFor("turn:first", first.messageId))
        assertFalse(first.showsPresentationFor("turn:second", first.messageId))
        assertTrue(second.showsPresentationFor("turn:second", second.messageId))
        assertFalse(second.showsPresentationFor("turn:first", second.messageId))
    }

    @Test
    fun userAndUnboundMessagesNeverBorrowExecutionCards() {
        val assistant = UIMessage("reply", false, "09:01", turnId = "turn:first")
        assertFalse(assistant.copy(isUser = true).showsPresentationFor("turn:first", assistant.messageId))
        assertFalse(assistant.copy(turnId = null).showsPresentationFor("turn:first", assistant.messageId))
        assertTrue(assistant.copy(text = "streamed reply", completedAtMs = 200)
            .showsPresentationFor("turn:first", assistant.messageId))
    }
}
