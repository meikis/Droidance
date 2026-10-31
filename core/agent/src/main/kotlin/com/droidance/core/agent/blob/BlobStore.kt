// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.blob

import java.io.IOException

data class BlobMetadata(
    val contentRef: String,
    val contentHash: String,
    val sizeBytes: Long
) {
    init {
        require(SHA256_HASH_REGEX.matches(contentHash)) {
            "Blob contentHash must be a lowercase SHA-256 hash"
        }
        require(contentRef == "$SHA256_REF_PREFIX$contentHash") {
            "Blob contentRef must match contentHash"
        }
        require(sizeBytes >= 0L) { "Blob sizeBytes cannot be negative" }
    }
}

interface BlobStore {
    @Throws(IOException::class)
    fun put(bytes: ByteArray): BlobMetadata

    @Throws(IOException::class)
    fun read(contentRef: String, maxBytes: Long): ByteArray

    /** Returns the exact Base64 representation of verified content. */
    @Throws(IOException::class)
    fun readBase64(contentRef: String, maxBytes: Long): String =
        java.util.Base64.getEncoder().encodeToString(read(contentRef, maxBytes))

    /** Streams and verifies a retained artifact without a whole-file memory allocation. */
    @Throws(IOException::class)
    fun copyTo(contentRef: String, output: java.io.OutputStream): Long

    @Throws(IOException::class)
    fun delete(contentRef: String): Boolean

    @Throws(IOException::class)
    fun listAllRefs(): Set<String>
}

open class BlobStoreException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class BlobNotFoundException(contentRef: String) :
    BlobStoreException("Blob not found: $contentRef")

class BlobIntegrityException(
    contentRef: String,
    detail: String
) : BlobStoreException("Blob integrity check failed for $contentRef: $detail")

class BlobReadLimitExceededException(
    contentRef: String,
    val actualSizeBytes: Long,
    val maxBytes: Long
) : BlobStoreException(
    "Blob $contentRef has $actualSizeBytes bytes, exceeding the $maxBytes-byte read limit"
)

const val SHA256_REF_PREFIX = "sha256:"
val SHA256_HASH_REGEX = Regex("^[0-9a-f]{64}$")

fun hashFromContentRef(contentRef: String): String {
    require(contentRef.startsWith(SHA256_REF_PREFIX)) {
        "Blob contentRef must use the sha256 scheme"
    }
    val hash = contentRef.removePrefix(SHA256_REF_PREFIX)
    require(SHA256_HASH_REGEX.matches(hash)) {
        "Blob contentRef must contain exactly one lowercase SHA-256 hash"
    }
    return hash
}
