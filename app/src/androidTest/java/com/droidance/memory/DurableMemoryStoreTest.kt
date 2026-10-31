// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableMemoryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val temporaryFolder = TemporaryFolder(context.cacheDir)

    private fun files() = MarkdownFileManager(object : ContextWrapper(context) {
        override fun getExternalFilesDir(type: String?): File = temporaryFolder.root
    })

    private fun store(files: MarkdownFileManager = files()) =
        DurableMemoryStore(ProfileStore(files), LongTermMemoryStore(files))

    @Test
    fun atomicTextAndBinaryReplacementSurviveReopeningStorage() {
        val storage = files()
        storage.writeFile("global/test.md", "old")
        storage.writeFile("global/test.md", "new")
        storage.writeBytes("global/frame.bin", byteArrayOf(1))
        storage.writeBytes("global/frame.bin", byteArrayOf(2, 3))
        assertEquals("new", files().readFile("global/test.md"))
        assertArrayEquals(byteArrayOf(2, 3), files().readBytes("global/frame.bin"))
        assertFalse(storage.listFiles("global").any { it.endsWith(".tmp") })
    }

    @Test
    fun rejectedAtomicReplacementPreservesTargetAndRemovesTemporaryFiles() {
        val storage = files()
        storage.writeFile("global/target/child", "preserve")
        assertThrows(IOException::class.java) { storage.writeFile("global/target", "replacement") }
        assertThrows(IOException::class.java) { storage.writeBytes("global/target", byteArrayOf(1)) }
        assertEquals("preserve", files().readFile("global/target/child"))
        assertFalse(storage.listFiles("global").any { it.endsWith(".tmp") })
    }

    private val update = DurableMemoryUpdate(
        scope = DurableMemoryScope.USER_PROFILE,
        operation = DurableMemoryOperation.UPSERT,
        key = "language",
        category = "language",
        content = "Reply in English",
        sourceQuote = "Reply in English"
    )

    @Test
    fun trustedSourceFailureReturnsFailureWithoutWritingMemory() = runBlocking {
        val files = files()
        val failure = IOException("source unavailable")

        val result = store(files).applyUpdates(listOf(update)) { throw failure }

        assertSame(failure, result.exceptionOrNull())
        assertFalse(files.exists(ProfileStore.PATH))
    }

    @Test
    fun profileReadFailureReturnsFailureWithoutReplacingExistingDirectory() = runBlocking {
        val files = files()
        // A directory at the target file makes persistence fail without touching user data.
        files.writeFile("global/USER.md/blocker", "fixture")
        val result = store(files).applyUpdates(listOf(update)) { listOf(update.sourceQuote) }

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().requested)
        assertEquals(1, result.getOrThrow().grounded)
        assertEquals(0, result.getOrThrow().changed)
        assertEquals("failed", result.getOrThrow().items.single().status)
        assertTrue(result.getOrThrow().items.single().error.orEmpty().isNotBlank())
        assertTrue(File(temporaryFolder.root, "droidance-data/global/USER.md").isDirectory)
    }

    @Test
    fun profileWriteFailureDoesNotPublishCachedSuccess() = runBlocking {
        val files = files()
        files.writeFile("global", "fixture blocking the parent directory")
        val profile = ProfileStore(files)
        val store = DurableMemoryStore(profile, LongTermMemoryStore(files))

        val result = store.applyUpdates(listOf(update)) { listOf(update.sourceQuote) }

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().requested)
        assertEquals(1, result.getOrThrow().grounded)
        assertEquals(0, result.getOrThrow().changed)
        assertEquals("failed", result.getOrThrow().items.single().status)
        assertTrue(result.getOrThrow().items.single().error.orEmpty().isNotBlank())
        assertEquals("", profile.readForDisplay())
    }

    @Test
    fun longTermStorageFailureIsAlsoIsolated() = runBlocking {
        val files = files()
        files.writeFile("agents", "fixture blocking the parent directory")
        val result = store(files).applyUpdates(listOf(update.copy(scope = DurableMemoryScope.LONG_TERM))) {
            listOf(update.sourceQuote)
        }

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().requested)
        assertEquals(1, result.getOrThrow().grounded)
        assertEquals(0, result.getOrThrow().changed)
        assertEquals("failed", result.getOrThrow().items.single().status)
        assertTrue(result.getOrThrow().items.single().error.orEmpty().isNotBlank())
        assertFalse(files.exists("agents/default/MEMORY.md"))
    }

    @Test
    fun trustedUpdatePersistsAndUntrustedUpdateDoesNot() = runBlocking {
        val files = files()
        val result = store(files).applyUpdates(listOf(update, update.copy(
            key = "untrusted", content = "Untrusted fact", sourceQuote = "not user authored"
        ))) { listOf(update.sourceQuote) }

        assertEquals(2, result.getOrThrow().requested)
        assertEquals(1, result.getOrThrow().grounded)
        assertEquals(1, result.getOrThrow().changed)
        assertEquals(listOf("committed", "rejected"), result.getOrThrow().items.map { it.status })
        val stored = files.readFile(ProfileStore.PATH)!!
        assertTrue(stored.contains("[memory:key=language]"))
        assertFalse(stored.contains("[memory:key=untrusted]"))
    }

    @Test
    fun staleExpectedRevisionReturnsItemFailureWithoutChangingTheDocument() = runBlocking {
        val files = files()
        val memory = store(files)
        val initial = update.copy(
            expectedRevision = "absent",
            operationId = "memory-initial",
            sourceRef = "turn:initial"
        )
        assertEquals(1, memory.applyUpdates(listOf(initial)) { listOf(initial.sourceQuote) }
            .getOrThrow().changed)
        val before = files.readFile(ProfileStore.PATH)

        val stale = initial.copy(
            content = "Changed after another editor won",
            operationId = "memory-stale",
            expectedRevision = "absent"
        )
        val result = memory.applyUpdates(listOf(stale)) { listOf(stale.sourceQuote) }.getOrThrow()

        assertEquals(1, result.requested)
        assertEquals(1, result.grounded)
        assertEquals(0, result.changed)
        assertEquals("failed", result.items.single().status)
        assertEquals("MEMORY_REVISION_CONFLICT", result.items.single().error)
        assertEquals(before, files.readFile(ProfileStore.PATH))
    }

    @Test
    fun retryingTheSameOperationIdIsIdempotentAndKeepsTheAuditRecord() = runBlocking {
        val files = files()
        val memory = store(files)
        val operation = update.copy(
            expectedRevision = "absent",
            operationId = "memory-idempotent",
            sourceRef = "turn:retry"
        )
        val first = memory.applyUpdates(listOf(operation)) { listOf(operation.sourceQuote) }.getOrThrow()
        val before = files.readFile(ProfileStore.PATH)
        val retry = memory.applyUpdates(listOf(operation)) { listOf(operation.sourceQuote) }.getOrThrow()

        assertEquals(1, first.changed)
        assertEquals(0, retry.changed)
        assertEquals("unchanged", retry.items.single().status)
        assertEquals(before, files.readFile(ProfileStore.PATH))
        assertTrue(before.orEmpty().contains("<!-- memory-audit:"))
    }

    @Test
    fun profileDisplayEditPreservesHiddenMetadataAndAuditHistory() = runBlocking {
        val files = files()
        val memory = store(files)
        val operation = update.copy(
            expectedRevision = "absent",
            operationId = "memory-ui-edit",
            sourceRef = "turn:ui"
        )
        memory.applyUpdates(listOf(operation)) { listOf(operation.sourceQuote) }.getOrThrow()
        val profile = ProfileStore(files)
        val snapshot = profile.editSnapshot()
        val edited = snapshot.displayText.replace("Reply in English", "Use simplified Chinese")

        assertEquals(MemoryDisplaySaveResult.SAVED, profile.saveDisplayEdit(snapshot.sourceDocument, edited))
        val stored = files.readFile(ProfileStore.PATH).orEmpty()
        assertTrue(stored.contains("[memory:key=language]"))
        assertTrue(stored.contains("[category=language]"))
        assertTrue(stored.contains("Use simplified Chinese"))
        assertTrue(stored.contains("<!-- memory-audit:"))
    }

    @Test
    fun expiredMemoryIsAbsentFromDurableContextEntries() = runBlocking {
        val files = files()
        val memory = store(files)
        val expired = update.copy(
            expectedRevision = "absent",
            operationId = "memory-expired",
            sourceRef = "turn:expiry",
            expiresAt = "2000-01-01T00:00:00Z"
        )

        val result = memory.applyUpdates(listOf(expired)) { listOf(expired.sourceQuote) }.getOrThrow()

        assertEquals(1, result.changed)
        assertTrue(memory.contextSnapshot().requireAvailable().entries.none { it.ref == "profile:language" })
        assertNull(memory.expand("profile:language"))
    }

    @Test
    fun batchCountsFinalUniqueChangesAcrossBothScopes() = runBlocking {
        val files = files()
        val store = store(files)
        val updates = DurableMemoryScope.entries.flatMap { scope ->
            listOf(
                update.copy(scope = scope),
                update.copy(scope = scope, content = "Final value"),
                update.copy(scope = scope, operation = DurableMemoryOperation.DELETE, key = "missing")
            )
        }
        val result = store.applyUpdates(updates) {
            listOf(update.sourceQuote)
        }.getOrThrow()
        assertEquals(6, result.requested)
        assertEquals(6, result.grounded)
        assertEquals(2, result.changed)
        assertEquals(
            listOf("superseded", "committed", "unchanged", "superseded", "committed", "unchanged"),
            result.items.map { it.status }
        )
        for (path in listOf(ProfileStore.PATH, "agents/default/MEMORY.md")) {
            val document = files.readFile(path)!!
            assertTrue(document.contains("Final value"))
            assertFalse(document.contains("Reply in English"))
        }
    }

    @Test
    fun netUnchangedBatchPreservesBothDocumentsAndModificationTimes() = runBlocking {
        val files = files()
        val paths = listOf(ProfileStore.PATH, "agents/default/MEMORY.md")
        val document = "# Memory\n\n- [memory:key=language][category=language][updated=old] Reply in English\n\n"
        for (path in paths) {
            files.writeFile(path, document)
            assertTrue(File(temporaryFolder.root, "droidance-data/$path").setLastModified(1_600_000_000_000L))
        }
        val before = paths.associateWith(files::lastModified)
        val updates = DurableMemoryScope.entries.flatMap { scope ->
            listOf(update.copy(scope = scope, content = "Temporary value"), update.copy(scope = scope))
        }
        val result = store(files).applyUpdates(updates) {
            listOf(update.sourceQuote)
        }.getOrThrow()
        assertEquals(4, result.requested)
        assertEquals(4, result.grounded)
        assertEquals(0, result.changed)
        assertEquals(
            listOf("superseded", "unchanged", "superseded", "unchanged"),
            result.items.map { it.status }
        )
        for (path in paths) {
            assertEquals(document, files.readFile(path))
            assertEquals(before[path], files.lastModified(path))
        }
    }

    @Test
    fun netAbsentBatchDoesNotCreateEitherDocument() = runBlocking {
        val files = files()
        val updates = DurableMemoryScope.entries.flatMap { scope ->
            listOf(update.copy(scope = scope), update.copy(scope = scope, operation = DurableMemoryOperation.DELETE))
        }
        val result = store(files).applyUpdates(updates) {
            listOf(update.sourceQuote)
        }.getOrThrow()
        assertEquals(4, result.requested)
        assertEquals(4, result.grounded)
        assertEquals(0, result.changed)
        assertEquals(
            listOf("superseded", "unchanged", "superseded", "unchanged"),
            result.items.map { it.status }
        )
        assertFalse(files.exists(ProfileStore.PATH))
        assertFalse(files.exists("agents/default/MEMORY.md"))
    }

    @Test
    fun sourceCancellationPropagatesWithoutAnyWrite() {
        val files = files()
        val cancellation = CancellationException("cancel source read")

        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            runBlocking { store(files).applyUpdates(listOf(update)) { throw cancellation } }
        })
        assertFalse(files.exists(ProfileStore.PATH))
    }

    @Test
    fun storageCancellationPropagatesRatherThanReturningFailure() {
        val cancellation = CancellationException("cancel storage access")
        val files = MarkdownFileManager(object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = throw cancellation
        })

        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            runBlocking { store(files).applyUpdates(listOf(update)) { listOf(update.sourceQuote) } }
        })
    }
}
