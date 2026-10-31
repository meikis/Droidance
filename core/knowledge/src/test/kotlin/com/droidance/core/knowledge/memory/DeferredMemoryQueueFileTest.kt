// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the outbox against actual files so restart behavior is observable. */
class DeferredMemoryQueueFileTest {
    @Test
    fun queuedMemorySurvivesQueueRecreationAndDrainCommitsTheCanonicalFile() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val memories = durableStore(storage)
            val update = update("address_as", "以后称呼我为 Alex")

            DeferredMemoryQueue(storage, memories).enqueue(
                threadId = "thread-1",
                turnId = "turn-1",
                updates = listOf(update),
                trustedTexts = listOf("以后称呼我为 Alex")
            )

            val outbox = storage.readFile(DeferredMemoryQueue.PATH).orEmpty()
            assertTrue(outbox.contains("thread-1"))
            assertTrue(outbox.contains("address_as"))

            val restarted = DeferredMemoryQueue(storage, memories)
            restarted.initialize()
            assertTrue(restarted.context("thread-1").contains("memory_write_state=pending"))
            restarted.drain()

            assertEquals("[]", storage.readFile(DeferredMemoryQueue.PATH))
            val profile = storage.readFile(ProfileStore.PATH).orEmpty()
            assertTrue(profile.contains("[memory:key=address_as]"))
            assertTrue(profile.contains("以后称呼我为 Alex"))
            assertFalse(restarted.context("thread-1").contains("memory_write_state="))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun newerGroundedProposalReplacesAnOlderQueuedProposalForTheSameKey() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val memories = durableStore(storage)
            val queue = DeferredMemoryQueue(storage, memories)
            queue.enqueue(
                threadId = "thread-1",
                turnId = "turn-1",
                updates = listOf(update("address_as", "以后称呼我为 Alex")),
                trustedTexts = listOf("以后称呼我为 Alex")
            )
            queue.enqueue(
                threadId = "thread-1",
                turnId = "turn-2",
                updates = listOf(update("address_as", "以后称呼我为队长")),
                trustedTexts = listOf("以后称呼我为队长")
            )

            val context = queue.context("thread-1")
            assertFalse(context.contains("以后称呼我为 Alex"))
            assertTrue(context.contains("以后称呼我为队长"))
            assertEquals(1, Regex("address_as").findAll(
                storage.readFile(DeferredMemoryQueue.PATH).orEmpty()
            ).count())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun enqueueingTheSameTurnTwiceIsIdempotentOnTheRealOutboxFile() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val queue = DeferredMemoryQueue(storage, durableStore(storage))
            val update = update("address_as", "以后称呼我为 Alex")

            repeat(2) {
                queue.enqueue("thread-1", "turn-1", listOf(update), listOf(update.sourceQuote))
            }

