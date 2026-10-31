// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.AgentEventDraft
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskWorkspaceActiveContextTest {
    private val scope = TurnEventScope("thread", "turn")
    private val policy = WorkspacePolicy(
        maxFiles = 4,
        maxFileChars = 1024,
        maxRetainedBytes = 4096,
        pageChars = 32
    )

    private suspend fun store(): InMemoryAgentEventStore = InMemoryAgentEventStore().also {
        val hash = "a".repeat(64)
        it.createThread(scope.threadId, "workspace")
        it.startTurn(
            scope,
            ModelSessionIdentity(
                threadId = scope.threadId,
                providerId = "fixture",
                transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
                modelId = "fixture",
                transportConfigHash = hash,
                authScopeHash = hash,
                stablePrefixHash = hash
            ),
            "snapshot"
        )
    }

    @Test
    fun activatedWritePublishesFileAndFocusTogetherAndContextIsRestorable() = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())

        val version = workspace.write(
            scope = scope,
            path = "research.md",
            text = "observed result",
            append = false,
            expectedRef = null,
            writeId = "write-1",
            policy = policy,
            activate = true
        )

        val turnEvents = events.eventsForTurn(scope.turnId)
        assertEquals(
            listOf(AgentEventKind.WORKSPACE_FILE_WRITTEN, AgentEventKind.WORKSPACE_FOCUS_CHANGED),
            turnEvents.takeLast(2).map { it.eventKind }
        )
        val context = workspace.activeContext(turnEvents, scope, 1024)
        requireNotNull(context)
        assertEquals("research.md", context.path)
        assertEquals(version.contentRef, context.contentRef)
        assertEquals("observed result", context.text)
        assertEquals(context.totalChars, context.nextOffset)
        assertFalse(context.hasMore)

        val retry = workspace.write(
            scope, "research.md", "observed result", false, null, "write-1", policy, activate = true
        )
        assertEquals(version, retry)
        assertEquals(2, events.eventsForTurn(scope.turnId).count {
            it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN ||
                it.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED
        })
    }

    @Test
    fun activeContextExposesExplicitContinuationWhenRecordExceedsContextBudget() = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        val content = "0123456789".repeat(8)
        workspace.write(scope, "research.md", content, false, null, "write-1", policy, activate = true)

        val context = workspace.activeContext(events.eventsForTurn(scope.turnId), scope, 17)
        requireNotNull(context)
        assertEquals(content.substring(0, 17), context.text)
        assertEquals(content.length, context.totalChars)
        assertEquals(17, context.nextOffset)
        assertTrue(context.hasMore)
        assertTrue(context.render().contains("\"nextOffset\":17"))
        assertTrue(context.render().contains("\"hasMore\":true"))
    }

    @Test
    fun focusRequiresAnExistingFileAndIdempotencyDoesNotDuplicateSelection() = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        workspace.write(scope, "research.md", "one", false, null, "write-1", policy)

        assertEquals("research.md", workspace.focus(scope, "research.md", "focus-1").path)
        assertEquals("research.md", workspace.focus(scope, "research.md", "focus-1").path)
        assertEquals(1, events.eventsForTurn(scope.turnId).count {
            it.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED
        })
    }

    @Test
    fun activeFocusSurvivesServiceRecreationAndNeverCrossesTurnBoundary() = runBlocking {
        val events = store()
        val blobs = InMemoryBlobStore()
        val workspace = TaskWorkspace(events, blobs)
        workspace.write(scope, "research.md", "turn one", false, null, "write-1", policy, activate = true)

        val second = TurnEventScope(scope.threadId, "turn-two")
        val hash = "b".repeat(64)
        events.startTurn(second, ModelSessionIdentity(
            threadId = second.threadId,
            providerId = "fixture",
            transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
            transportConfigHash = hash,
            authScopeHash = hash,
            modelId = "fixture",
            stablePrefixHash = hash
        ), "snapshot-two")

        val restored = TaskWorkspace(events, blobs)
        val firstContext = restored.activeContext(events.eventsForTurn(scope.turnId), scope, 1024)
        assertEquals("turn one", firstContext?.text)
        assertEquals(null, restored.activeContext(events.eventsForTurn(second.turnId), second, 1024))
        assertTrue(restored.list(second).isEmpty())
    }

    @Test
    fun explicitFocusIsReconstructedAfterServiceRecreationAndRemainsTurnLocal() = runBlocking {
        val events = store()
        val blobs = InMemoryBlobStore()
        val workspace = TaskWorkspace(events, blobs)
        workspace.write(scope, "first.md", "first", false, null, "write-first", policy)
        workspace.write(scope, "second.md", "second", false, null, "write-second", policy)
        workspace.focus(scope, "second.md", "focus-second")

        val restored = TaskWorkspace(events, blobs)
        val active = restored.activeContext(events.eventsForTurn(scope.turnId), scope, 1024)
        assertEquals("second.md", active?.path)
        assertEquals("second", active?.text)

        val other = TurnEventScope(scope.threadId, "other-turn")
        val hash = "c".repeat(64)
        events.startTurn(
            other,
            ModelSessionIdentity(
                threadId = other.threadId,
                providerId = "fixture",
                transportKind = ModelTransport.GENERIC_CHAT_COMPLETIONS,
                modelId = "fixture",
                transportConfigHash = hash,
                authScopeHash = hash,
                stablePrefixHash = hash
            ),
            "snapshot-other"
        )
        assertEquals(null, restored.activeContext(events.eventsForTurn(other.turnId), other, 1024))
        assertEquals("second", restored.read(scope, "second.md", policy))
    }

    @Test
    fun activeContextUsesUtf16BudgetWithoutSplittingEmojiAndRejectsOneCharBudget() = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        val content = "A😀B" + "0123456789".repeat(8)
        workspace.write(scope, "research.md", content, false, null, "write-emoji", policy, activate = true)

        assertThrows(IllegalArgumentException::class.java) { policy.copy(pageChars = 1).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { workspace.activeContext(events.eventsForTurn(scope.turnId), scope, 1) }
        }
        val first = requireNotNull(workspace.activeContext(events.eventsForTurn(scope.turnId), scope, 2))
        assertEquals("A", first.text)
        assertEquals(1, first.nextOffset)
        assertTrue(first.hasMore)
        val second = requireNotNull(workspace.activeContext(events.eventsForTurn(scope.turnId), scope, 3))
        assertEquals("A😀", second.text)
        assertEquals(3, second.nextOffset)
        assertTrue(second.text.codePoints().toArray().contentEquals(intArrayOf('A'.code, 0x1F600)))
    }

    @Test
    fun atomicActivatedWriteFailurePublishesNeitherFileNorFocus() = runBlocking {
        val events = store()
        var fail = true
        val failing = object : AgentEventStore by events {
            override suspend fun appendAtomically(eventsToAppend: List<AgentEventDraft>): List<AgentEventEnvelope> {
                if (fail) error("disk full")
                return events.appendAtomically(eventsToAppend)
            }
        }
        val workspace = TaskWorkspace(failing, InMemoryBlobStore())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { workspace.write(scope, "research.md", "pending", false, null, "write-1", policy, activate = true) }
        }
        assertTrue(events.eventsForTurn(scope.turnId).none {
            it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN ||
                it.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED
        })

        fail = false
        workspace.write(scope, "research.md", "pending", false, null, "write-1", policy, activate = true)
        assertEquals(1, events.eventsForTurn(scope.turnId).count { it.eventKind == AgentEventKind.WORKSPACE_FILE_WRITTEN })
        assertEquals(1, events.eventsForTurn(scope.turnId).count { it.eventKind == AgentEventKind.WORKSPACE_FOCUS_CHANGED })
    }
}
