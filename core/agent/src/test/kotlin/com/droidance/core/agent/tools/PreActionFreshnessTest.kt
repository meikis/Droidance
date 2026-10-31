// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreActionFreshnessTest {
    // A 10x10 sample grid over a 100x100 display. Rows 0..4 play a video; rows 5..9 are still UI.
    private val samples = (0 until 10).flatMap { row ->
        (0 until 10).map { column -> ObservationMappedSample(column * 10 + 5, row * 10 + 5, column * 10 + 5, row * 10 + 5) }
    }
    private fun isVideo(index: Int) = index / 10 < 5
    // A structured control: a 3x3 block of samples inside the video area.
    private fun isIcon(index: Int) = index / 10 in 1..3 && index % 10 in 4..6
    private fun uiColor(index: Int) = if ((index / 10 + index % 10) % 2 == 0) 0xff202020.toInt() else 0xffe0e0e0.toInt()
    private fun videoColor(index: Int, frame: Int) = (0xff000000.toInt() or ((index * 37 + frame * 91) % 256 shl 8))
    private fun screen(frame: Int, page: Int = 0) = IntArray(samples.size) { index ->
        when {
            page != 0 -> 0xff000000.toInt() or ((index * 53 + page * 17) % 256 shl 16)
            isIcon(index) -> uiColor(index)
            isVideo(index) -> videoColor(index, frame)
            else -> uiColor(index)
        }
    }
    private val reference = ObservationPixelReference(samples, screen(frame = 0))

    private fun decide(ref: ObservationPixelReference, live: IntArray, target: Pair<Int, Int>?): PreActionFreshness.Decision {
        val byPoint = samples.mapIndexed { index, sample -> (sample.physicalX to sample.physicalY) to live[index] }.toMap()
        return PreActionFreshness.decide(ref, 100, 100, { x, y -> byPoint.getValue(x to y) }, target,
            { point, multiplier -> intArrayOf(point.first - 22 * multiplier, point.second - 22 * multiplier,
                point.first + 22 * multiplier, point.second + 22 * multiplier) },
            listOf(1, 2, 4), changeFraction = 0.2, channelDelta = 8)
    }

    @Test fun aPlayingVideoAloneDoesNotStopASwipeOnceItsMovingSamplesAreSetAside() {
        val readings = listOf(screen(frame = 3), screen(frame = 4), screen(frame = 5))
        assertFalse("Video motion alone exceeds the whole-frame limit", decide(reference, readings.first(), null).allowed)
        val still = PreActionFreshness.stillReference(reference, readings, channelDelta = 8)
        assertTrue(still.samples.size >= 50)
        assertTrue(decide(still, readings.last(), null).allowed)
    }

    @Test fun aControlOverlaidOnPlayingVideoIsJudgedByItsOwnStillPixels() {
        val readings = listOf(screen(frame = 3), screen(frame = 4), screen(frame = 5))
        val icon = 55 to 25
        assertFalse(decide(reference, readings.first(), icon).allowed)
        val still = PreActionFreshness.stillReference(reference, readings, channelDelta = 8)
        assertTrue(decide(still, readings.last(), icon).allowed)

        val changedIcon = readings.map { reading -> reading.copyOf().also { live ->
            live.indices.filter(::isIcon).forEach { live[it] = 0xff808000.toInt() } } }
        val stillAroundChangedIcon = PreActionFreshness.stillReference(reference, changedIcon, channelDelta = 8)
        assertFalse("A control that itself changed is still stale", decide(stillAroundChangedIcon, changedIcon.last(), icon).allowed)
    }

    @Test fun aRealPageChangeIsStillRejectedOnItsStillSamples() {
        val readings = List(3) { screen(frame = it, page = 1) }
        val still = PreActionFreshness.stillReference(reference, readings, channelDelta = 8)
        assertEquals("A static new page holds still everywhere", samples.size, still.samples.size)
        assertFalse(decide(still, readings.last(), null).allowed)
        assertFalse(decide(still, readings.last(), 55 to 75).allowed)
    }

    @Test fun samplesInRecurringMotionAreSetAsideWhileTheOverlaidControlStays() {
        // The decision frame and frames sampled while the Agent decided: the video keeps changing.
        val frames = listOf(screen(frame = 0), screen(frame = 1), screen(frame = 2), screen(frame = 3))
        val live = screen(frame = 9)
        val icon = 55 to 25
        assertFalse(decide(reference, live, icon).allowed)
        val kept = PreActionFreshness.withoutRecurringMotion(reference, frames, channelDelta = 8, motionChanges = 2)
        assertEquals("Only the interface is not in recurring motion", 59, kept.samples.size)
        assertTrue(decide(kept, live, icon).allowed)
    }

    @Test fun aChangeThatHappensOnceWhileDecidingStaysInAndStopsTheTap() {
        // A dialog or a new page appears once during the decision and then holds.
        val frames = listOf(screen(frame = 0), screen(frame = 0), screen(frame = 0, page = 1), screen(frame = 0, page = 1))
        val kept = PreActionFreshness.withoutRecurringMotion(reference, frames, channelDelta = 8, motionChanges = 2)
        assertEquals(samples.size, kept.samples.size)
        val live = screen(frame = 0, page = 1)
        assertFalse(decide(kept, live, 55 to 25).allowed)
        assertFalse(decide(kept, live, 55 to 75).allowed)
    }

    @Test fun aScreenThatIsMovingEverywhereLeavesNoStillSamples() {
        val readings = List(3) { frame -> IntArray(samples.size) { videoColor(it, frame * 7 + 1) } }
        assertEquals(0, PreActionFreshness.stillReference(reference, readings, channelDelta = 8).samples.size)
    }
}
