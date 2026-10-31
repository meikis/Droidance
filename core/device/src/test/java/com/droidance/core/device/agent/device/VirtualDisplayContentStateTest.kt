// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualDisplayContentStateTest {
    @Test
    fun `only a successful zero activity inventory on the requested valid display is empty`() {
        assertEquals(VirtualDisplayContentState.EMPTY, state(activityCount = 0))
        assertEquals(VirtualDisplayContentState.CONTENT_PRESENT, state(activityCount = 1))
        assertEquals(VirtualDisplayContentState.CONTENT_PRESENT, state(activityCount = 12))
    }

    @Test
    fun `missing failed or stale platform facts never establish emptiness`() {
        listOf(
            state(querySucceeded = false),
            state(displayValid = false),
            state(displayId = 59),
            state(activityCount = null),
            state(activityCount = -1),
            VirtualDisplayContentState.fromFacts(0, 0, true, true, 0)
        ).forEach { assertEquals(VirtualDisplayContentState.UNAVAILABLE, it) }
    }

    private fun state(
        displayId: Int = 7,
        displayValid: Boolean = true,
        querySucceeded: Boolean = true,
        activityCount: Int? = 0
    ) = VirtualDisplayContentState.fromFacts(7, displayId, displayValid, querySucceeded, activityCount)
}
