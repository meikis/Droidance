// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import com.droidance.core.model.llm.models.ImageDetailCapabilitySnapshot
import com.droidance.core.model.llm.models.ImageDetailNegotiation
import com.droidance.core.model.llm.models.ImageDetailOption
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDetailNegotiationTest {
    private val ttl = 86_400_000L
    private val now = 200_000_000L

    @Test
    fun freshCachedDetailStartsWithAcceptedButKeepsFallbackForBackendSwitch() {
        val snapshot = ImageDetailCapabilitySnapshot(
            ImageDetailOption.HIGH,
            setOf(ImageDetailOption.ORIGINAL),
            now - 1_000L
        )
        assertEquals(
            listOf(ImageDetailOption.HIGH, ImageDetailOption.AUTO,
                ImageDetailOption.SERVICE_DEFAULT, ImageDetailOption.LOW),
            ImageDetailNegotiation.candidates(snapshot, now, ttl)
        )
    }

    @Test
    fun noCachedCapabilityUsesAutomaticFallbackOrder() {
        assertEquals(
            listOf(ImageDetailOption.ORIGINAL, ImageDetailOption.HIGH, ImageDetailOption.AUTO,
                ImageDetailOption.SERVICE_DEFAULT, ImageDetailOption.LOW),
            ImageDetailNegotiation.candidates(null, now, ttl)
        )
    }

    @Test
    fun expiredRejectionRechecksOriginal() {
        val snapshot = ImageDetailCapabilitySnapshot(
            ImageDetailOption.HIGH,
            setOf(ImageDetailOption.ORIGINAL),
            now - ttl - 1L
        )
        assertEquals(
            ImageDetailOption.ORIGINAL,
            ImageDetailNegotiation.candidates(snapshot, now, ttl).first()
        )
    }
}
