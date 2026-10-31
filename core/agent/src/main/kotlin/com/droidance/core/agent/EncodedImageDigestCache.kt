// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

/** Turn-local derived metadata, bounded by retained string memory. Never stores observation identity. */
internal class EncodedImageDigestCache(private val maxBytes: Long = 24L * 1024 * 1024) {
    private val entries = LinkedHashMap<String, String>(16, 0.75f, true)
    private var retainedBytes = 0L

    init { require(maxBytes >= 0) }

    fun sha256(encoded: String): String = entries[encoded] ?: run {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(java.util.Base64.getMimeDecoder().decode(encoded))
            .joinToString("") { "%02x".format(it) }
        val weight = encoded.length.toLong() * 2 + digest.length * 2
        if (weight <= maxBytes) {
            while (retainedBytes + weight > maxBytes && entries.isNotEmpty()) {
                val oldest = entries.entries.iterator()
                val entry = oldest.next()
                retainedBytes -= entry.key.length.toLong() * 2 + entry.value.length * 2
                oldest.remove()
            }
            entries[encoded] = digest
            retainedBytes += weight
        }
        digest
    }
}
