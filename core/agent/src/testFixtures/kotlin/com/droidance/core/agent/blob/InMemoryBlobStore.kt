// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.blob

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure in-memory BlobStore implementation for testing and isolated headless task execution.
 */
class InMemoryBlobStore : BlobStore {
    private val storage = ConcurrentHashMap<String, ByteArray>()

    override fun put(bytes: ByteArray): BlobMetadata {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.joinToString("") { "%02x".format(it) }
        val ref = "$SHA256_REF_PREFIX$hash"
        storage[ref] = bytes.copyOf()
        return BlobMetadata(
            contentRef = ref,
            contentHash = hash,
            sizeBytes = bytes.size.toLong()
        )
    }

    override fun read(contentRef: String, maxBytes: Long): ByteArray {
        val bytes = storage[contentRef] ?: throw BlobNotFoundException(contentRef)
        if (bytes.size.toLong() > maxBytes) {
            throw BlobReadLimitExceededException(contentRef, bytes.size.toLong(), maxBytes)
        }
        return bytes.copyOf()
    }

    override fun delete(contentRef: String): Boolean = storage.remove(contentRef) != null

    override fun copyTo(contentRef: String, output: java.io.OutputStream): Long {
        val bytes = read(contentRef, Int.MAX_VALUE.toLong())
        output.write(bytes)
        return bytes.size.toLong()
    }

    override fun listAllRefs(): Set<String> = storage.keys.toSet()

    fun clear() {
        storage.clear()
    }

    fun count(): Int = storage.size
}
