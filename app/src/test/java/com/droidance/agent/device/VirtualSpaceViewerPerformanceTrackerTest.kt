// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class VirtualSpaceViewerPerformanceTrackerTest {
    @Test fun reportsInputBacklogAndResetsEachWindowWithoutClaimingMirrorFps() {
        val tracker = VirtualSpaceViewerPerformanceTracker(1_000L)
        assertNull(tracker.report(0))
        tracker.recordTouchQueued(4)
        tracker.recordTouchDispatched(7, 13)
        tracker.recordTouchDispatched(30, 20)
        assertNull(tracker.report(999))
        val report = checkNotNull(tracker.report(1_000))
        assertEquals(4, report.maxPendingTouchCount)
        assertEquals(30L, report.touchQueueLagP95Ms)
        assertEquals(20L, report.touchInjectionP95Ms)
        assertFalse(report.toLogLine().contains("fps"))
        assertNull(tracker.report(2_000))
        tracker.recordTouchQueued(1)
        tracker.recordTouchDispatched(-5, -2)
        val next = checkNotNull(tracker.report(3_000))
        assertEquals(1, next.maxPendingTouchCount)
        assertEquals(0L, next.touchQueueLagP95Ms)
        assertEquals(0L, next.touchInjectionP95Ms)
    }
}
