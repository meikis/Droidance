// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.blob

import com.droidance.core.agent.blob.*

import com.droidance.agent.blob.*

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBlobStoreTest {
    @Test fun encodedCachePreservesIntegrityLimitsAndDeletionEvenAfterAHit() = withStore { store, root ->
        val bytes = "original evidence".toByteArray()
        val blob = store.put(bytes)
        val encoded = store.readBase64(blob.contentRef, 100)
        org.junit.Assert.assertSame(encoded, store.readBase64(blob.contentRef, 100))
        assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(encoded))
        assertThrows(BlobReadLimitExceededException::class.java) { store.readBase64(blob.contentRef, 1) }
        val stored = root.resolve("sha256").resolve("${blob.contentHash}.blob")
        // Same-size corruption must not be hidden by the derived cache.
        Files.write(stored, ByteArray(bytes.size) { 42 })
        assertThrows(BlobIntegrityException::class.java) { store.readBase64(blob.contentRef, 100) }
        store.delete(blob.contentRef)
        assertThrows(BlobNotFoundException::class.java) { store.readBase64(blob.contentRef, 100) }
        store.put(bytes)
        assertEquals(encoded, store.readBase64(blob.contentRef, 100))
    }

    @Test fun encodedCacheEvictsByMemoryBudgetAndSkipsOversizeEntries() = withStore { _, root ->
        val store = FileBlobStore(root.toFile(), encodedCacheMaxBytes = 16)
        val a = store.put("first!".toByteArray())
        val b = store.put("second".toByteArray())
        val first = store.readBase64(a.contentRef, 100)
        org.junit.Assert.assertSame(first, store.readBase64(a.contentRef, 100))
        store.readBase64(b.contentRef, 100)
        org.junit.Assert.assertNotSame(first, store.readBase64(a.contentRef, 100))
        val big = store.put("too large for cache".toByteArray())
        org.junit.Assert.assertNotSame(store.readBase64(big.contentRef, 100), store.readBase64(big.contentRef, 100))
    }

    @Test fun referenceListingKeepsOnlyRegularBlobFilesWithoutFollowingLinks() = withStore { store, root ->
        assertTrue(store.listAllRefs().isEmpty())
        val first = store.put("first".toByteArray())
        val second = store.put("second".toByteArray())
        val directory = root.resolve("sha256")
        Files.write(directory.resolve("ignored.txt"), byteArrayOf(1))
        Files.write(directory.resolve(".pending-crashed-write.blob"), byteArrayOf(1))
        Files.write(directory.resolve("invalid.blob"), byteArrayOf(1))
        Files.write(directory.resolve("${"A".repeat(64)}.blob"), byteArrayOf(1))
        Files.createDirectory(directory.resolve("not-a-file.blob"))
        Files.createSymbolicLink(directory.resolve("link.blob"), directory.resolve("${first.contentHash}.blob"))
        assertEquals(setOf(first.contentRef, second.contentRef), store.listAllRefs())
        assertEquals(setOf(first.contentRef, second.contentRef), FileBlobStore(root.toFile()).listAllRefs())
    }

    @Test fun streamingExportVerifiesContentAndPreservesExactBytes() = withStore { store, root ->
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val blob = store.put(bytes)
        val output = java.io.ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), store.copyTo(blob.contentRef, output))
        assertArrayEquals(bytes, output.toByteArray())
        val stored = root.resolve("sha256").resolve("${blob.contentHash}.blob")
        Files.write(stored, "corrupt".toByteArray())
        assertThrows(BlobIntegrityException::class.java) {
            store.copyTo(blob.contentRef, java.io.ByteArrayOutputStream())
        }
    }

    @Test
    fun rawBytesProduceStableSha256MetadataAndRoundTripExactly() = withStore { store, _ ->
        val bytes = byteArrayOf(0, 1, 2, 127, -1, -128)

        val metadata = store.put(bytes)

        assertEquals("sha256:${metadata.contentHash}", metadata.contentRef)
        assertEquals(64, metadata.contentHash.length)
        assertEquals(bytes.size.toLong(), metadata.sizeBytes)
        assertArrayEquals(bytes, store.read(metadata.contentRef, bytes.size.toLong()))
    }

    @Test
    fun knownContentHasStableSha256Reference() = withStore { store, _ ->
        val metadata = store.put("hello".toByteArray())

        assertEquals(
            "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            metadata.contentRef
        )
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            metadata.contentHash
        )
    }

    @Test
    fun repeatedContentIsDeduplicated() = withStore { store, root ->
        val first = store.put("same bytes".toByteArray())
        val second = store.put("same bytes".toByteArray())

        assertEquals(first, second)
        assertEquals(1L, regularFileCount(root))
    }

    @Test
    fun emptyDifferentAndLargeContentsRemainDistinctAndExact() = withStore { store, root ->
        val empty = ByteArray(0)
        val first = "first".toByteArray()
        val second = "second".toByteArray()
        val large = ByteArray(2 * 1024 * 1024) { index -> (index % 253).toByte() }

        val emptyMetadata = store.put(empty)
        val firstMetadata = store.put(first)
        val secondMetadata = store.put(second)
        val largeMetadata = store.put(large)

        assertEquals(
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptyMetadata.contentRef
        )
        assertEquals(4, setOf(emptyMetadata, firstMetadata, secondMetadata, largeMetadata).size)
        assertEquals(4L, regularFileCount(root))
        assertArrayEquals(empty, store.read(emptyMetadata.contentRef, 0))
        assertArrayEquals(first, store.read(firstMetadata.contentRef, first.size.toLong()))
        assertArrayEquals(second, store.read(secondMetadata.contentRef, second.size.toLong()))
        assertArrayEquals(large, store.read(largeMetadata.contentRef, large.size.toLong()))
    }

    @Test
    fun contentCanBeReadAfterStoreIsReopened() = withStore { store, root ->
        val bytes = "persisted bytes".toByteArray()
        val metadata = store.put(bytes)

        val reopened = FileBlobStore(root.toFile())

        assertArrayEquals(bytes, reopened.read(metadata.contentRef, bytes.size.toLong()))
    }

    @Test
    fun concurrentWritersAreIdempotentAndLeaveNoPendingFiles() = withStore { store, root ->
        val workers = 12
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until workers).map {
                executor.submit<BlobMetadata> {
                    start.await()
                    store.put(ByteArray(128 * 1024) { index -> (index % 251).toByte() })
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.distinct().size)
            assertEquals(1L, regularFileCount(root))
            Files.walk(root).use { paths ->
                assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".pending-") })
            }
            assertArrayEquals(
                ByteArray(128 * 1024) { index -> (index % 251).toByte() },
                store.read(results.first().contentRef, 128L * 1024L)
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun differentStoreInstancesConcurrentlyDeduplicateTheSameContent() = withStore { _, root ->
        val workers = 12
        val stores = List(workers) { FileBlobStore(root.toFile()) }
        val bytes = ByteArray(256 * 1024) { index -> (index % 239).toByte() }
        val executor = Executors.newFixedThreadPool(workers)
        val start = CountDownLatch(1)
        try {
            val futures = stores.map { store ->
                executor.submit<BlobMetadata> {
                    start.await()
                    store.put(bytes)
                }
            }
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.distinct().size)
            assertEquals(1L, regularFileCount(root))
            assertArrayEquals(
                bytes,
                FileBlobStore(root.toFile()).read(results.first().contentRef, bytes.size.toLong())
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun readRejectsTraversalAndMalformedReferences() = withStore { store, _ ->
        listOf(
            "../../outside",
            "sha256:../../outside",
            "sha256:${"A".repeat(64)}",
            "sha256:${"a".repeat(63)}",
            "sha512:${"a".repeat(64)}"
        ).forEach { maliciousRef ->
            assertThrows(IllegalArgumentException::class.java) {
                store.read(maliciousRef, 1024)
            }
        }
    }

    @Test
    fun readVerifiesStoredBytesAgainstReference() = withStore { store, root ->
        val metadata = store.put("original".toByteArray())
        val storedFile = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.findFirst().orElseThrow()
        }
        Files.write(
            storedFile,
            "tampered".toByteArray(),
            StandardOpenOption.TRUNCATE_EXISTING
        )

        assertThrows(BlobIntegrityException::class.java) {
            store.read(metadata.contentRef, 1024)
        }
    }

    @Test
    fun readRejectsOversizedBlobBeforeReturningContent() = withStore { store, _ ->
        val bytes = ByteArray(4096) { 7 }
        val metadata = store.put(bytes)

        val error = assertThrows(BlobReadLimitExceededException::class.java) {
            store.read(metadata.contentRef, 4095)
        }

        assertEquals(bytes.size.toLong(), error.actualSizeBytes)
        assertEquals(4095L, error.maxBytes)
    }

    @Test
    fun putFailsClosedWhenExistingTargetIsCorrupt() = withStore { store, root ->
        val original = "original".toByteArray()
        val metadata = store.put(original)
        val storedFile = onlyStoredFile(root)
        val corrupt = "tampered".toByteArray()
        Files.write(storedFile, corrupt, StandardOpenOption.TRUNCATE_EXISTING)

        assertThrows(BlobIntegrityException::class.java) {
            store.put(original)
        }
        assertArrayEquals(corrupt, Files.readAllBytes(storedFile))
        assertEquals(metadata.contentRef, "sha256:${metadata.contentHash}")
    }

    @Test
    fun readRejectsTruncatedStoredContent() = withStore { store, root ->
        val original = "content that will be truncated".toByteArray()
        val metadata = store.put(original)
        val storedFile = onlyStoredFile(root)
        Files.write(
            storedFile,
            original.copyOf(original.size / 2),
            StandardOpenOption.TRUNCATE_EXISTING
        )

        assertThrows(BlobIntegrityException::class.java) {
            store.read(metadata.contentRef, original.size.toLong())
        }
    }

    @Test
    fun storedFileContainsOriginalBytesRatherThanBase64OrJson() = withStore { store, root ->
        val raw = byteArrayOf(0, -1, 10, 13, 34, 92, -128, 127)

        store.put(raw)

        assertArrayEquals(raw, Files.readAllBytes(onlyStoredFile(root)))
    }

    @Test
    fun missingValidReferenceHasTypedFailure() = withStore { store, _ ->
        assertThrows(BlobNotFoundException::class.java) {
            store.read("sha256:${"0".repeat(64)}", 1024)
        }
    }

    private fun withStore(block: (FileBlobStore, java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("droidance-blobs")
        try {
            block(FileBlobStore(root.toFile()), root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun regularFileCount(root: java.nio.file.Path): Long = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.count()
    }

    private fun onlyStoredFile(root: java.nio.file.Path): java.nio.file.Path =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.findFirst().orElseThrow()
        }
}
