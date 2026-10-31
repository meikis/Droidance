// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TurnRequestTest {
    @Test
    fun consecutiveRequestsKeepIndependentImmutableIngressData() {
        val mutableImages = mutableListOf("image-a")
        val first = TurnRequest.create(
            source = MessageSource.DIRECT,
            rawText = "first task",
            images = mutableImages
        )
        mutableImages[0] = "mutated"
        val second = TurnRequest.create(
            source = MessageSource.SCHEDULED,
            rawText = "second task",
            images = listOf("image-b")
        )

        assertNotEquals(first.requestId, second.requestId)
        assertEquals(MessageSource.DIRECT, first.source)
        assertEquals("first task", first.rawText)
        assertEquals(listOf("image-a"), first.images)
        assertEquals(MessageSource.SCHEDULED, second.source)
        assertEquals("second task", second.rawText)
        assertEquals(listOf("image-b"), second.images)
    }

    @Test
    fun rawHashIsStableForSameTextAndDifferentForDifferentText() {
        val first = TurnRequest.create(MessageSource.DIRECT, "same", emptyList())
        val repeated = TurnRequest.create(MessageSource.SCHEDULED, "same", emptyList())
        val different = TurnRequest.create(MessageSource.DIRECT, "different", emptyList())

        assertEquals(first.rawHash, repeated.rawHash)
        assertNotEquals(first.rawHash, different.rawHash)
    }
}
