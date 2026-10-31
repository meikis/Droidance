// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import org.junit.Assert.*
import org.junit.Test

class EncodedImageDigestCacheTest {
    @Test fun exactPixelDigestIsPreservedAcrossHitsAndEviction() {
        val cache = EncodedImageDigestCache(150)
        val encoded = java.util.Base64.getEncoder().encodeToString("hello".toByteArray())
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val first = cache.sha256(encoded)
        assertEquals(expected, first)
        assertSame(first, cache.sha256(encoded))
        cache.sha256("YQ==")
        val reread = cache.sha256(encoded)
        assertEquals(expected, reread)
        assertNotSame(first, reread)
        assertThrows(IllegalArgumentException::class.java) { cache.sha256("a") }
    }
}
