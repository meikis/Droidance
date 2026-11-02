// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FrameChannelRecoveryTest {
    @Test fun cancellationDuringRetryDelayDoesNotResetOrKeepCapturing() = runBlocking {
        var captures = 0
        var resets = 0
        try {
            kotlinx.coroutines.withTimeout(50) {
                captureWithFrameRecovery<String>(3, capture = { captures++; null },
                    reset = { resets++; true }, report = { _, _ -> }, retryDelayMs = 5000)
            }
            fail("expected timeout")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertEquals(1, captures)
        assertEquals(0, resets)
    }

    @Test fun transientReadExceptionRecoversWithoutResettingOrReplayingInput() = runBlocking {
        var captures = 0
        val failures = mutableListOf<String>()
        val frame = captureWithFrameRecovery(3,
            capture = { if (++captures == 1) throw java.io.IOException("temporary") else "fresh" },
            reset = { error("healthy channel must not be reset") }, report = { _, _ -> },
            onFailure = { stage, _ -> failures += stage })
        assertEquals("fresh", frame)
        assertEquals(2, captures)
        assertEquals(listOf("capture"), failures)
    }

    @Test fun failedResetStillRequiresAndCanRecoverAFreshFrame() = runBlocking {
        var captures = 0
        val failures = mutableListOf<String>()
        val frame = captureWithFrameRecovery(2,
            capture = { if (++captures <= 2) throw IllegalStateException("unavailable") else "fresh" },
            reset = { throw java.io.IOException("reset unavailable") }, report = { _, _ -> },
            onFailure = { stage, _ -> failures += stage })
        assertEquals("fresh", frame)
        assertEquals(listOf("capture", "capture", "reset"), failures)
    }

    @Test fun persistentReadFailuresExhaustTheBudgetAndPreserveDiagnostics() = runBlocking {
        val failures = mutableListOf<String>()
        assertNull(captureWithFrameRecovery<String>(2,
            capture = { throw IllegalStateException("broken surface") }, reset = { false },
            report = { _, _ -> }, onFailure = { stage, _ -> failures += stage }))
        assertEquals(listOf("capture", "capture", "capture_after_reset"), failures)
    }

    @Test fun healthyFrameDoesNotResetChannel() = runBlocking {
        val frame = captureWithFrameRecovery(2, { "pixels" }, { error("unexpected reset") }, { _, _ -> })
        assertEquals("pixels", frame)
    }

    @Test fun resetRequiresAnActualFrameAndResumeGetsANewBudget() = runBlocking {
        var resets = 0
        var captures = 0
        val events = mutableListOf<Pair<String, Boolean>>()
        suspend fun observe() = captureWithFrameRecovery<String>(2,
            capture = { captures++; null }, reset = { resets++; true },
            report = { stage, ok -> events += stage to ok })
        assertNull(observe())
        assertNull(observe())
        assertEquals(2, resets)
        assertEquals(6, captures)
        assertEquals(2, events.count { it == "capture_after_reset" to false })
    }

    @Test fun recoversOnlyAfterCapturingFreshPixels() = runBlocking {
        var captures = 0
        assertEquals("fresh", captureWithFrameRecovery(2,
            capture = { if (++captures == 3) "fresh" else null },
            reset = { true }, report = { _, _ -> }))
    }

    @Test fun cancellationNeverStartsRecovery() = runBlocking {
        var reset = false
        try {
            captureWithFrameRecovery<String>(2,
                capture = { throw kotlinx.coroutines.CancellationException() },
                reset = { reset = true; true }, report = { _, _ -> })
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(reset)
    }
}
