// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.*
import org.junit.Test

class TaskExecutionClockTest {
    @Test fun repeatedPausesFreezeImmediatelyAndResumeWithoutResettingElapsedWork() {
        var now = 10_000L
        val clock = TaskExecutionClock { now }
        now += 1_200L
        clock.pause()
        val paused = clock.state.value
        now += 3_600_000L
        clock.pause()
        assertEquals(1_200L, clock.elapsedMs())
        assertEquals(1_200L, paused.elapsedMs(now))
        assertNull(clock.state.value.runningSinceMs)

        clock.resume()
        now += 700L
        clock.resume()
        assertEquals(1_900L, clock.elapsedMs())
        clock.pause()
        now += 3_600_000L
        clock.resume()
        now += 300L
        assertEquals(2_200L, clock.finish())

        now += 50_000L
        clock.resume()
        assertEquals(2_200L, clock.finish())
        assertNull(clock.state.value.runningSinceMs)
    }

    @Test fun cancellationWhilePausedAndZeroWorkKeepTheirFrozenDurations() {
        var now = 0L
        val clock = TaskExecutionClock { now }
        clock.pause()
        now = 3_600_000L
        assertEquals(0L, clock.finish())
        assertEquals(0L, clock.elapsedMs())
    }
}
