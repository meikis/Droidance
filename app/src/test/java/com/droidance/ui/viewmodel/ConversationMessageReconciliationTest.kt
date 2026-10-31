// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.viewmodel

import com.droidance.agent.ActiveConversationTask
import com.droidance.core.agent.MessageSource
import com.droidance.core.agent.TurnRequest
import org.junit.Assert.*
import org.junit.Test

class ConversationMessageReconciliationTest {
    @Test fun reattachingWhileAssistantArrivesFirstStillLoadsUserAndHistory() {
        val history = listOf(user("old", "Earlier question"), assistant("old", "Earlier answer"))
        val waiting = assistant("live", "", transient = true)
        val active = active("live", "Current question")
        val restored = reconcile(listOf(waiting), history + user("live", "Current question"),
            setOf("turn:old"), active)

        assertEquals(listOf("message:old", "assistant:turn:old", "message:live", "assistant:turn:live"),
            restored.map { it.messageId })
        assertEquals("Current question", restored[2].text)
        assertEquals(1, restored.count { it.messageId == waiting.messageId })
    }

    @Test fun durableEventsUpdateDuringExecutionAndPendingInputSurvivesOlderProjection() {
        val active = active("live", "Current question")
        val initial = listOf(user("live", "Current question"), assistant("live", "", transient = true))
        val pending = user("followup", "Additional requirement", transient = true)
        val updated = reconcile(initial + pending,
            listOf(user("live", "Current question"), assistant("live", "Model opening")), emptySet(), active)

        assertEquals(listOf("Current question", "Model opening", "Additional requirement"), updated.map { it.text })
        assertTrue(updated.last().isTransient)
        val acknowledged = pending.copy(turnId = "turn:live", isTransient = false)
        val next = reconcile(updated,
            listOf(user("live", "Current question"), assistant("live", "Model opening"), acknowledged),
            emptySet(), active)
        assertEquals(1, next.count { it.messageId == pending.messageId })
        assertEquals("turn:live", next.last().turnId)
        assertFalse(next.last().isTransient)
    }

    @Test fun lateAssistantStaysBeforeNewerInputAcrossEveryProjectionRefresh() {
        val active = active("live", "Initial question", "Partial reply")
        val projected = listOf(user("live", "Initial question"), user("steer", "Follow up").copy(turnId = "turn:live"))
        val first = reconcile(emptyList(), projected, emptySet(), active)
        val second = reconcile(first, projected, emptySet(), active.copy(streamingByRound = mapOf(null to "Full reply")))

        assertEquals(listOf("message:live", "assistant:turn:live", "message:steer"), second.map { it.messageId })
        assertEquals("Full reply", second[1].text)
    }

    @Test fun terminalProjectionReplacesTransientTextAndDoesNotResurrectActiveSnapshot() {
        val current = listOf(user("live", "Question"), assistant("live", "Partial reply", transient = true))
        val durable = current.map { if (it.isUser) it else it.copy(text = "Final reply", isTransient = false, completedAtMs = 300) }
        val result = reconcile(current, durable, setOf("turn:live"), active("live", "Question", "Partial reply"))
        assertEquals(durable, result)
        assertFalse(result.last().isTransient)
    }

    @Test fun queuedTurnRestoresRequestImagesAndOriginalTimeBeforeAnyDurableEvent() {
        val image = byteArrayOf(1, 2, 3, 4)
        val active = active("queued", "Queued request").let {
            it.copy(request = it.request.copy(images = listOf(java.util.Base64.getEncoder().encodeToString(image))))
        }
        val restored = reconcile(listOf(assistant("queued", "", transient = true)), emptyList(), emptySet(), active)
        assertEquals(listOf("message:queued", "assistant:turn:queued"), restored.map { it.messageId })
        assertEquals("Queued request", restored.first().text)
        assertArrayEquals(image, restored.first().imageBytes.single())
        assertEquals(100L, restored.first().timestampMs)
        // Existing live slots retain their own identity while the user request is restored.
        assertEquals("assistant:turn:queued", restored.last().messageId)
        val fresh = reconcile(emptyList(), emptyList(), emptySet(), active)
        assertEquals(100L, fresh.last().startedAtMs)
    }

    @Test fun unfinishedDurableBlankReplyKeepsLiveTextUntilPublicReplyIsCommitted() {
        val active = active("live", "Question", "Streaming reply")
        val current = listOf(user("live", "Question"), assistant("live", "Streaming reply", transient = true))
        val blank = listOf(user("live", "Question"), assistant("live", ""))
        assertEquals("Streaming reply", reconcile(current, blank, emptySet(), active).last().text)
        val committed = blank.map { if (it.isUser) it else it.copy(text = "Committed reply") }
        assertEquals("Committed reply", reconcile(current, committed, emptySet(), active).last().text)
    }

    @Test fun coldReattachmentUsesStreamSnapshotEvenWhenDurableEmptySlotAlreadyExists() {
        val blank = listOf(user("live", "Question"), assistant("live", ""))
        val active = active("live", "Question", "First chunk")
        val restored = reconcile(emptyList(), blank, emptySet(), active)
        assertEquals("First chunk", restored.last().text)
        assertEquals(100L, restored.last().startedAtMs)
        val next = reconcile(restored, blank, emptySet(), active.copy(streamingByRound = mapOf(null to "First chunk and second")))
        assertEquals("First chunk and second", next.last().text)
        assertEquals(blank.map { it.messageId }, next.map { it.messageId })
    }

    @Test fun localCompletionSurvivesStaleEmptyOrOpeningProjectionUntilCanonicalTerminalArrives() {
        val finished = assistant("live", "Opening\n\nFinal reply").copy(completedAtMs = 300)
        val current = listOf(user("live", "Question"), finished)
        for (oldText in listOf("", "Opening")) {
            val stale = listOf(user("live", "Question"), assistant("live", oldText))
            assertEquals(finished, reconcile(current, stale, emptySet(), null).last())
            val canonical = stale.map { if (it.isUser) it else it.copy(text = "Canonical final", completedAtMs = 300) }
            assertEquals("Canonical final", reconcile(current, canonical, setOf("turn:live"), null).last().text)
        }
        assertEquals(finished, reconcile(current, listOf(current.first()), emptySet(), null).last())
    }

    @Test fun sameConversationRunningAndQueuedTurnsRestoreBothMessagesAndTheirOwnStream() {
        val a = active("a", "First question", "A stream")
        val b = active("b", "Second question")
        val result = reconcileConversationMessages(emptyList(), emptyList(), emptySet(), listOf(a, b))
        assertEquals(listOf("message:a", "assistant:turn:a", "message:b", "assistant:turn:b"), result.map { it.messageId })
        assertEquals("A stream", result[1].text)
        assertEquals("Second question", result[2].text)
        assertEquals("", result[3].text)
    }

    private fun reconcile(current: List<UIMessage>, projected: List<UIMessage>, terminal: Set<String>, active: ActiveConversationTask?) =
        reconcileConversationMessages(current, projected, terminal, active?.let(::listOf).orEmpty())

    private fun user(id: String, text: String, transient: Boolean = false) = UIMessage(
        text, true, "", turnId = "turn:$id", messageId = "message:$id", isTransient = transient)
    private fun assistant(id: String, text: String, transient: Boolean = false) = UIMessage(
        text, false, "", turnId = "turn:$id", messageId = "assistant:turn:$id", isTransient = transient)
    private fun active(id: String, text: String, stream: String = "") = ActiveConversationTask(
        "thread", "turn:$id", TurnRequest(id, MessageSource.DIRECT, text, emptyList(), id, "thread"), 100L, mapOf(null to stream))
}
