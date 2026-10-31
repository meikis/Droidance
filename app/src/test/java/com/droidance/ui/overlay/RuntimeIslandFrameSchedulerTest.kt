// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeIslandFrameSchedulerTest {
    @Test fun inputBurstsSubmitOnlyTheNewestCoordinatesOncePerDisplayFrame() {
        for (refreshRate in listOf(60, 90, 120)) {
            var requests = 0
            var latestX = 0
            val submitted = mutableListOf<Pair<Long, Int>>()
            val scheduler = RuntimeIslandFrameScheduler({ requests++ }, {}, { submitted += it to latestX })
            for (frame in 1L..refreshRate.toLong()) {
                repeat(16) { latestX++; scheduler.request() }
                scheduler.onFrame(frame)
                assertEquals(frame to latestX, submitted.last())
                assertEquals(frame.toInt(), submitted.size)
            }
            assertEquals(refreshRate, requests)
        }
    }

    @Test fun releasingTheFingerCommitsItsFinalPositionAndCancelsTheQueuedFrame() {
        var cancels = 0
        var latestX = 0
        val submitted = mutableListOf<Pair<Long, Int>>()
        val scheduler = RuntimeIslandFrameScheduler({}, { cancels++ }, { submitted += it to latestX })
        latestX = 20
        scheduler.request()
        latestX = 80
        scheduler.request()
        scheduler.flush()
        scheduler.onFrame(100)
        assertEquals(listOf(RuntimeIslandFrameScheduler.NO_FRAME_TIMELINE to 80), submitted)
        assertEquals(1, cancels)
    }

    @Test fun hidingTheIslandDiscardsPendingMovementAndTheNextGestureStillWorks() {
        val submitted = mutableListOf<Long>()
        val scheduler = RuntimeIslandFrameScheduler({}, {}, { submitted += it })
        scheduler.request()
        scheduler.cancel()
        scheduler.onFrame(10)
        assertEquals(emptyList<Long>(), submitted)
        scheduler.request()
        scheduler.onFrame(20)
        assertEquals(listOf(20L), submitted)
    }

    @Test fun inputReceivedDuringSubmissionIsScheduledForTheFollowingFrame() {
        var requests = 0
        var submissions = 0
        lateinit var scheduler: RuntimeIslandFrameScheduler
        scheduler = RuntimeIslandFrameScheduler({ requests++ }, {}, {
            submissions++
            if (submissions == 1) scheduler.request()
        })
        scheduler.request()
        scheduler.onFrame(10)
        assertEquals(2, requests)
        scheduler.onFrame(20)
        assertEquals(2, submissions)
    }
}
