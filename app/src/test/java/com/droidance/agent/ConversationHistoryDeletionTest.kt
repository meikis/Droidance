// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.InMemoryAgentEventStore
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryDeletionTest {
    @Test fun physicalDeletionKeepsSharedAndKnowledgeEvidenceAndCanBeRetried() = runBlocking {
        val root = Files.createTempDirectory("conversation-delete").toFile()
        try {
            val blobs = FileBlobStore(root)
            val events = InMemoryAgentEventStore()
            val own = blobs.put("private screenshot".toByteArray()).contentRef
            val shared = blobs.put("shared screenshot".toByteArray()).contentRef
            val knowledge = blobs.put("learning evidence".toByteArray()).contentRef
            events.createThread("deleted", "$own $shared")
            events.createThread("retained", shared)
            events.createThread(INTERNAL_SKILL_THREAD_ID, knowledge)
            purgeConversationData(events, blobs, setOf("deleted"))
            assertTrue(events.eventsForThread("deleted").isEmpty())
            assertEquals(setOf(shared, knowledge), blobs.listAllRefs())
            purgeConversationData(events, blobs, setOf("deleted"))
            assertEquals(setOf(shared, knowledge), blobs.listAllRefs())
            purgeConversationData(events, blobs, setOf("retained"))
            assertEquals(setOf(knowledge), blobs.listAllRefs())
        } finally { root.deleteRecursively() }
    }

    @Test fun aFalseDeletionReceiptCannotHideARetainedOrphan() = runBlocking {
        val root = Files.createTempDirectory("conversation-delete-false").toFile()
        try {
            val files = FileBlobStore(root)
            val ref = files.put("retained orphan".toByteArray()).contentRef
            val failing = object : BlobStore by files {
                override fun delete(contentRef: String): Boolean = false
            }
            assertThrows(IOException::class.java) {
                runBlocking { purgeConversationData(InMemoryAgentEventStore(), failing, emptySet()) }
            }
            assertEquals(setOf(ref), files.listAllRefs())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedAttachmentCleanupIsReportedAndARetryFinishesTheSweep() = runBlocking {
        val root = Files.createTempDirectory("conversation-delete-failure").toFile()
        try {
            val files = FileBlobStore(root)
            val ref = files.put("orphan".toByteArray()).contentRef
            val events = InMemoryAgentEventStore()
            events.createThread("deleted", ref)
            val failing = object : BlobStore by files {
                override fun delete(contentRef: String): Boolean = throw IOException("Storage unavailable")
            }
            assertThrows(IOException::class.java) {
                runBlocking { purgeConversationData(events, failing, setOf("deleted")) }
            }
            assertTrue(events.eventsForThread("deleted").isEmpty())
            assertEquals(setOf(ref), files.listAllRefs())
            purgeConversationData(events, files, emptySet())
            assertTrue(files.listAllRefs().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
