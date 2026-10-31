// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.*
import org.junit.Test

class CursorMotionTest {
    @Test fun delayedSwipeCatchesUpInsteadOfRestarting() {
        val f = CursorMotion.sample(1300, 1000, 0, 600, true)
        assertEquals(0.5f, f.progress, 0.001f)
    }
    @Test fun completedSwipeRestoresTheEndpointAfterSuppression() {
        val f = CursorMotion.sample(1440, 1000, 1400, 600, true)
        assertEquals(1f, f.progress, 0f)
        assertEquals(1f, f.alpha, 0f)
    }
    @Test fun reopeningPreviewRestoresAnIdleCursorWithoutReplayingTheGesture() {
        assertEquals(1f, CursorMotion.sample(5000, 1000, 1600, 600, true).alpha, 0f)
        assertEquals(1f, CursorMotion.sample(5000, 1000, 0, 600, true).alpha, 0f)
    }
    @Test fun gestureProgressIsLinearAndIndependentOfCosmeticPress() {
        for (offset in 0..600 step 60) {
            val f = CursorMotion.sample(1000L + offset, 1000, 0, 600, true)
            assertEquals(offset / 600f, f.progress, 0.001f)
        }
    }
    @Test fun earlyCompletionSettlesWithoutASecondPress() {
        val f = CursorMotion.sample(1160, 1000, 1070, 0, false)
        assertEquals(1f, f.press, 0.001f)
        assertEquals(0f, f.feedback, 0f)
    }
    @Test fun aNewActionUsesOnlyItsOwnClock() {
        val old = CursorMotion.sample(1810, 1000, 1600, 600, true)
        val fresh = CursorMotion.sample(1810, 1800, 0, 0, false)
        assertFalse(old.animating)
        assertTrue(fresh.animating)
        assertTrue(fresh.feedback > 0.9f)
    }
    @Test fun zeroDurationAndFutureTimestampsStayFinite() {
        val f = CursorMotion.sample(999, 1000, 0, 0, true)
        assertEquals(0f, f.progress, 0f)
        assertEquals(1f, f.alpha, 0f)
    }

    @Test fun travelDurationIsCappedAndFast() {
        assertEquals(0L, CursorMotion.calculateTravelDuration(0f))
        assertEquals(0L, CursorMotion.calculateTravelDuration(15f))
        val mid = CursorMotion.calculateTravelDuration(400f)
        assertTrue("Travel duration should be snappy (70-130ms)", mid in 70L..130L)
        val full = CursorMotion.calculateTravelDuration(2500f)
        assertEquals(180L, full)
    }

    @Test fun travelPhaseSmoothlyMovesFromSourceToTargetBeforeAction() {
        val start = 1000L
        val travelDuration = 100L
        // At start of travel: exactly at fromX, fromY
        val t0 = CursorMotion.sampleMotion(1000L, start, travelDuration, 0L,
            100f, 200f, 500f, 800f, 500f, 800f, 160, false, 2.5f)
        assertTrue(t0.isTraveling)
        assertEquals(100f, t0.x, 0.01f)
        assertEquals(200f, t0.y, 0.01f)
        assertEquals(1f, t0.press, 0f)
        assertEquals(0f, t0.feedback, 0f)

        // Midway through travel: moving along curve, hovering with dynamic tilt
        val tMid = CursorMotion.sampleMotion(1050L, start, travelDuration, 0L,
            100f, 200f, 500f, 800f, 500f, 800f, 160, false, 2.5f)
        assertTrue(tMid.isTraveling)
        assertTrue(tMid.x > 100f && tMid.x < 500f)
        assertTrue(tMid.y > 200f && tMid.y < 800f)
        assertTrue(tMid.animating)
        assertNotEquals(0f, tMid.tilt)

        // Upon arrival at target (t = 1100): reaches targetX, targetY, transition to gesture
        val tArrive = CursorMotion.sampleMotion(1100L, start, travelDuration, 0L,
            100f, 200f, 500f, 800f, 500f, 800f, 160, false, 2.5f)
        assertFalse(tArrive.isTraveling)
        assertEquals(500f, tArrive.x, 0.01f)
        assertEquals(800f, tArrive.y, 0.01f)

        // Mid-tap: pressing down at target with feedback ripple
        val tPress = CursorMotion.sampleMotion(1180L, start, travelDuration, 0L,
            100f, 200f, 500f, 800f, 500f, 800f, 160, false, 2.5f)
        assertEquals(500f, tPress.x, 0.01f)
        assertEquals(800f, tPress.y, 0.01f)
        assertTrue(tPress.press < 0.95f)
        assertTrue(tPress.feedback > 0f)

        // Settled after tap completes: resting at target
        val tSettled = CursorMotion.sampleMotion(2000L, start, travelDuration, 1260L,
            100f, 200f, 500f, 800f, 500f, 800f, 160, false, 2.5f)
        assertFalse(tSettled.animating)
        assertEquals(500f, tSettled.x, 0.01f)
        assertEquals(800f, tSettled.y, 0.01f)
        assertEquals(1f, tSettled.press, 0f)
    }

    @Test fun swipePhaseTravelsToStartPointThenDragsToEndPoint() {
        val start = 1000L
        val travelDuration = 80L
        val gestureDuration = 300
        // During travel to swipe start:
        val tTravel = CursorMotion.sampleMotion(1040L, start, travelDuration, 0L,
            50f, 50f, 200f, 400f, 200f, 1000f, gestureDuration, true, 2.5f)
        assertTrue(tTravel.isTraveling)
        assertFalse(tTravel.isSwiping)

        // Arrived at swipe start, dragging towards end:
        val tSwipe = CursorMotion.sampleMotion(1230L, start, travelDuration, 0L,
            50f, 50f, 200f, 400f, 200f, 1000f, gestureDuration, true, 2.5f)
        assertFalse(tSwipe.isTraveling)
        assertTrue(tSwipe.isSwiping)
        assertEquals(200f, tSwipe.x, 0.01f)
        assertTrue(tSwipe.y > 400f && tSwipe.y < 1000f)

        // Settled at swipe end:
        val tEnd = CursorMotion.sampleMotion(1500L, start, travelDuration, 1380L,
            50f, 50f, 200f, 400f, 200f, 1000f, gestureDuration, true, 2.5f)
        assertFalse(tEnd.animating)
        assertEquals(200f, tEnd.x, 0.01f)
        assertEquals(1000f, tEnd.y, 0.01f)
    }
}
