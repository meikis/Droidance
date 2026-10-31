// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.*
import com.droidance.core.agent.llm.session.*
import com.droidance.core.model.llm.session.*
import com.droidance.core.agent.tools.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TaskWorkspaceTest {
    private val policy = WorkspacePolicy(4, 128, 1024, 20)
    private val scope = TurnEventScope("thread", "turn")

    private suspend fun store(): InMemoryAgentEventStore = InMemoryAgentEventStore().also {
        val hash = "1".repeat(64)
        it.createThread(scope.threadId, "Workspace test")
        it.startTurn(scope, ModelSessionIdentity(scope.threadId, "fixture", ModelTransport.GENERIC_CHAT_COMPLETIONS,
            hash, hash, "fixture", hash), "fixture")
    }

    @Test fun committedFilesSurviveNewServiceAndRemainTaskScoped(): Unit = runBlocking {
        val events = store()
        val blobs = InMemoryBlobStore()
        val files = TaskWorkspace(events, blobs)
        val first = files.write(scope, "notes/plan.md", "中文\nobserved", false, null, "write", policy)
        val restored = TaskWorkspace(events, blobs)
        assertEquals("中文\nobserved", restored.read(scope, first.path, policy))
        assertTrue(restored.list(TurnEventScope("other", "turn")).isEmpty())
        assertTrue(restored.list(TurnEventScope("thread", "other")).isEmpty())
        assertTrue(TaskWorkspace.overview(events.eventsForTurn(scope.turnId), scope, 1000).contains("notes/plan.md"))
        assertFalse(events.eventsForTurn(scope.turnId).any { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED })
        AgentEventReadModelProjector.project(events.eventsForThread(scope.threadId))
    }

    @Test fun replayingAnAppendReceiptDoesNotAppendTwiceAndConflictsAreRejected(): Unit = runBlocking {
        val files = TaskWorkspace(store(), InMemoryBlobStore())
        val first = files.write(scope, "data", "A", false, null, "first", policy)
        val added = files.write(scope, "data", "B", true, first.contentRef, "append", policy)
        assertEquals(added, files.write(scope, "data", "B", true, first.contentRef, "append", policy))
        assertEquals("AB", files.read(scope, "data", policy))
        assertThrows(IllegalStateException::class.java) { runBlocking {
            files.write(scope, "data", "C", true, null, "append", policy)
        } }
        assertThrows(IllegalStateException::class.java) { runBlocking {
            files.write(scope, "data", "C", true, first.contentRef, "stale", policy)
        } }
        assertEquals("AB", files.read(scope, "data", policy))
    }

    @Test fun failedEventCommitDoesNotPublishAFileAndRetryCanCommit(): Unit = runBlocking {
        val events = store()
        var reject = true
        val failing = object : AgentEventStore by events {
            override suspend fun appendAtomically(eventsToAppend: List<AgentEventDraft>): List<AgentEventEnvelope> {
                if (reject) error("disk full")
                return events.appendAtomically(eventsToAppend)
            }
        }
        val files = TaskWorkspace(failing, InMemoryBlobStore())
        assertThrows(IllegalStateException::class.java) { runBlocking {
            files.write(scope, "data", "retained", false, null, "first", policy)
        } }
        assertTrue(files.list(scope).isEmpty())
        reject = false
        files.write(scope, "data", "retained", false, null, "first", policy)
        assertEquals("retained", files.read(scope, "data", policy))
    }

    @Test fun versionStorageLimitCountsOverwritesAndPathErrorsExecuteNothing(): Unit = runBlocking {
        val events = store()
        val files = TaskWorkspace(events, InMemoryBlobStore())
        val limited = policy.copy(maxRetainedBytes = 5)
        files.write(scope, "data", "abc", false, null, "first", limited)
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            files.write(scope, "data", "def", false, null, "second", limited)
        } }
        for (path in listOf("../data", "/data", "a/../data", "a\\data", "a//data")) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                files.write(scope, path, "x", false, null, path, policy)
            } }
        }
        assertEquals("abc", files.read(scope, "data", policy))
        assertEquals(1, files.list(scope).size)
    }

    @Test fun toolNeedsOnlyTaskIdentityAndCancellationDoesNotRemoveFiles(): Unit = runBlocking {
        val events = store()
        val files = TaskWorkspace(events, InMemoryBlobStore())
        val tool = WorkspaceTool(files, { scope }, { if (it == "policy") Json.encodeToString(policy) else "Task files" },
            { error("No artifact requested") })
        val committed = CompletableDeferred<Unit>()
        val job = launch {
            val result = withContext(ToolInvocation("write")) {
                tool.execute("""{"op":"write","path":"plan.md","text":"retained"}""")
            }
            assertTrue(result.isVerifiedSuccess)
            committed.complete(Unit)
            awaitCancellation()
        }
        committed.await()
        job.cancelAndJoin()
        assertTrue(tool.execute("""{"op":"read","path":"plan.md"}""").outputText.contains("retained"))
        assertEquals(ToolExecutionStatus.FAILED,
            tool.execute("""{"op":"write","source_turn_id":"turn","path":"plan.md","text":"no"}""").executionStatus)
        assertThrows(IllegalStateException::class.java) { runBlocking { files.readScope(scope, "unknown") } }
    }

    @Test
    fun toolReadPagesUseUtf16OffsetsAndRejectAnOldExpectedReference(): Unit = runBlocking {
        val events = store()
        val blobs = InMemoryBlobStore()
        val files = TaskWorkspace(events, blobs)
        val pagePolicy = policy.copy(pageChars = 2)
        val tool = WorkspaceTool(files, { scope }, {
            if (it == "policy") Json.encodeToString(pagePolicy) else "Task files"
        }, { error("No artifact requested") })

        val write = withContext(ToolInvocation("write-emoji")) {
            tool.execute("""{"op":"write","path":"record.md","text":"A😀B","activate":true}""")
        }
        assertTrue(write.isVerifiedSuccess)
        val version = files.list(scope).single()

        val first = tool.execute("""{"op":"read","path":"record.md","offset":0,"expected_ref":"${version.contentRef}"}""")
        assertTrue(first.isVerifiedSuccess)
        val firstPage = Json.parseToJsonElement(first.outputText).jsonObject
        assertEquals("A", firstPage.getValue("text").jsonPrimitive.content)
        assertEquals(1, firstPage.getValue("next_offset").jsonPrimitive.int)
        assertTrue(firstPage.getValue("has_more").jsonPrimitive.boolean)
        assertEquals(version.contentRef, firstPage.getValue("content_ref").jsonPrimitive.content)

        val second = tool.execute("""{"op":"read","path":"record.md","offset":1,"expected_ref":"${version.contentRef}"}""")
        assertTrue(second.isVerifiedSuccess)
        assertEquals("😀", Json.parseToJsonElement(second.outputText).jsonObject.getValue("text").jsonPrimitive.content)

        val stale = tool.execute(
            """{"op":"read","path":"record.md","offset":0,"expected_ref":"sha256:${"0".repeat(64)}"}"""
        )
        assertEquals(ToolExecutionStatus.FAILED, stale.executionStatus)
        assertTrue(stale.outputText.contains("WORKSPACE_VERSION_CHANGED"))
    }
}
