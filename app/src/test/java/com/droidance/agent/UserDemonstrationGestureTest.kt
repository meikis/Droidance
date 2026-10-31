// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDemonstrationGestureTest {
    @Test
    fun classifiesTapLongPressAndSwipeWithoutTreatingCoordinatesAsPortableFacts() {
        val tap = classify(100, 200, 104, 204, 90)
        val longPress = classify(100, 200, 102, 201, 700)
        val swipe = classify(100, 800, 100, 200, 420)

        assertEquals(UserDemonstrationActionType.TAP, tap.actionType)
        assertEquals(UserDemonstrationActionType.LONG_PRESS, longPress.actionType)
        assertEquals(UserDemonstrationActionType.SWIPE, swipe.actionType)
        assertEquals("up", UserDemonstrationGestureClassifier.direction(swipe))
        assertTrue(swipe.argumentsJson().contains("recorded_hint_only"))
        assertTrue(swipe.argumentsJson().contains("normalized_end_y"))
    }

    private fun classify(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Long
    ): UserDemonstrationGesture = UserDemonstrationGestureClassifier.classify(
        startX = startX,
        startY = startY,
        endX = endX,
        endY = endY,
        durationMillis = duration,
        displayWidth = 1080,
        displayHeight = 2376,
        touchSlopPx = 20
    )
}
