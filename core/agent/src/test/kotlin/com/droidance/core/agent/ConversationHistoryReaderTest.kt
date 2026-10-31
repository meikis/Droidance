// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ConversationEntryKind
import com.droidance.core.agent.event.ConversationEntryReadModel
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.model.llm.models.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryReaderTest {
    @Test
    fun missingAndTombstonedThreadsHaveNoModelHistory() = runBlocking {
        val store = InMemoryAgentEventStore()
        val reader = ConversationHistoryReader(store)

        assertTrue(reader.read("missing-thread").isEmpty())

        store.createThread("deleted-thread", "Deleted")
        store.tombstoneThread("deleted-thread")
        assertTrue(reader.read("deleted-thread").isEmpty())
    }

    @Test
    fun userSteerIsUserInputAndAssistantStreamingEntriesMergeAtTheFinalEntry() {
        val reader = ConversationHistoryReader(InMemoryAgentEventStore())
        val messages = reader.project(
            listOf(
                entry("user", 1, ConversationEntryKind.USER_MESSAGE, "Original goal"),
                entry("steer", 2, ConversationEntryKind.USER_STEER, "Use the other option"),
                entry("opening", 3, ConversationEntryKind.ASSISTANT_START, "Opening"),
                entry("duplicate-opening", 4, ConversationEntryKind.ASSISTANT_USER_INTERVENTION, "Opening"),
                entry("final", 5, ConversationEntryKind.TURN_ASSISTANT_REPLY, "Final result")
            )
        )

        assertEquals(3, messages.size)
        assertEquals(Role.USER, messages[0].role)
        assertEquals("Original goal", messages[0].content)
        assertEquals(Role.USER, messages[1].role)
        assertEquals("Use the other option", messages[1].content)
        assertEquals(Role.ASSISTANT, messages[2].role)
        assertEquals("Opening\n\nFinal result", messages[2].content)
    }

    @Test
    fun projectedHistoryKeepsAtMostTheLastTwentyMessages() {
        val reader = ConversationHistoryReader(InMemoryAgentEventStore())
        val entries = (1..21).map { sequence ->
            entry(
                itemId = "user-$sequence",
                sequence = sequence.toLong(),
                kind = ConversationEntryKind.USER_MESSAGE,
                text = "User message $sequence",
                turnId = "turn-$sequence"
            )
        }

        val messages = reader.project(entries)

        assertEquals(20, messages.size)
        assertEquals("User message 2", messages.first().content)
        assertEquals("User message 21", messages.last().content)
    }

    private fun entry(
        itemId: String,
        sequence: Long,
        kind: ConversationEntryKind,
        text: String,
        turnId: String = "turn-1"
    ) = ConversationEntryReadModel(
        itemId = itemId,
        sourceEventId = "event-$itemId",
        threadId = "thread-1",
        turnId = turnId,
        sequence = sequence,
        occurredAt = sequence,
        kind = kind,
        text = text
    )
}
