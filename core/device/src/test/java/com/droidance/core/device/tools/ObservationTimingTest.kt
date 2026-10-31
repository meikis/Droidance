// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ObservationTimingTest {
    @Test fun timingIsInvocationScopedAndRetainedWhenCaptureThrows() = runBlocking {
        val timing = ObservationTiming()
        withContext(timing) {
            assertSame(timing, ObservationTiming.current())
            try { ObservationTiming.measure("capture_ms") { error("capture failed") } }
            catch (_: IllegalStateException) { }
            timing.add("frames", 2)
        }
        assertNull(ObservationTiming.current())
        assertTrue(timing.snapshot().getValue("capture_ms") >= 0)
        assertEquals(2L, timing.snapshot()["frames"])
        val next = ObservationTiming()
        withContext(next) { ObservationTiming.measure("capture_ms") { 1 } }
        assertFalse(next.snapshot().containsKey("frames"))
    }
}
