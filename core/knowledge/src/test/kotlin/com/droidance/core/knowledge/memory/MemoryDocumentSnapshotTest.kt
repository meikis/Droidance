// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the context path is backed by the last committed document, rather than by a
 * write currently holding the document lock. The storage below is a real temporary-file store;
 * its gates only make the real write boundary observable to the test.
 */
class MemoryDocumentSnapshotTest {
    @Test
    fun failedProfileWriteKeepsThePreviouslyPublishedDocument() {
        withTempStorage { storage ->
            val profile = ProfileStore(storage)
            profile.initializeIfMissing("# User Profile\n\n- committed baseline\n")
            val before = profile.contextSnapshot()
            storage.replaceWithDirectoryOnWrite = ProfileStore.PATH

            val thrown = runCatching {
                profile.applyGroundedUpdates(listOf(profileUpdate("new_value")))
            }.exceptionOrNull()

            assertTrue(thrown is java.nio.file.FileSystemException)
            val after = profile.contextSnapshot()
            assertEquals(before, after)
            assertTrue(after.document.orEmpty().contains("committed baseline"))
            assertFalse(after.document.orEmpty().contains("new_value"))
            assertEquals(null, after.diagnostic)
        }
    }

    @Test
    fun failedLongTermWriteKeepsThePreviouslyPublishedDocument() {
        withTempStorage { storage ->
            val longTerm = LongTermMemoryStore(storage)
            longTerm.initializeIfMissing(LongTermMemoryStore.DEFAULT_AGENT)
            longTerm.applyGroundedUpdates(
                listOf(
                    profileUpdate("committed baseline").copy(scope = DurableMemoryScope.LONG_TERM)
                )
            )
            val before = longTerm.contextSnapshot()
            storage.replaceWithDirectoryOnWrite = "agents/${LongTermMemoryStore.DEFAULT_AGENT}/MEMORY.md"

            val thrown = runCatching {
                longTerm.applyGroundedUpdates(
                    listOf(
                        profileUpdate("new_value").copy(
                            scope = DurableMemoryScope.LONG_TERM,
                            expectedRevision = DurableMemoryDocument.revision(
                                requireNotNull(before.document), "test_value"
                            )
                        )
                    )
                )
            }.exceptionOrNull()

            assertTrue(thrown is java.nio.file.FileSystemException)
            val after = longTerm.contextSnapshot()
            assertEquals(before, after)
            assertTrue(after.document.orEmpty().contains("committed baseline"))
            assertFalse(after.document.orEmpty().contains("new_value"))
            assertEquals(null, after.diagnostic)
        }
    }

    @Test
    fun profileContextSnapshotReturnsTheCommittedValueWhileAWriteIsBlocked() = runBlocking {
        withTempStorage { storage ->
            val profile = ProfileStore(storage)
            profile.initializeIfMissing("# User Profile\n\n- committed baseline\n")
            storage.blockWrites = true
            val writerEntered = CountDownLatch(1)
            val releaseWriter = CountDownLatch(1)
            storage.writeEntered = writerEntered
            storage.releaseWrite = releaseWriter

            val writer = launch(Dispatchers.Default) {
                profile.applyGroundedUpdates(listOf(profileUpdate("new_value")))
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(writerEntered.await(1, TimeUnit.SECONDS))
                val startedAt = System.nanoTime()
                val snapshot = executor.submit<MemoryDocumentSnapshot> { profile.contextSnapshot() }
                    .get(500L, TimeUnit.MILLISECONDS)
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000L
                assertTrue("context snapshot waited ${elapsed}ms for the writer", elapsed < 500L)
                assertTrue(snapshot.document.orEmpty().contains("committed baseline"))
                assertFalse(snapshot.document.orEmpty().contains("new_value"))
                assertEquals(null, snapshot.diagnostic)
            } finally {
                releaseWriter.countDown()
                runBlocking { writer.join() }
                executor.shutdownNow()
            }

            assertTrue(profile.contextSnapshot().document.orEmpty().contains("new_value"))
        }
    }

    @Test
    fun durableContextSnapshotUsesBothCommittedDocuments() = withTempStorage { storage ->
        val profile = ProfileStore(storage)
        val longTerm = LongTermMemoryStore(storage)
        profile.initializeIfMissing("# User Profile\n\n- [memory:key=address][category=communication][updated=old] 称呼我为 Alex\n")
        longTerm.initializeIfMissing(LongTermMemoryStore.DEFAULT_AGENT)
        longTerm.applyGroundedUpdates(
            listOf(
                profileUpdate("先运行 JVM 测试").copy(
                    scope = DurableMemoryScope.LONG_TERM,
                    key = "deployment",
                    category = "project"
                )
            )
        )

        val snapshot = DurableMemoryStore(profile, longTerm).contextSnapshot()

        assertTrue(snapshot.diagnostics.isEmpty())
        assertTrue(snapshot.entries.any { it.ref == "profile:address" })
        assertTrue(snapshot.entries.any { it.ref == "long_term:deployment" })
    }

    private fun profileUpdate(content: String): DurableMemoryUpdate = DurableMemoryUpdate(
        scope = DurableMemoryScope.USER_PROFILE,
        operation = DurableMemoryOperation.UPSERT,
        key = "test_value",
        category = "test",
        content = content,
        sourceQuote = content,
        expectedRevision = "absent"
    )

    private fun <T> withTempStorage(block: (GateFileStorage) -> T): T {
        val root = Files.createTempDirectory("droidance-memory-snapshot-")
        return try {
            block(GateFileStorage(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class GateFileStorage(private val root: Path) : MemoryDocumentStorage {
        @Volatile var blockWrites: Boolean = false
        @Volatile var writeEntered: CountDownLatch? = null
        @Volatile var releaseWrite: CountDownLatch? = null
        @Volatile var replaceWithDirectoryOnWrite: String? = null

        override fun readFile(relativePath: String): String? {
            val path = resolve(relativePath)
            return if (Files.exists(path)) Files.readString(path, StandardCharsets.UTF_8) else null
        }

        override fun writeFile(relativePath: String, content: String) {
            if (blockWrites) {
                writeEntered?.countDown()
                check(releaseWrite?.await(2, TimeUnit.SECONDS) == true) { "timed out waiting for test writer release" }
            }
            val path = resolve(relativePath)
            Files.createDirectories(path.parent)
            if (replaceWithDirectoryOnWrite == relativePath) {
                replaceWithDirectoryOnWrite = null
                Files.deleteIfExists(path)
                Files.createDirectories(path)
            }
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
            val path = root.toAbsolutePath().normalize().resolve(input).normalize()
            require(path.startsWith(root.toAbsolutePath().normalize())) { "path escapes test storage" }
            return path
        }
    }
}
