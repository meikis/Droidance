// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import org.junit.Assert.*
import org.junit.Test

class MascotMotionTest {
    @Test fun runtimeDanceKeepsMovingThroughLongExecutionWaitsAndLoopsSmoothly() {
        for (start in 0L..16_000L step 200L) {
            val poses = (start..start + 400L step 40L).map { MascotMotion.loopingDancePose(it) }
            assertTrue("Keep dancing without activity refresh at $start", poses.distinct().size > 1)
            assertTrue("Keep body movement through the old idle hold at $start", poses.map { it.sway }.distinct().size > 1)
        }
        for (time in listOf(0L, 1_100L, 6_800L, 7_999L)) {
            assertEquals(MascotMotion.loopingDancePose(time), MascotMotion.loopingDancePose(time + MascotMotion.CYCLE_MS))
            assertEquals(MascotPose(), MascotMotion.loopingDancePose(time, reducedMotion = true))
        }
        val before = MascotMotion.loopingDancePose(7_999L)
        val after = MascotMotion.loopingDancePose(8_000L)
        assertEquals(before.sway, after.sway, .03f)
        assertEquals(before.headTilt, after.headTilt, .03f)
        assertEquals(before.lift, after.lift, .03f)
    }
    @Test fun reducedMotionHasNoDecorativeMovementInAnyState() {
        for (mood in MascotMood.entries) {
            val still = MascotMotion.pose(2800, mood, true)
            assertEquals(still, MascotMotion.pose(3500, mood, true))
            assertEquals(MascotPose(), still)
        }
    }
    @Test fun theEyesLeadTheHeadWhenThinking() {
        val pose = MascotMotion.pose(1200, MascotMood.THINKING)
        assertTrue(pose.gazeX < 0f)
        assertEquals(0f, pose.headTilt, 0f)
        val rightGlance = MascotMotion.pose(4550, MascotMood.THINKING)
        assertTrue(rightGlance.gazeX > 0f)
        assertEquals(0f, rightGlance.headTilt, 0f)
        for (time in 0L..MascotMotion.CYCLE_MS step 20) {
            val thinking = MascotMotion.pose(time, MascotMood.THINKING)
            assertEquals("Keep the face forward rather than looking down", 0f, thinking.gazeY, 0f)
            assertEquals("The curious motion should not bow", 0f, thinking.nod, 0f)
            assertEquals(1f, thinking.smile, 0f)
        }
    }
    @Test fun operatingUsesUprightSideShiftsAndOneReboundBeforeRest() {
        val poses = (0L until MascotMotion.CYCLE_MS step 20).map {
            MascotMotion.pose(it, MascotMood.OPERATING)
        }
        val peaks = poses.windowed(3).count { (before, pose, after) ->
            pose.lift > before.lift && pose.lift > after.lift
        }
        assertEquals("One rebound per four-second phrase", 2, peaks)
        assertTrue("Weight should shift to both sides", poses.any { it.sway < -1f } && poses.any { it.sway > 1f })
        assertTrue("Leave quiet holds between work phrases", poses.count {
            it.sway == 0f && it.lift == 0f && it.bodyTilt == 0f && it.headTilt == 0f
        } >= 100)
        assertTrue("Keep an upright friendly expression", poses.all {
            it.nod == 0f && it.gazeY == 0f && it.smile == 1f
        })
        assertTrue("Keep the rebound inside the existing character frame", poses.all {
            kotlin.math.abs(it.sway) <= 1.8f && it.lift <= 2.6f && kotlin.math.abs(it.bodyTilt) <= 3f
        })
    }
    @Test fun workMoodsStayContinuousAcrossEveryFrameAndLoopBoundary() {
        fun channels(time: Long, mood: MascotMood) = MascotMotion.pose(time, mood).let {
            listOf(it.headTilt, it.gazeX, it.gazeY, it.breath, it.nod, it.smile,
                it.sway, it.lift, it.bodyTilt, it.squash)
        }
        for (mood in listOf(MascotMood.THINKING, MascotMood.OPERATING)) {
            for (time in 1L..MascotMotion.CYCLE_MS) {
                val before = channels(time - 1, mood)
                val center = channels(time, mood)
                val after = channels(time + 1, mood)
                for (index in center.indices) {
                    val entering = center[index] - before[index]
                    val leaving = after[index] - center[index]
                    assertTrue("No position jump in $mood at $time, channel $index",
                        kotlin.math.abs(entering) < .03f && kotlin.math.abs(leaving) < .03f)
                    assertEquals("No velocity jump in $mood at $time, channel $index",
                        entering, leaving, .001f)
                }
            }
            for (time in listOf(0L, 1200L, 2300L, 4000L, 6200L, 7999L)) {
                assertEquals(MascotMotion.pose(time, mood),
                    MascotMotion.pose(time + MascotMotion.CYCLE_MS, mood))
            }
        }
    }
    @Test fun greetingDoesNotRepeatAtTheIdleCycleBoundary() {
        assertEquals(0f, MascotMotion.pose(8900, MascotMood.WELCOME).nod, 0f)
        assertTrue(MascotMotion.pose(900, MascotMood.WELCOME).nod > 0.9f)
    }
    @Test fun greetingStartsGentlyAndSettlesContinuouslyIntoIdle() {
        val initialTilt = MascotMotion.pose(0, MascotMood.WELCOME).headTilt
        val firstTilt = MascotMotion.pose(1, MascotMood.WELCOME).headTilt
        assertEquals(initialTilt, firstTilt, .00001f)
        // The old greeting jumped about 0.61 degrees at this handoff.
        val beforeIdle = MascotMotion.pose(2199, MascotMood.WELCOME).headTilt
        val idle = MascotMotion.pose(2200, MascotMood.WELCOME).headTilt
        assertEquals(beforeIdle, idle, .00001f)
        assertEquals(idle, MascotMotion.pose(2201, MascotMood.WELCOME).headTilt, .00001f)
        // Keep the recognizable greeting swing and original smile.
        assertTrue(MascotMotion.pose(550, MascotMood.WELCOME).headTilt > 3f)
        assertTrue(MascotMotion.pose(1650, MascotMood.WELCOME).headTilt < -3f)
        assertEquals(1.35f, MascotMotion.pose(900, MascotMood.WELCOME).smile, .001f)
    }
    @Test fun interruptedMoodTransitionContinuesFromTheVisiblePose() {
        val listening = MascotTransition(MascotMood.DANCING)
            .retarget(2000, MascotMood.LISTENING)
        val visible = listening.pose(2060)
        assertTrue(visible.sway > 2f)
        val operating = listening.retarget(2060, MascotMood.OPERATING)
        assertEquals(visible, operating.pose(2060))
        val nextFrame = operating.pose(2061)
        assertEquals(visible.sway, nextFrame.sway, .001f)
        assertEquals(visible.headTilt, nextFrame.headTilt, .001f)
        val settled = operating.pose(2280)
        val target = MascotMotion.pose(2280, MascotMood.OPERATING)
        assertEquals(target.headTilt, settled.headTilt, .0001f)
        assertEquals(target.nod, settled.nod, .0001f)
        assertEquals(target.sway, settled.sway, .0001f)
    }
    @Test fun retainingTheSameMoodDoesNotRestartItsTransition() {
        val listening = MascotTransition(MascotMood.DANCING)
            .retarget(2000, MascotMood.LISTENING)
        assertSame(listening, listening.retarget(2100, MascotMood.LISTENING))
    }
    @Test fun reducedMotionBypassesAnInProgressTransition() {
        val operating = MascotTransition(MascotMood.DANCING)
            .retarget(2000, MascotMood.OPERATING)
        assertEquals(MascotPose(), operating.pose(2001, reducedMotion = true))
    }
    @Test fun blinkingHasQuietOpenEyeIntervals() {
        assertEquals(1f, MascotMotion.pose(1000, MascotMood.LISTENING).eyeOpen, 0f)
        assertEquals(0f, MascotMotion.pose(2800, MascotMood.LISTENING).eyeOpen, 0.001f)
        assertEquals(1f, MascotMotion.pose(3100, MascotMood.LISTENING).eyeOpen, 0f)
    }
    @Test fun danceSettlesBeforeLoopingAndBlendsIntoListening() {
        val dancing = MascotMotion.pose(2000, MascotMood.DANCING)
        assertTrue(kotlin.math.abs(dancing.sway) > 2f)
        assertTrue(dancing.lift > 1f)
        for (time in listOf(0L, 6800L, 7999L, 8000L)) {
            val resting = MascotMotion.pose(time, MascotMood.DANCING)
            assertEquals(0f, resting.sway, .001f)
            assertEquals(0f, resting.lift, .001f)
            assertEquals(0f, resting.bodyTilt, .001f)
        }
        val listening = MascotMotion.pose(2000, MascotMood.LISTENING)
        assertEquals(dancing, MascotMotion.blend(dancing, listening, 0f))
        val settled = MascotMotion.blend(dancing, listening, 1f)
        assertEquals(listening.headTilt, settled.headTilt, .0001f)
        assertEquals(0f, settled.sway, .0001f)
        assertEquals(0f, settled.lift, .0001f)
        assertEquals(0f, settled.bodyTilt, .0001f)
        val halfway = MascotMotion.blend(dancing, listening, .5f)
        assertEquals(dancing.sway * .5f, halfway.sway, .001f)
    }
    @Test fun danceVariesItsStepTimingAndWeightWithoutChangingTheExpression() {
        val poses = (1000L..5000L step 5).map { time ->
            time to MascotMotion.pose(time, MascotMood.DANCING)
        }
        val crossings = poses.zipWithNext().filter { (a, b) ->
            a.second.sway * b.second.sway < 0f
        }.map { it.second.first }
        val stepDurations = crossings.zipWithNext { a, b -> b - a }
        assertTrue("The phrase should contain several alternating steps", stepDurations.size >= 3)
        assertTrue("The step timing should breathe instead of repeating at a fixed speed",
            stepDurations.max() - stepDurations.min() > 70L)
        val strongestRight = poses.maxOf { it.second.sway }
        val strongestLeft = -poses.minOf { it.second.sway }
        assertTrue("The return step should be slightly lighter", strongestRight - strongestLeft > .2f)
        val rightLift = poses.filter { it.second.sway > 0 }.maxOf { it.second.lift }
        val leftLift = poses.filter { it.second.sway < 0 }.maxOf { it.second.lift }
        assertTrue("Alternating steps should not bounce with identical weight", rightLift - leftLift > .4f)
        assertTrue("The head should follow through while the body crosses center", poses.any {
            kotlin.math.abs(it.second.bodyTilt) < .1f && kotlin.math.abs(it.second.headTilt) > 1f
        })
        for ((time, pose) in poses) {
            assertEquals(1.3f, pose.smile, .0001f)
            assertEquals(MascotMotion.pose(time, MascotMood.LISTENING).eyeOpen, pose.eyeOpen, 0f)
        }
    }
    @Test fun danceStaysWithinItsExistingMovementBounds() {
        for (time in 0L..MascotMotion.CYCLE_MS step 5) {
            val pose = MascotMotion.pose(time, MascotMood.DANCING)
            assertTrue("Head tilt at $time", kotlin.math.abs(pose.headTilt) <= 5.001f)
            assertTrue("Body tilt at $time", kotlin.math.abs(pose.bodyTilt) <= 5.001f)
            assertTrue("Sway at $time", kotlin.math.abs(pose.sway) <= 4.001f)
            assertTrue("Lift at $time", pose.lift in 0f..3.001f)
            assertTrue("Squash at $time", kotlin.math.abs(pose.squash) <= .0181f)
        }
    }
    @Test fun danceKeepsPositionAndVelocityContinuousAtThePhraseAndLoopBoundaries() {
        fun channels(time: Long): List<Float> = MascotMotion.pose(time, MascotMood.DANCING).let {
            listOf(it.headTilt, it.bodyTilt, it.sway, it.lift, it.squash, it.breath)
        }
        for (boundary in listOf(200L, 1000L, 5000L, 6400L, MascotMotion.CYCLE_MS)) {
            val before = channels(boundary - 1)
            val center = channels(boundary)
            val after = channels(boundary + 1)
            for (index in center.indices) {
                val enteringVelocity = center[index] - before[index]
                val leavingVelocity = after[index] - center[index]
                assertTrue("No position jump at $boundary, channel $index",
                    kotlin.math.abs(enteringVelocity) < .03f && kotlin.math.abs(leavingVelocity) < .03f)
                assertEquals("Velocity should stay continuous at $boundary, channel $index",
                    enteringVelocity, leavingVelocity, .0005f)
            }
        }
        for (time in listOf(0L, 1000L, 3100L, 6200L, 7999L)) {
            assertEquals(MascotMotion.pose(time, MascotMood.DANCING),
                MascotMotion.pose(time + MascotMotion.CYCLE_MS, MascotMood.DANCING))
        }
    }
}
