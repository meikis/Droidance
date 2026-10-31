// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import com.droidance.core.agent.CompletionReviewCandidate
import com.droidance.core.agent.CompletionReviewGate
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.knowledge.tools.MemoryToolRegistrar
import com.droidance.core.agent.tools.Tool
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableMemoryStorePortTest {
    @Test
    fun groundedUpdatesExpandAndDeleteThroughProfileAndLongTermStores() = runBlocking {
        val storage = InMemoryMemoryDocumentStorage()
        val store = durableStore(storage)
        val upserts = listOf(
            DurableMemoryUpdate(
                scope = DurableMemoryScope.USER_PROFILE,
                operation = DurableMemoryOperation.UPSERT,
                key = "reply_style",
                category = "communication",
                content = "回复要简洁",
                sourceQuote = "回复要简洁"
            ),
            DurableMemoryUpdate(
                scope = DurableMemoryScope.LONG_TERM,
                operation = DurableMemoryOperation.UPSERT,
                key = "deployment",
                category = "project",
                content = "部署前先运行 JVM 测试",
                sourceQuote = "部署前先运行 JVM 测试"
            )
        )

        val appliedUpserts = store.applyUpdates(upserts) {
            upserts.map(DurableMemoryUpdate::sourceQuote)
        }.getOrThrow()
        assertEquals(2, appliedUpserts.requested)
        assertEquals(2, appliedUpserts.grounded)
        assertEquals(2, appliedUpserts.changed)
        assertEquals(listOf("committed", "committed"), appliedUpserts.items.map { it.status })
        assertEquals("回复要简洁", store.expand("profile:reply_style")?.content)
        assertEquals("部署前先运行 JVM 测试", store.expand("long_term:deployment")?.content)

        val deletes = upserts.map { it.copy(operation = DurableMemoryOperation.DELETE, content = "") }
        val appliedDeletes = store.applyUpdates(deletes) {
            deletes.map(DurableMemoryUpdate::sourceQuote)
        }.getOrThrow()
        assertEquals(2, appliedDeletes.requested)
        assertEquals(2, appliedDeletes.grounded)
        assertEquals(2, appliedDeletes.changed)
        assertEquals(listOf("committed", "committed"), appliedDeletes.items.map { it.status })
        assertNull(store.expand("profile:reply_style"))
        assertNull(store.expand("long_term:deployment"))
        assertEquals(4, storage.writeCount)
    }

    @Test
    fun repeatedIdenticalUpsertDoesNotWriteTheDocumentAgain() = runBlocking {
        val storage = InMemoryMemoryDocumentStorage()
        val store = durableStore(storage)
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "language",
            category = "communication",
            content = "使用简体中文",
            sourceQuote = "使用简体中文"
        )

        assertEquals(1, store.applyUpdates(listOf(update)) { listOf(update.sourceQuote) }
            .getOrThrow().changed)
        val firstDocument = storage.readFile(ProfileStore.PATH)
        val second = store.applyUpdates(listOf(update)) { listOf(update.sourceQuote) }.getOrThrow()

        assertEquals(0, second.changed)
        assertEquals(1, storage.writeCount)
        assertEquals(firstDocument, storage.readFile(ProfileStore.PATH))
    }

    @Test
    fun localPersistencePreservesReviewWhileChangedPublicContentNeedsAnotherReview() = runBlocking {
        val storage = InMemoryMemoryDocumentStorage()
        val store = durableStore(storage)
        var tool: Tool? = null
        MemoryToolRegistrar({ tool = it }, { updates ->
            store.applyUpdates(updates) { listOf("使用简体中文", "使用英文") }
        }).registerAll()
        fun arguments(value: String, revision: String) = buildJsonObject {
            put("updates", buildJsonArray { add(buildJsonObject {
                put("scope", "user_profile"); put("operation", "upsert"); put("key", "language")
                put("category", "communication"); put("content", value)
                put("source_quote", value); put("expected_revision", revision)
            }) })
        }.toString()
        val first = checkNotNull(tool).execute(arguments("使用简体中文", "absent"))
        assertEquals(true, first.stateChanged)
        val originalRevision = checkNotNull(store.expand("profile:language")).revision
        val gate = CompletionReviewGate(true)
        val candidate = CompletionReviewCandidate.from("done",
            """{"results":[{"text":"Stored the requested language."}]}""")
        fun confirm() = gate.request("Remember the requested language", "Review", emptySet(), candidate,
            confirmation = buildJsonObject { put("candidate_id", candidate.id) })
        assertTrue(confirm() != null)
        gate.recordDeliveredSources(emptyMap())

        val repeated = checkNotNull(tool).execute(arguments("使用简体中文", originalRevision))
        assertTrue(repeated.outputText.contains("MEMORY_UNCHANGED"))
        assertEquals(false, repeated.stateChanged)
        assertEquals(1, storage.writeCount)
        assertEquals(originalRevision, store.expand("profile:language")?.revision)
        assertEquals(false, gate.onToolSettled(ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING,
            repeated.executionStatus, repeated.stateChanged))
        assertNull(confirm())

        val changed = checkNotNull(tool).execute(arguments("使用英文", originalRevision))
        assertEquals(true, changed.stateChanged)
        assertEquals(2, storage.writeCount)
        assertEquals(false, gate.onToolSettled(ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING,
            changed.executionStatus, changed.stateChanged))
        assertNull(confirm())

        // Local persistence is not a device mutation. A revised public outcome is
        // still a distinct candidate, with its own source-review opportunity.
        val revisedCandidate = CompletionReviewCandidate.from("done",
            """{"results":[{"text":"Stored the requested English language preference."}]}""")
        assertTrue(gate.request("Remember the requested language", "Review", emptySet(), revisedCandidate) != null)
    }

    @Test
    fun externalDocumentReplacementIsReadableAndRejectsAnOldEditSnapshot() {
        val storage = InMemoryMemoryDocumentStorage()
        val profile = ProfileStore(storage)
        val update = DurableMemoryUpdate(
            scope = DurableMemoryScope.USER_PROFILE,
            operation = DurableMemoryOperation.UPSERT,
            key = "address",
            category = "preference",
            content = "称呼用户为队长",
            sourceQuote = "称呼用户为队长"
        )
        profile.applyGroundedUpdates(listOf(update))
        val snapshot = profile.editSnapshot()

        storage.replace(
            ProfileStore.PATH,
            "# User Profile\n\n- [memory:key=external][category=general][updated=external] 外部替换\n"
        )

        assertTrue(profile.readForDisplay().contains("外部替换"))
        assertEquals(
            MemoryDisplaySaveResult.STALE,
            profile.saveDisplayEdit(
                sourceDocument = snapshot.sourceDocument,
                editedDisplay = snapshot.displayText + "\n- 编辑后的内容"
            )
        )
        assertTrue(storage.readFile(ProfileStore.PATH).orEmpty().contains("外部替换"))
    }

    @Test
    fun writeFailureReturnsFailureAndCancellationIsPropagated() = runBlocking {
        val failure = IllegalStateException("storage write failed")
        val failingStorage = InMemoryMemoryDocumentStorage().also { it.writeFailure = failure }
        val update = profileUpdate()
        val failed = durableStore(failingStorage).applyUpdates(listOf(update)) {
            listOf(update.sourceQuote)
        }

        val failedResult = failed.getOrThrow()
        assertEquals(1, failedResult.requested)
        assertEquals(1, failedResult.grounded)
        assertEquals(0, failedResult.changed)
        assertEquals("failed", failedResult.items.single().status)
        assertEquals(failure.message, failedResult.items.single().error)

        val cancellation = CancellationException("memory write cancelled")
        val cancelledStorage = InMemoryMemoryDocumentStorage().also { it.writeFailure = cancellation }
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                durableStore(cancelledStorage).applyUpdates(listOf(update)) {
                    listOf(update.sourceQuote)
                }
            }
        }
        assertSame(cancellation, thrown)
    }

    private fun durableStore(storage: MemoryDocumentStorage): DurableMemoryStore = DurableMemoryStore(
        profileStore = ProfileStore(storage),
        longTermMemoryStore = LongTermMemoryStore(storage)
    )

    private fun profileUpdate(): DurableMemoryUpdate = DurableMemoryUpdate(
        scope = DurableMemoryScope.USER_PROFILE,
        operation = DurableMemoryOperation.UPSERT,
        key = "timezone",
        category = "preference",
        content = "使用东京时区",
        sourceQuote = "使用东京时区"
    )

    private class InMemoryMemoryDocumentStorage : MemoryDocumentStorage {
        private val documents = mutableMapOf<String, String>()
        var writeCount: Int = 0
            private set
        var writeFailure: Throwable? = null

        override fun readFile(relativePath: String): String? = documents[relativePath]

        override fun writeFile(relativePath: String, content: String) {
            writeCount++
            writeFailure?.let { throw it }
            documents[relativePath] = content
        }

        override fun exists(relativePath: String): Boolean = relativePath in documents

        fun replace(relativePath: String, content: String) {
            documents[relativePath] = content
        }
    }
}
