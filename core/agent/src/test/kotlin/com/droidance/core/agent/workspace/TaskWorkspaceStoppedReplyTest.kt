// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.model.llm.session.ModelTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TaskWorkspaceStoppedReplyTest {
    private val scope = TurnEventScope("thread", "turn")
    private val policy = WorkspacePolicy(8, 8192, 65536, 128)

    private suspend fun store(): InMemoryAgentEventStore = InMemoryAgentEventStore().also {
        val hash = "a".repeat(64)
        it.createThread(scope.threadId, "workspace")
        it.startTurn(scope, ModelSessionIdentity(scope.threadId, "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS,
            hash, hash, "fixture", hash), "snapshot")
    }

    @Test fun rejectedRuntimeDraftCannotReplaceRetainedAuthoredRecords(): Unit = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        workspace.write(scope, "notes.md", "original observations A and B", false, null, "notes", policy)
        workspace.write(scope, "findings.md", "recorded findings A and B", false, null, "findings", policy)
        workspace.write(scope, ".completion/done.json", "rejected draft ".repeat(350), false, null,
            "draft", policy, runtimeOwned = true)
        val snapshot = events.eventsForTurn(scope.turnId)

        val excerpt = workspace.stoppedReplyExcerpt(snapshot, scope, 1666)

        assertTrue(excerpt.contains("original observations A and B"))
        assertTrue(excerpt.contains("recorded findings A and B"))
        assertFalse(excerpt.contains("rejected draft"))
        assertFalse(excerpt.contains(".completion/"))
        assertEquals(snapshot, events.eventsForTurn(scope.turnId))
        assertEquals(3, workspace.list(scope).size)
    }

    @Test fun committedSnapshotSurvivesLaterWritesAndDoesNotCrossTurnOrThread(): Unit = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        val first = workspace.write(scope, "notes.md", "snapshot content", false, null, "first", policy)
        val snapshot = events.eventsForTurn(scope.turnId)
        workspace.write(scope, "notes.md", "later content", false, first.contentRef, "later", policy)

        assertTrue(workspace.stoppedReplyExcerpt(snapshot, scope, 256).contains("snapshot content"))
        assertFalse(workspace.stoppedReplyExcerpt(snapshot, scope, 256).contains("later content"))
        assertEquals("", workspace.stoppedReplyExcerpt(snapshot, scope.copy(turnId = "other"), 256))
        assertEquals("", workspace.stoppedReplyExcerpt(snapshot, scope.copy(threadId = "other"), 256))
        assertTrue(workspace.stoppedReplyExcerpt(events.eventsForTurn(scope.turnId), scope, 256).contains("later content"))
    }

    @Test fun truncationRemainsBoundedAndExplicitWithoutSplittingEmoji(): Unit = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        workspace.write(scope, "notes.md", "A😀B" + "0123456789".repeat(10), false, null, "notes", policy)
        val snapshot = events.eventsForTurn(scope.turnId)
        val marker = "\n[excerpt truncated: middle omitted; not a complete record]\n"
        val budget = "notes.md\n".length + 4 + marker.length

        val excerpt = workspace.stoppedReplyExcerpt(snapshot, scope, budget)

        assertTrue(excerpt.length <= budget)
        assertEquals("notes.md\nA${marker}789", excerpt)
        assertEquals("", workspace.stoppedReplyExcerpt(snapshot, scope, 0))
        assertThrows(IllegalArgumentException::class.java) { workspace.stoppedReplyExcerpt(snapshot, scope, -1) }
    }

    @Test fun cumulativeTailAndCompleteSmallRecordsSurviveStoppedReplyProjection(): Unit = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        val text = (1..21).joinToString("\n") { "$it. recorded object and original source ".repeat(3) } +
            "\nRecorded findings include 21 entries; source order still requires verification."
        workspace.write(scope, "work.md", text, false, null, "findings", policy)
        val history = events.eventsForTurn(scope.turnId)
        val full = workspace.stoppedReplyExcerpt(history, scope, 6666)
        assertEquals("work.md\n$text", full)
        val bounded = workspace.stoppedReplyExcerpt(history, scope, 1666)
        assertTrue(bounded.contains("1. recorded object"))
        assertTrue(bounded.contains("21. recorded object"))
        assertTrue(bounded.contains("source order still requires verification"))
        assertTrue(bounded.contains("not a complete record"))
        assertTrue(bounded.length <= 1666)
    }

    @Test fun unavailableContentDoesNotEraseOtherRetainedFilesAndCancellationPropagates(): Unit = runBlocking {
        val events = store()
        val blobs = InMemoryBlobStore()
        val workspace = TaskWorkspace(events, blobs)
        workspace.write(scope, "notes.md", "available content", false, null, "notes", policy)
        val missing = workspace.write(scope, "findings.md", "unavailable content", false, null, "findings", policy)
        val snapshot = events.eventsForTurn(scope.turnId)
        blobs.delete(missing.contentRef)

        val excerpt = workspace.stoppedReplyExcerpt(snapshot, scope, 256)
        assertTrue(excerpt.contains("[retained content unavailable]"))
        assertTrue(excerpt.contains("available content"))

        val cancelled = TaskWorkspace(events, object : BlobStore by blobs {
            override fun read(contentRef: String, maxBytes: Long): ByteArray = throw CancellationException("cancelled")
        })
        assertThrows(CancellationException::class.java) { cancelled.stoppedReplyExcerpt(snapshot, scope, 256) }
        assertEquals(snapshot, events.eventsForTurn(scope.turnId))
    }

    @Test fun onlyRuntimeDraftsSupplyNoAuthoredExcerpt(): Unit = runBlocking {
        val events = store()
        val workspace = TaskWorkspace(events, InMemoryBlobStore())
        workspace.write(scope, ".completion/done.json", "rejected candidate", false, null, "draft", policy,
            runtimeOwned = true)

        assertEquals("", workspace.stoppedReplyExcerpt(events.eventsForTurn(scope.turnId), scope, 1666))
        assertEquals(1, workspace.list(scope).size)
    }
}
