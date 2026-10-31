// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.llm.session.ModelSessionIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryAgentEventStoreTest {
    @Test fun conflictingBatchRollsBackItsPrefixAndPreservesSequence() = runBlocking {
        fun draft(key: String, text: String) = AgentEventDraft(
            eventKind = AgentEventKind.SYSTEM_NOTICE, threadId = "thread", turnId = "turn",
            payloadJson = "{\"text\":\"$text\"}", idempotencyKey = key)
        val store = InMemoryAgentEventStore()
        val first = store.append(draft("existing", "before"))
        try {
            store.appendAtomically(listOf(draft("new", "new"), draft("existing", "conflict")))
            throw AssertionError("Expected idempotency conflict")
        } catch (_: AgentEventConflictException) { }
        assertEquals(1, store.count())
        val next = store.append(draft("new", "new"))
        assertEquals(first.sequence + 1, next.sequence)
        assertEquals(next, store.append(draft("new", "new")))
        assertEquals(2, store.count())
    }

    @Test fun duplicateConflictsInsideTheSameBatchAlsoRollBack() = runBlocking {
        val store = InMemoryAgentEventStore()
        val draft = AgentEventDraft(eventKind = AgentEventKind.SYSTEM_NOTICE,
            threadId = "thread", turnId = "turn", payloadJson = "{\"text\":\"a\"}", idempotencyKey = "same")
        try {
            store.appendAtomically(listOf(draft, draft.copy(payloadJson = "{\"text\":\"b\"}")))
            throw AssertionError("Expected idempotency conflict")
        } catch (_: AgentEventConflictException) { }
        assertEquals(0, store.count())
    }

    @Test fun exportSnapshotContainsRetainedHistoryAndDoesNotChangeAfterLaterWrites() = runBlocking {
        val store = InMemoryAgentEventStore()
        store.createThread("first", "old")
        store.createThread("deleted", "removed")
        store.tombstoneThread("deleted")
        val snapshot = store.exportSnapshot()
        store.createThread("second", "new")
        assertEquals(listOf("first"), snapshot.map { it.threadId }.distinct())
        assertEquals(listOf("first", "second"), store.exportSnapshot().map { it.threadId }.distinct())
    }

    private lateinit var store: InMemoryAgentEventStore
    private val dummyHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun testIdentity(threadId: String) = ModelSessionIdentity(
        threadId = threadId,
        providerId = "fake-provider",
        transportKind = com.droidance.core.model.llm.session.ModelTransport.GENERIC_CHAT_COMPLETIONS,
        transportConfigHash = dummyHash,
        authScopeHash = dummyHash,
        modelId = "fake-model",
        stablePrefixHash = dummyHash
    )

    @Before
    fun setUp() {
        store = InMemoryAgentEventStore()
    }

    @Test
    fun threadLifecycleAndReadModel() { runBlocking {
        val threadId = "test-thread-1"
        store.createThread(threadId, "Test Thread")

        val modelBeforeTurn = store.threadReadModel(threadId)
        assertNotNull(modelBeforeTurn)
        assertEquals("Test Thread", modelBeforeTurn?.metadata?.title)
        assertFalse(modelBeforeTurn?.metadata?.pinned ?: true)

        store.changeThreadPin(threadId, "change-1", isPinned = true)
        val modelAfterPin = store.threadReadModel(threadId)
        assertTrue(modelAfterPin?.metadata?.pinned ?: false)

        val activeModels = store.activeThreadReadModels()
        assertEquals(1, activeModels.size)
        assertEquals(threadId, activeModels.first().metadata.threadId)
    } }

    @Test fun latestConversationUsesCommittedDialogueRecencyRatherThanPinsOrMetadata() = runBlocking {
        assertNull(store.latestConversationThreadId())
        suspend fun conversation(id: String) {
            store.createThread(id, id)
            val scope = TurnEventScope(id, "turn-$id")
            store.startTurn(scope, testIdentity(id), "snapshot")
            store.recordUserMessage(scope, "message-$id", id)
            store.completeTurn(id, scope.turnId, TurnResult("done", assistantReply = "Reply $id"))
        }
        conversation("older")
        conversation("newer")
        store.createThread("empty", "empty")
        store.changeThreadPin("older", "pin", true)
        store.changeThreadMetadata("older", "rename", "renamed", null)
        assertEquals("newer", store.latestConversationThreadId())
        store.tombstoneThread("newer")
        assertEquals("older", store.latestConversationThreadId())
        val scope = TurnEventScope("older", "open-turn")
        store.startTurn(scope, testIdentity("older"), "snapshot")
        store.recordUserMessage(scope, "open-message", "Still running")
        conversation("other")
        store.recordUserSteer(scope, "guidance", "New guidance")
        assertEquals("older", store.latestConversationThreadId())
        assertEquals(store.activeThreadReadModels().filter { it.conversation.entries.isNotEmpty() }
            .maxByOrNull { it.conversation.entries.maxOf { entry -> entry.sequence } }?.metadata?.threadId,
            store.latestConversationThreadId())
    }

    @Test
    fun turnLifecycleFromStartToCompletion() { runBlocking {
        val threadId = "test-thread-2"
        val turnId = "turn-1"
        val scope = TurnEventScope(threadId, turnId)
        val identity = testIdentity(threadId)

        store.createThread(threadId, "Task Thread")
        store.startTurn(scope, identity, "snapshot-1")
        store.recordUserMessage(scope, "msg-1", "Open settings")

        val turnEvents = store.eventsForTurn(turnId)
        assertEquals(2, turnEvents.size)
        assertEquals(AgentEventKind.TURN_STARTED, turnEvents[0].eventKind)
        assertEquals(AgentEventKind.USER_MESSAGE, turnEvents[1].eventKind)

        store.completeTurn(
            threadId = threadId,
            turnId = turnId,
            result = TurnResult(
                status = "done",
                assistantReply = "Successfully opened settings"
            )
        )

        val completedModel = store.threadReadModel(threadId)
        assertNotNull(completedModel)
        assertEquals(1, completedModel?.turns?.size)
        val turn = completedModel?.turns?.first()
        assertEquals(TurnReadModelState.TERMINAL, turn?.state)
        assertEquals("done", turn?.terminalStatus)
        assertEquals("Successfully opened settings", completedModel?.conversation?.entries?.lastOrNull()?.text)

        // Attempting to complete the turn again fails with conflict
        assertThrows(AgentEventConflictException::class.java) {
            runBlocking {
                store.completeTurn(
                    threadId = threadId,
                    turnId = turnId,
                    result = TurnResult(status = "done", assistantReply = "Already done")
                )
            }
        }
    } }

    @Test
    fun tombstoneWithOpenTurnThrowsConflict() { runBlocking {
        val threadId = "test-thread-3"
        val turnId = "turn-open"
        val scope = TurnEventScope(threadId, turnId)
        val identity = testIdentity(threadId)

        store.createThread(threadId, "Thread with open turn")
        store.startTurn(scope, identity, "snapshot-1")

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking { store.tombstoneThread(threadId) }
        }

        store.completeTurn(threadId, turnId, TurnResult(status = "done", assistantReply = "Done"))
        // After completing turn, tombstone succeeds
        val tombstoneEnvelope = store.tombstoneThread(threadId)
        assertEquals(AgentEventKind.THREAD_TOMBSTONED, tombstoneEnvelope.eventKind)

        val activeModels = store.activeThreadReadModels()
        assertTrue(activeModels.none { it.metadata.threadId == threadId })
    } }

    @Test
    fun purgeRejectsOpenTurnsWithoutHidingHistoryAndCanBeRetriedAfterSettlement() = runBlocking {
        val threadId = "delete-after-settlement"
        val scope = TurnEventScope(threadId, "pending-turn")
        store.createThread(threadId, "Keep until settled")
        store.startTurn(scope, testIdentity(threadId), "snapshot")
        store.recordUserMessage(scope, "message", "A task being cancelled")
        val original = store.eventsForThread(threadId)

        assertThrows(AgentEventConflictException::class.java) {
            runBlocking { store.purgeThreads(setOf(threadId)) }
        }
        assertEquals(original, store.eventsForThread(threadId))
        assertNotNull(store.threadReadModel(threadId))
        assertEquals(listOf(scope), store.unfinishedTurnScopesForRecovery())

        store.completeTurn(threadId, scope.turnId, TurnResult(status = "cancelled", assistantReply = "Stopped"))
        assertEquals(original.size + 1, store.purgeThreads(setOf(threadId)))
        assertNull(store.threadReadModel(threadId))
        assertTrue(store.unfinishedTurnScopesForRecovery().isEmpty())
        assertEquals(0, store.purgeThreads(setOf(threadId)))
    }

    @Test fun bulkPurgeIsAtomicWhenAnySelectedTurnIsStillOpen() = runBlocking {
        store.createThread("settled", "Keep on failure")
        store.createThread("open", "Still settling")
        store.createThread("other", "Not selected")
        val scope = TurnEventScope("open", "pending")
        store.startTurn(scope, testIdentity("open"), "snapshot")
        val before = store.count()
        assertThrows(AgentEventConflictException::class.java) {
            runBlocking { store.purgeThreads(setOf("settled", "open")) }
        }
        assertEquals(before, store.count())
        store.completeTurn("open", "pending", TurnResult("cancelled", "Stopped"))
        store.purgeThreads(setOf("settled", "open"))
        assertTrue(store.eventsForThread("settled").isEmpty())
        assertTrue(store.eventsForThread("open").isEmpty())
        assertEquals(1, store.eventsForThread("other").size)
    }

    @Test
    fun purgingASettledThreadPreservesAnotherThreadsOpenTurn() = runBlocking {
        store.createThread("deleted", "Deleted")
        store.createThread("running", "Still running")
        val scope = TurnEventScope("running", "active-turn")
        store.startTurn(scope, testIdentity("running"), "snapshot")
        val original = store.eventsForThread("running")

        assertEquals(1, store.purgeThreads(setOf("deleted")))

        assertEquals(original, store.eventsForThread("running"))
        assertEquals(listOf(scope), store.unfinishedTurnScopesForRecovery())
    }

    @Test
    fun recoveryAndInterruption() { runBlocking {
        val threadId = "test-thread-recovery"
        val turnId = "turn-interrupted"
        val scope = TurnEventScope(threadId, turnId)
        val identity = testIdentity(threadId)

        store.createThread(threadId, "Interrupted task")
        store.startTurn(scope, identity, "snapshot-1")
        store.recordUserMessage(scope, "msg-1", "Long running task")

        val unfinished = store.unfinishedTurnScopesForRecovery()
        assertEquals(1, unfinished.size)
        assertEquals(scope, unfinished.first())

        val terminationEnvelope = store.terminateInterruptedTurn(
            scope = scope,
            recoveryId = "rec-1",
            notice = "Process terminated by user"
        )
        assertEquals(AgentEventKind.TURN_COMPLETED, terminationEnvelope.eventKind)

        val updatedUnfinished = store.unfinishedTurnScopesForRecovery()
        assertTrue(updatedUnfinished.isEmpty())

        val model = store.threadReadModel(threadId)
        assertEquals("interrupted", model?.turns?.first()?.terminalStatus)
    } }

    @Test
    fun observeThreadReadModelEmitsUpdates() { runBlocking {
        val threadId = "test-thread-flow"
        val initial = store.observeThreadReadModel(threadId).first()
        assertNull(initial)

        store.createThread(threadId, "Observed Thread")
        val afterCreate = store.observeThreadReadModel(threadId).first()
        assertNotNull(afterCreate)
        assertEquals("Observed Thread", afterCreate?.metadata?.title)
    } }
}
