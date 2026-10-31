// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSpaceFrameFreshnessTest {
    private fun frame(vararg bytes: Int, producedAtMs: Long) =
        VirtualSpaceFrameFreshness.FrameSample(bytes.map(Int::toByte).toByteArray(), producedAtMs)

    @Test fun missingCaptureBreaksConsecutiveReadinessAndReportsCause() {
        var now = 0L
        val frames = ArrayDeque(listOf(frame(1, producedAtMs = 20), null,
            frame(2, producedAtMs = 30)))
        val result = VirtualSpaceFrameFreshness.waitForFreshFrame(
            previousFrame = null, minProducedAtMs = 10, timeoutMs = 150, pollMs = 50,
            capture = { frames.removeFirst() }, isBlack = { false }, nowMs = { now }, sleep = { now += it }
        )
        assertFalse(result.fresh)
        assertEquals(3, result.polls)
        assertEquals(1, result.missingFrames)
        assertEquals(150L, result.elapsedMs)
    }

    @Test
    fun identicalRenderableFrameIsNotFresh() {
        val previous = frame(1, 2, 3, producedAtMs = 10)

        assertFalse(
            VirtualSpaceFrameFreshness.isFresh(
                previousFrame = previous,
                candidateFrame = previous.copy(bytes = previous.bytes.copyOf(), producedAtMs = 20),
                isBlack = false,
                minProducedAtMs = 10
            )
        )
    }

    @Test
    fun changedRenderableFrameIsFresh() {
        assertTrue(
            VirtualSpaceFrameFreshness.isFresh(
                previousFrame = frame(1, 2, 3, producedAtMs = 10),
                candidateFrame = frame(1, 2, 4, producedAtMs = 20),
                isBlack = false,
                minProducedAtMs = 10
            )
        )
    }

    @Test
    fun changedFrameProducedBeforeBoundaryIsNotFresh() {
        assertFalse(
            VirtualSpaceFrameFreshness.isFresh(
                previousFrame = frame(1, 2, 3, producedAtMs = 10),
                candidateFrame = frame(1, 2, 4, producedAtMs = 19),
                isBlack = false,
                minProducedAtMs = 20
            )
        )
    }

    @Test
    fun firstRenderableFrameIsFreshWhenThereWasNoPreviousFrame() {
        assertTrue(
            VirtualSpaceFrameFreshness.isFresh(
                previousFrame = null,
                candidateFrame = frame(1, 2, 3, producedAtMs = 20),
                isBlack = false,
                minProducedAtMs = 10
            )
        )
    }

    @Test
    fun blackFrameIsNeverFresh() {
        assertFalse(
            VirtualSpaceFrameFreshness.isFresh(
                previousFrame = frame(1, 2, 3, producedAtMs = 10),
                candidateFrame = frame(4, 5, 6, producedAtMs = 20),
                isBlack = true,
                minProducedAtMs = 10
            )
        )
    }

    @Test
    fun oldFramesAreRetriedUntilANewFrameArrives() {
        val oldFrame = frame(1, 2, 3, producedAtMs = 10)
        val frames = ArrayDeque(
            listOf(
                oldFrame.copy(bytes = oldFrame.bytes.copyOf(), producedAtMs = 20),
                frame(1, 2, 4, producedAtMs = 30),
                frame(1, 2, 4, producedAtMs = 40)
            )
        )
        var nowMs = 0L
        var captures = 0

        val result = VirtualSpaceFrameFreshness.waitForFreshFrame(
            previousFrame = oldFrame,
            minProducedAtMs = oldFrame.producedAtMs,
            timeoutMs = 300,
            pollMs = 50,
            capture = {
                captures++
                frames.removeFirst()
            },
            isBlack = { false },
            nowMs = { nowMs },
            sleep = { nowMs += it }
        )

        assertTrue(result.fresh)
        assertEquals(3, captures)
        assertEquals(100, nowMs)
    }

    @Test
    fun unchangedFrameTimesOutWithinTheSharedBudget() {
        val oldFrame = frame(1, 2, 3, producedAtMs = 10)
        var nowMs = 0L
        val captureBudgets = mutableListOf<Long>()

        val result = VirtualSpaceFrameFreshness.waitForFreshFrame(
            previousFrame = oldFrame,
            minProducedAtMs = oldFrame.producedAtMs,
            timeoutMs = 300,
            pollMs = 80,
            capture = { remainingMs ->
                captureBudgets += remainingMs
                oldFrame.copy(bytes = oldFrame.bytes.copyOf(), producedAtMs = 20 + nowMs)
            },
            isBlack = { false },
            nowMs = { nowMs },
            sleep = { nowMs += it }
        )

        assertFalse(result.fresh)
        assertTrue(result.sawPreviousRenderableFrame)
        assertEquals(300, nowMs)
        assertEquals(listOf(300L, 220L, 140L, 60L), captureBudgets)
    }

    @Test
    fun blackFrameIsRetriedBeforeANewRenderableFrame() {
        val oldFrame = frame(1, 2, 3, producedAtMs = 10)
        val blackFrame = frame(0, 0, 0, producedAtMs = 20)
        val newFrame = frame(4, 5, 6, producedAtMs = 30)
        val frames = ArrayDeque(listOf(blackFrame, newFrame, newFrame.copy(producedAtMs = 40)))
        var nowMs = 0L

        val result = VirtualSpaceFrameFreshness.waitForFreshFrame(
            previousFrame = oldFrame,
            minProducedAtMs = oldFrame.producedAtMs,
            timeoutMs = 200,
            pollMs = 50,
            capture = { frames.removeFirst() },
            isBlack = { it.contentEquals(blackFrame.bytes) },
            nowMs = { nowMs },
            sleep = { nowMs += it }
        )

        assertTrue(result.fresh)
        assertEquals(100, nowMs)
    }

    @Test
    fun animatedFreshFramesCanBecomeReadyWithoutIdenticalPngBytes() {
        val oldFrame = frame(1, 2, 3, producedAtMs = 10)
        val frames = ArrayDeque(
            listOf(
                frame(4, 5, 6, producedAtMs = 20),
                frame(4, 5, 7, producedAtMs = 30)
            )
        )
        var nowMs = 0L

        val result = VirtualSpaceFrameFreshness.waitForFreshFrame(
            previousFrame = oldFrame,
            minProducedAtMs = oldFrame.producedAtMs,
            timeoutMs = 100,
            pollMs = 25,
            capture = { frames.removeFirst() },
            isBlack = { false },
            nowMs = { nowMs },
            sleep = { nowMs += it }
        )

        assertTrue(result.fresh)
        assertEquals(25, nowMs)
    }
}
