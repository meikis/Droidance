// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*
import com.droidance.agent.*
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import com.droidance.core.agent.llm.session.*
import com.droidance.core.model.llm.session.*
import com.droidance.ui.viewmodel.projectConversationMessages
import com.droidance.ui.viewmodel.reconcileConversationMessages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationExecutionSegmentsTest {
    @Test fun stageResultsKeepRoundOwnershipAcrossGuidanceAndSurviveFinalProjection() = runBlocking {
        val entries = listOf(input("initial", 1), input("guide", 4),
            reply("First findings", "old", 5).copy(kind = ConversationEntryKind.ASSISTANT_STAGE_RESULT),
            reply("Second findings", "new", 7).copy(kind = ConversationEntryKind.ASSISTANT_STAGE_RESULT),
            reply("Overall summary", null, 8).copy(kind = ConversationEntryKind.TURN_ASSISTANT_REPLY))
        val events = listOf(start("old", 2), start("new", 6))
        val presentations = thread(entries, events, terminal = true).segmentPresentations(null)
        assertEquals(listOf("First findings"), presentations.first().stageResults.map { it.text })
        assertEquals(listOf("Second findings"), presentations.last().stageResults.map { it.text })
        assertEquals("Overall summary", presentations.last().finalSummary)
        assertEquals("First findings", presentations.first().publicCopyText())
        assertEquals("Second findings\n\nOverall summary", presentations.last().publicCopyText())
        val messages = projectConversationMessages(entries, conversationExecutionSegments(entries, events)) { null }
        assertTrue(messages.first { !it.isUser }.text.contains("First findings"))
        assertEquals(presentations, thread(entries.toList(), events.toList(), terminal = true).segmentPresentations(null))
    }

    @Test fun lateReplyAndReceiptStayWithTheirOriginalRoundAfterGuidance() = runBlocking {
        val entries = listOf(input("initial", 1), input("guide", 4), reply("late", "old", 5), reply("new-reply", "new", 8))
        val events = listOf(start("old", 2), start("new", 6))
        val segments = conversationExecutionSegments(entries, events)
        assertEquals(listOf("late"), segments.first().replies.map { it.text })
        assertEquals(listOf("new-reply"), segments.last().replies.map { it.text })
        val projected = projectConversationMessages(entries, segments) { null }
        assertEquals(listOf("initial", "assistant:t", "guide", "assistant:t:guide"), projected.map { it.messageId })
        val base = thread(entries, events)
        val calls = listOf(tool("old-call", "old", 3), tool("new-call", "new", 7))
        val thread = base.copy(activityByTurn = mapOf("t" to base.activityByTurn.getValue("t").copy(
            entries = calls, operationCount = 2,
            publicProgressHistory = listOf(PublicProgressReadModel("progress", 3, "Inspecting", listOf("old", "new"))))))
        val presentations = thread.segmentPresentations(null)
        assertEquals(setOf("old"), presentations.first().activity.executionRoundTimings.keys)
        assertEquals(setOf("new"), presentations.last().activity.executionRoundTimings.keys)
        assertEquals(listOf("old-call"), presentations.first().activity.entries.map { it.itemId })
        assertEquals(listOf("new-call"), presentations.last().activity.entries.map { it.itemId })
        assertEquals(listOf(1, 1), presentations.map { it.activity.operationCount })
        assertEquals(listOf(listOf("old"), listOf("new")), presentations.map { it.activity.publicProgressHistory.single().roundIds })
        assertFalse(presentations.first().isCurrentSegment)
        assertFalse(presentations.first().showCurrentProgress(true))
        assertFalse(presentations.first().isPaused(true))
        assertTrue(presentations.last().isCurrentSegment)
        assertEquals(presentations, thread.copy(completeRecord = thread.completeRecord.copy(events = events.toList())).segmentPresentations(null))
    }

    @Test fun repeatedGuidanceWithoutWorkPreservesInputsWithoutEmptyHistoricalCards() = runBlocking {
        val entries = listOf(input("initial", 1), reply("opening", "old", 3), input("guide", 4), input("latest", 5))
        val events = listOf(start("old", 2))
        val segments = conversationExecutionSegments(entries, events)
        val projected = projectConversationMessages(entries, segments) { null }
        assertEquals(listOf("initial", "assistant:t", "guide", "latest", "assistant:t:latest"), projected.map { it.messageId })
        val presentations = thread(entries, events).segmentPresentations(null)
        assertEquals(2, presentations.size)
        assertTrue(presentations.last().applyingGuidance)
    }

    @Test fun lateStreamCannotMoveToTheNewSegmentAndColdReattachmentHasSameText() = runBlocking {
        val entries = listOf(input("initial", 1), input("guide", 4))
        val events = listOf(start("old", 2), start("new", 6))
        val projected = projectConversationMessages(entries, conversationExecutionSegments(entries, events)) { null }
        val active = ActiveConversationTask("thread", "t", TurnRequest("initial", MessageSource.DIRECT,
            "initial", emptyList(), "hash", "thread"), 100L,
            streamingByRound = mapOf("old" to "Old reply", "new" to "New reply"), started = true)
        val first = reconcileConversationMessages(emptyList(), projected, emptySet(), listOf(active))
        assertEquals("Old reply", first.single { it.messageId == "assistant:t" }.text)
        assertEquals("New reply", first.single { it.messageId == "assistant:t:guide" }.text)
        val next = reconcileConversationMessages(first, projected, emptySet(), listOf(active.copy(
            streamingByRound = active.streamingByRound + ("old" to "Old reply, late tail"))))
        assertEquals("Old reply, late tail", next.single { it.messageId == "assistant:t" }.text)
        assertEquals("New reply", next.single { it.messageId == "assistant:t:guide" }.text)
    }

    @Test fun segmentClocksUsePersistedActiveTimeAndOnlyTheCurrentSegmentTicks() {
        val entries = listOf(input("initial", 1), input("guide", 4).copy(activeDurationMillis = 4_000L))
        val events = listOf(start("old", 2), start("new", 6))
        val live = thread(entries, events).segmentPresentations(LiveTaskTiming("t", TaskExecutionClock.Snapshot(9_000L, null)))
        assertEquals(4_000L, live.first().activity.activeDurationMillis)
        assertNull(live.first().executionClock)
        assertEquals(5_000L, live.last().executionClock!!.elapsedMs())
        val ended = thread(entries, events, terminal = true).segmentPresentations(null)
        assertEquals(listOf(4_000L, 6_000L), ended.map { it.activity.activeDurationMillis })
        assertFalse(ended.first().isTerminal)
        assertTrue(ended.last().isTerminal)
    }

    @Test fun terminalReplyBelongsToLatestInputEvenWhenTheOldRoundFinishesLate() {
        val entries = listOf(input("initial", 1), input("guide", 4), reply("old reply", "old", 7),
            reply("final", null, 9).copy(kind = ConversationEntryKind.TURN_ASSISTANT_REPLY))
        val segments = conversationExecutionSegments(entries, listOf(start("old", 2)))
        assertEquals(listOf("old reply"), segments.first().replies.map { it.text })
        assertEquals(listOf("final"), segments.last().replies.map { it.text })
    }

    private fun tool(id: String, round: String, seq: Long) = ToolActivityReadModel(
        id, "t", seq, true, round, id, "attempt", "execution", ToolCallSource.MODEL_REQUESTED,
        ToolPresentationSnapshot("tool.screen_act", ToolPresentationCategory.SUPPORTING, ResultImageRole.NONE),
        ToolActivityState.VERIFIED, null, null, true, seq * 1000, 9000L)

    private fun input(id: String, seq: Long) = ConversationEntryReadModel(id, "e-$id", "thread", "t", seq, seq * 1_000,
        if (id == "initial") ConversationEntryKind.USER_MESSAGE else ConversationEntryKind.USER_STEER, id)
    private fun reply(text: String, round: String?, seq: Long) = ConversationEntryReadModel(text, "e-$text", "thread", "t", seq,
        seq * 1_000, ConversationEntryKind.ASSISTANT_START, text, roundId = round)
    private fun start(round: String, seq: Long) = AgentEventEnvelope(1, "e-$round", AgentEventKind.MODEL_ROUND_STARTED,
        seq, seq * 1_000, "thread", "t", round, null, null, null, "hash", "{}", "start-$round")
    private fun thread(entries: List<ConversationEntryReadModel>, events: List<AgentEventEnvelope>, terminal: Boolean = false): AgentThreadReadModel {
        val state = if (terminal) TurnReadModelState.TERMINAL else TurnReadModelState.RUNNING
        return AgentThreadReadModel(ThreadMetadataReadModel("thread", "", null, false, false, 1, 1, 10, 10),
            listOf(TurnReadModel("thread", "t", ModelSessionIdentity("thread", "provider", ModelTransport.GENERIC_CHAT_COMPLETIONS,
                "a".repeat(64), "c".repeat(64), "model", "b".repeat(64)), "config", 0, state, null, if (terminal) "done" else null, 1000,
                if (terminal) 12_000L else null, TurnActivationKind.ANDROID)),
            ConversationReadModel("thread", entries), mapOf("t" to TurnActivityReadModel("t", state, emptyList(), 0,
                null, emptyList(), 1000, if (terminal) 12_000L else null, null,
                activeDurationMillis = if (terminal) 10_000L else null,
                executionRoundTimings = events.associate { it.roundId!! to ExecutionRoundTimingReadModel(it.roundId!!, it.occurredAt, it.occurredAt + 1000) })),
            emptyMap(), emptyList(), emptyMap(), CompleteRecordReadModel("thread", events.map { it.eventId }, events))
    }
}