            val outbox = storage.readFile(DeferredMemoryQueue.PATH).orEmpty()
            assertEquals(1, Regex("address_as").findAll(outbox).count())
            assertTrue(outbox.contains("turn-1:deferred-memory:0"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun revisionConflictLeavesTheOldCanonicalValueAndMarksTheQueuedWriteFailed() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val memories = durableStore(storage)
            val existing = update("address_as", "以前称呼我为 Alex")
            memories.applyUpdates(listOf(existing)) { listOf(existing.sourceQuote) }.getOrThrow()

            val queued = update("address_as", "以后称呼我为队长")
            val queue = DeferredMemoryQueue(storage, memories)
            queue.enqueue("thread-1", "turn-2", listOf(queued), listOf(queued.sourceQuote))
            queue.drain()

            val profile = storage.readFile(ProfileStore.PATH).orEmpty()
            assertTrue(profile.contains("以前称呼我为 Alex"))
            assertFalse(profile.contains("以后称呼我为队长"))
            assertTrue(queue.context("thread-1").contains("memory_write_state=failed"))
            assertTrue(queue.context("thread-1").contains("MEMORY_REVISION_CONFLICT"))
            assertTrue(storage.readFile(DeferredMemoryQueue.PATH).orEmpty().contains("MEMORY_REVISION_CONFLICT"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun groundingRejectionDoesNotCreateAnOutboxEntryOrCanonicalMemoryFile() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val queue = DeferredMemoryQueue(storage, durableStore(storage))
            val update = update("address_as", "称呼我为 Alex")

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    queue.enqueue(
                        "thread-1", "turn-3", listOf(update),
                        trustedTexts = listOf("这句话没有包含被引用的内容")
                    )
                }
            }
            assertFalse(storage.exists(DeferredMemoryQueue.PATH))
            assertFalse(storage.exists(ProfileStore.PATH))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun contextUsesThePublishedOutboxSnapshotWhileAnEnqueueWriteIsBlocked() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val queue = DeferredMemoryQueue(storage, durableStore(storage))
            queue.initialize()
            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            storage.blockWrites = true
            storage.writeEntered = writeEntered
            storage.releaseWrite = releaseWrite

            val enqueue = launch(Dispatchers.Default) {
                queue.enqueue(
                    "thread-1", "turn-blocked", listOf(update("address_as", "以后称呼我为 Alex")),
                    listOf("以后称呼我为 Alex")
                )
            }
            try {
                assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
                val executor = Executors.newSingleThreadExecutor()
                val beforeCommit = try {
                    executor.submit<String> { queue.context("thread-1") }
                        .get(500L, TimeUnit.MILLISECONDS)
                } finally {
                    executor.shutdownNow()
                }
                assertFalse(beforeCommit.contains("memory_write_state=pending"))
                assertFalse(beforeCommit.contains("以后称呼我为 Alex"))
            } finally {
                releaseWrite.countDown()
                enqueue.join()
            }

            assertTrue(queue.context("thread-1").contains("memory_write_state=pending"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun contextUsesThePublishedOutboxSnapshotWhileDrainIsBlockedByCanonicalFileIo() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val memories = durableStore(storage)
            val queue = DeferredMemoryQueue(storage, memories)
            queue.enqueue(
                "thread-1", "turn-drain", listOf(update("address_as", "以后称呼我为 Alex")),
                listOf("以后称呼我为 Alex")
            )

            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            storage.blockWrites = true
            storage.blockedWritePath = ProfileStore.PATH
            storage.writeEntered = writeEntered
            storage.releaseWrite = releaseWrite
            val drain = launch(Dispatchers.Default) { queue.drain() }
            var secondEnqueue: kotlinx.coroutines.Job? = null
            try {
                assertTrue(writeEntered.await(1, TimeUnit.SECONDS))
                val enqueueJob = launch(Dispatchers.Default) {
                    queue.enqueue(
                        "thread-2", "turn-drain-second",
                        listOf(update("timezone", "时区是 Asia/Shanghai")),
                        listOf("时区是 Asia/Shanghai")
                    )
                }
                secondEnqueue = enqueueJob
                withTimeout(1_000L) { enqueueJob.join() }
                val executor = Executors.newSingleThreadExecutor()
                val pending = try {
                    executor.submit<String> { queue.context("thread-1") }
                        .get(500L, TimeUnit.MILLISECONDS)
                } finally {
                    executor.shutdownNow()
                }
                assertTrue(pending.contains("memory_write_state=pending"))
            } finally {
                secondEnqueue?.cancel()
                releaseWrite.countDown()
                drain.join()
            }

            assertFalse(queue.context("thread-1").contains("memory_write_state="))
            val profile = storage.readFile(ProfileStore.PATH).orEmpty()
            assertTrue(profile.contains("以后称呼我为 Alex"))
            assertTrue(profile.contains("时区是 Asia/Shanghai"))
            assertEquals("[]", storage.readFile(DeferredMemoryQueue.PATH))

            val restarted = DeferredMemoryQueue(storage, durableStore(storage))
            restarted.initialize()
            assertFalse(restarted.context("thread-1").contains("memory_write_state="))
            assertFalse(restarted.context("thread-2").contains("memory_write_state="))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentEnqueueCommitsEveryDistinctProposalAndRestartRestoresAllEntries() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val queue = DeferredMemoryQueue(storage, durableStore(storage))
            val count = 32
            coroutineScope {
                repeat(count) { index ->
                    launch(Dispatchers.Default) {
                        val text = "事实 $index"
                        queue.enqueue(
                            threadId = "thread-$index",
                            turnId = "turn-$index",
                            updates = listOf(update("key_$index", text)),
                            trustedTexts = listOf(text)
                        )
                    }
                }
            }

            val persisted = storage.readFile(DeferredMemoryQueue.PATH).orEmpty()
            val entries = kotlinx.serialization.json.Json.decodeFromString<List<DeferredMemoryQueue.Entry>>(persisted)
            assertEquals(count, entries.size)
            assertEquals((0 until count).map { "key_$it" }.toSet(), entries.map { it.update.key }.toSet())

            val restarted = DeferredMemoryQueue(storage, durableStore(storage))
            restarted.initialize()
            assertEquals(
                (0 until count).map { "key_$it" }.toSet(),
                (0 until count).mapNotNull { index ->
                    "key_$index".takeIf { restarted.context("thread-$index").contains(it) }
                }.toSet()
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedOutboxWriteDoesNotPublishTheUncommittedEntry() = runBlocking {
        val root = Files.createTempDirectory("droidance-memory-outbox-")
        try {
            val storage = FileMemoryDocumentStorage(root)
            val queue = DeferredMemoryQueue(storage, durableStore(storage))
            queue.initialize()
            Files.createDirectories(root.resolve(DeferredMemoryQueue.PATH))

            val thrown = runCatching {
                queue.enqueue(
                    "thread-1", "turn-failed", listOf(update("address_as", "以后称呼我为 Alex")),
                    listOf("以后称呼我为 Alex")
                )
            }.exceptionOrNull()

            assertTrue(thrown is java.nio.file.FileSystemException)
            val context = queue.context("thread-1")
            assertTrue(context.contains("memory_outbox_unavailable"))
            assertFalse(context.contains("以后称呼我为 Alex"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun durableStore(storage: MemoryDocumentStorage): DurableMemoryStore = DurableMemoryStore(
        profileStore = ProfileStore(storage),
        longTermMemoryStore = LongTermMemoryStore(storage)
    )

    private fun update(key: String, text: String) = DurableMemoryUpdate(
        scope = DurableMemoryScope.USER_PROFILE,
        operation = DurableMemoryOperation.UPSERT,
        key = key,
        category = "communication",
        content = text,
        sourceQuote = text,
        expectedRevision = "absent"
    )

    private class FileMemoryDocumentStorage(private val root: Path) : MemoryDocumentStorage {
        private val canonicalRoot = root.toAbsolutePath().normalize()
        @Volatile var blockWrites: Boolean = false
        @Volatile var blockedWritePath: String? = null
        @Volatile var writeEntered: CountDownLatch? = null
        @Volatile var releaseWrite: CountDownLatch? = null

        override fun readFile(relativePath: String): String? {
            val path = resolve(relativePath)
            return if (Files.isRegularFile(path)) {
                Files.readString(path, StandardCharsets.UTF_8)
            } else {
                null
            }
        }

        override fun writeFile(relativePath: String, content: String) {
            if (blockWrites && (blockedWritePath == null || blockedWritePath == relativePath)) {
                writeEntered?.countDown()
                check(releaseWrite?.await(2, TimeUnit.SECONDS) == true) {
                    "timed out waiting for test writer release"
                }
            }
            val path = resolve(relativePath)
            Files.createDirectories(path.parent)
            Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        }

        override fun exists(relativePath: String): Boolean = Files.exists(resolve(relativePath))

        private fun resolve(relativePath: String): Path {
            val input = Paths.get(relativePath)
            require(!input.isAbsolute) { "absolute paths are not allowed" }
            val path = canonicalRoot.resolve(input).normalize()
            require(path.startsWith(canonicalRoot)) { "path escapes test storage" }
            return path
        }
    }
}
