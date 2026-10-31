// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolCall
import org.junit.Assert.*
import org.junit.Test

class CacheableRequestHistoryTest {
    @Test fun mutableFrameBindingsAndFootersDoNotEnterTheNextImmutablePrefix() {
        val history = CacheableRequestHistory()
        val goal = ChatMessage(Role.USER, "collect requested fields")
        val first = history.prepare(listOf(goal), emptyList(), null)
        val tail = listOf(ChatMessage(Role.USER, "current image", imageBase64 = "first viewport"),
            ChatMessage(Role.USER, "current binding and capacity"))
        assertEquals(3, (first + tail).size)
        history.recordSent(first)
        val call = ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "read", "{}")))
        val next = history.prepare(listOf(goal, call), emptyList(), null)
        assertEquals(first, next.take(first.size))
        assertEquals(listOf(goal, call), next)
        assertFalse(history.retainedRequestContext().any { it in tail })
    }
    private val goal = ChatMessage(Role.USER, "goal")
    private val env = ChatMessage(Role.SYSTEM, "frozen environment")
    private val memory = ChatMessage(Role.SYSTEM, "initial memory")

    @Test fun nextRequestKeepsExactSentPrefixAndAppendsCommittedCallsAndMemoryUpdates() {
        val history = CacheableRequestHistory()
        val first = history.prepare(listOf(goal), listOf(env), memory)
        history.recordSent(first)
        val call = ChatMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "read", "{}")))
        val receipt = ChatMessage(Role.TOOL, "committed receipt", toolCallId = "call")
        val updated = memory.copy(content = "updated memory")
        val second = history.prepare(listOf(goal.copy(timestamp = 1), call, receipt), listOf(env), updated)
        assertEquals(first, second.take(first.size))
        assertEquals(listOf(call, receipt, updated), second.drop(first.size))
        assertEquals(first, history.fork(listOf(goal)).take(first.size))
    }

    @Test fun unsentPreparationDoesNotLoseMemoryOrDirectiveUpdates() {
        val history = CacheableRequestHistory()
        val first = history.prepare(listOf(goal), listOf(env), memory)
        history.recordSent(first)
        val updated = memory.copy(content = "new memory")
        val directive = ChatMessage(Role.SYSTEM, "new directive")
        val aborted = history.prepare(listOf(goal), listOf(env, directive), updated)
        assertEquals(aborted, history.prepare(listOf(goal), listOf(env, directive), updated))
    }

    @Test fun changedProjectionAndRemovedDirectiveCannotRetainStaleHistory() {
        val history = CacheableRequestHistory()
        history.recordSent(history.prepare(listOf(goal), listOf(env), memory))
        assertEquals(listOf(memory, goal), history.prepare(listOf(goal), emptyList(), memory))
        history.reset()
        assertEquals(listOf(memory, env, goal), history.prepare(listOf(goal), listOf(env), memory))
    }

    @Test fun userSuppliedAttachmentsRemainDurableWhileScreenImagesStayRequestLocal() {
        val history = CacheableRequestHistory()
        val input = goal.copy(imageBase64 = "user attachment")
        val first = history.prepare(listOf(input), listOf(env), memory)
        history.recordSent(first)
        val call = ChatMessage(Role.ASSISTANT, "observed frame returned")
        val next = history.prepare(listOf(input, call), listOf(env), memory)
        assertEquals(first + call, next)
        assertEquals("user attachment", next.single { it.imageBase64 != null }.imageBase64)
    }

    @Test fun removingMemoryDoesNotRetainItsFormerIndex() {
        val history = CacheableRequestHistory()
        history.recordSent(history.prepare(listOf(goal), listOf(env), memory))
        assertEquals(listOf(env, goal), history.prepare(listOf(goal), listOf(env), null))
    }

    @Test fun committingAnAssembledRequestAsHistoryIsRejected() {
        val history = CacheableRequestHistory()
        val prefix = history.prepare(listOf(goal), emptyList(), null)
        val result = runCatching { history.recordSent(prefix + ChatMessage(Role.USER, "mutable tail")) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        history.recordSent(prefix)
        assertEquals(prefix, history.prepare(listOf(goal), emptyList(), null))
    }

    @Test fun identicalDirectivesAreSentOnceWhileDistinctUpdatesAndReceiptHistoryStayStable() {
        val history = CacheableRequestHistory()
        val initial = history.prepare(listOf(goal), listOf(env, env.copy(timestamp = 5)), memory)
        assertEquals(1, initial.count { it.content == env.content })
        assertEquals("1", history.preparationFacts()["duplicate_directives_removed"])
        history.recordSent(initial)
        val updated = env.copy(content = "Changed execution restriction")
        val receipt = ChatMessage(Role.TOOL, "Native action receipt", toolCallId = "call")
        val next = history.prepare(listOf(goal, receipt), listOf(env, updated, updated), memory)
        assertEquals(initial, next.take(initial.size))
        assertEquals(1, next.count { it.content == updated.content })
        assertTrue(next.contains(receipt))
        assertEquals("true", history.preparationFacts()["local_prefix_reused"])
        assertEquals(initial.size.toString(), history.preparationFacts()["local_prefix_reused_messages"])
        history.recordSent(next)
        history.prepare(listOf(goal, receipt), listOf(updated), memory)
        assertEquals("directive_removed_or_changed", history.preparationFacts()["local_prefix_reset_reason"])
        history.prepare(listOf(goal.copy(content = "Revised user goal")), listOf(env), memory)
        assertEquals("durable_history_changed", history.preparationFacts()["local_prefix_reset_reason"])
    }
}
