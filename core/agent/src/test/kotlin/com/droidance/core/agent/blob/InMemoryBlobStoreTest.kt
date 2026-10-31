// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.blob

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class InMemoryBlobStoreTest {
    private lateinit var blobStore: InMemoryBlobStore

    @Before
    fun setUp() {
        blobStore = InMemoryBlobStore()
    }

    @Test
    fun putAndReadSucceeds() {
        val bytes = "hello world".toByteArray()
        val metadata = blobStore.put(bytes)

        assertEquals(bytes.size.toLong(), metadata.sizeBytes)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", metadata.contentHash)
        assertEquals("sha256:b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", metadata.contentRef)

        val retrieved = blobStore.read(metadata.contentRef, 1024)
        assertArrayEquals(bytes, retrieved)
    }

    @Test
    fun readLimitExceededThrows() {
        val bytes = "hello world".toByteArray()
        val metadata = blobStore.put(bytes)

        assertThrows(BlobReadLimitExceededException::class.java) {
            blobStore.read(metadata.contentRef, 5)
        }
    }

    @Test
    fun readMissingThrowsNotFound() {
        assertThrows(BlobNotFoundException::class.java) {
            blobStore.read("sha256:0000000000000000000000000000000000000000000000000000000000000000", 1024)
        }
    }
}
