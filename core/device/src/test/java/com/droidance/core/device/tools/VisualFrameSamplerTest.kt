// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VisualFrameSamplerTest {
    @Test fun aPersistentLocalAnimationEndsSamplingWithoutClaimingAStaticPage() = runBlocking {
        var now = 0L
        var index = 0
        val sampler = VisualFrameSampler({
            index++
            VisualFrameSampler.Frame(ByteArray(100) { if (it < 10) (index * 40).toByte() else 80 }, index.toLong())
        }, { now }, { now += it })
        val result = sampler.sample(800, 120, 2, 8, 0.005, 0.15, 3)
        assertFalse(result.stable)
        assertTrue(result.localizedMotion)
        assertEquals(3, result.captures)
        assertEquals(240L, result.elapsedMs)
        assertEquals(3L, result.diagnostics["distinct_frames"])
        assertEquals(100000L, result.diagnostics["motion_union_ppm"])
    }

    @Test fun aCompletedScrollDoesNotPoisonTheLaterLocalizedMotionWindow() = runBlocking {
        var now = 0L
        var index = 0
        val sampler = VisualFrameSampler({
            index++
            VisualFrameSampler.Frame(ByteArray(100) {
                if (index == 1) 20 else if (it < 10) (index * 40).toByte() else 80
            }, index.toLong())
        }, { now }, { now += it })
        val result = sampler.sample(800, 120, 3, 8, 0.005, 0.15, 3)
        assertFalse(result.stable)
        assertTrue(result.localizedMotion)
        assertEquals(4, result.captures)
        assertEquals(100000L, result.diagnostics["motion_union_ppm"])
        assertEquals(1000000L, result.diagnostics["max_changed_ppm"])
    }

    @Test fun aCaptureCompletingAfterItsDeadlineCannotEstablishReadiness() = runBlocking {
        var now = 0L
        var id = 0L
        val result = VisualFrameSampler({
            now += 100
            VisualFrameSampler.Frame(ByteArray(100) { 80 }, ++id)
        }, { now }, { now += it }).sample(150, 10, 2, 8, 0.005)
        assertFalse(result.stable)
        assertFalse(result.localizedMotion)
    }

    @Test fun motionSpreadingAcrossThePageCannotQualifyAsALocalAnimation() = runBlocking {
        var now = 0L
        var index = 0
        val sampler = VisualFrameSampler({
            index++
            VisualFrameSampler.Frame(ByteArray(100) { if (it < index * 10) 120 else 20 }, index.toLong())
        }, { now }, { now += it })
        val result = sampler.sample(480, 120, 2, 8, 0.005, 0.15, 3)
        assertFalse(result.stable)
        assertFalse(result.localizedMotion)
        assertEquals(480L, result.elapsedMs)
    }

    @Test fun unchangedPageReturnsAfterOneIntervalInsteadOfWaitingForAnEffect() = runBlocking {
        var now = 0L
        val result = VisualFrameSampler({ VisualFrameSampler.Frame(ByteArray(4096) { 80 }, now) }, { now }, { now += it })
            .sample(2000, 120, 2, 8, 0.005)
        assertTrue(result.stable)
        assertEquals(2, result.captures)
        assertEquals(120L, result.elapsedMs)
    }

    @Test fun repeatedCacheReadsDoNotEstablishStableRendering() = runBlocking {
        var now = 0L
        val cached = VisualFrameSampler.Frame(ByteArray(100) { 80 }, 7)
        val result = VisualFrameSampler({ cached }, { now }, { now += it })
            .sample(240, 120, 2, 8, 0.005)
        assertFalse(result.stable)
        assertEquals(240L, result.elapsedMs)
        assertEquals(1L, result.diagnostics["distinct_frames"])
        assertEquals(1L, result.diagnostics["cached_reads"])
        assertEquals(1L, result.diagnostics["unavailable_reads"])
    }

    @Test fun delayedNewFrameIsNotMistakenForStableOldCache() = runBlocking {
        var now = 0L
        val samples = ArrayDeque(listOf(
            VisualFrameSampler.Frame(ByteArray(100) { 20 }, 1),
            VisualFrameSampler.Frame(ByteArray(100) { 20 }, 1),
            VisualFrameSampler.Frame(ByteArray(100) { 100 }, 2),
            VisualFrameSampler.Frame(ByteArray(100) { 100 }, 3)
        ))
        val result = VisualFrameSampler({ samples.removeFirst() }, { now }, { now += it })
            .sample(1000, 120, 2, 8, 0.005)
        assertTrue(result.stable)
        assertEquals(4, result.captures)
    }

    @Test fun longerStableWindowWaitsThroughDelayedNavigationWithDistinctFrameIds() = runBlocking {
        var now = 0L
        var index = 0
        val values = listOf(20, 20, 80, 120, 160, 160, 160, 160)
        val sampler = VisualFrameSampler({
            val value = values[index++]
            VisualFrameSampler.Frame(ByteArray(4096) { value.toByte() }, index.toLong())
        }, { now }, { now += it })
        val result = sampler.sample(2000, 120, 4, 8, 0.005)
        assertTrue(result.stable)
        assertEquals(8, result.captures)
        assertEquals(840L, result.elapsedMs)
    }

    @Test fun localAnimationDoesNotPreventRenderingReadiness() {
        val before = ByteArray(4096) { 20 }
        val cursor = before.copyOf().apply { repeat(10) { this[it] = 120 } }
        val scroll = before.copyOf().apply { repeat(300) { this[it] = 120 } }
        assertTrue(VisualFrameSampler.similar(before, cursor, 8, 0.005))
        assertFalse(VisualFrameSampler.similar(before, scroll, 8, 0.005))
        assertFalse(VisualFrameSampler.similar(before, byteArrayOf(20), 8, 0.005))
    }

    @Test fun continuousMotionTimesOutAndMissingSampleNeverBecomesStable() = runBlocking {
        val missing = VisualFrameSampler({ null }, { 0L }).sample(2000, 120, 2, 8, 0.005)
        assertFalse(missing.stable)
        assertEquals(1, missing.captures)
        assertEquals(1L, missing.diagnostics["unavailable_reads"])
    }

    @Test fun motionBudgetAndCancellationAreBounded() = runBlocking {
        var now = 0L
        var frame = 0
        val sampler = VisualFrameSampler({ frame++; VisualFrameSampler.Frame(ByteArray(100) { (frame * 40).toByte() }, frame.toLong()) },
            { now }, { now += it })
        val result = sampler.sample(240, 120, 2, 8, 0.005)
        assertFalse(result.stable)
        assertEquals(3, result.captures)
        var captures = 0
        try {
            VisualFrameSampler({ captures++; VisualFrameSampler.Frame(byteArrayOf(0), captures.toLong()) }, { 0L }, { throw CancellationException() })
                .sample(2000, 120, 2, 8, 0.005)
            fail("cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1, captures) }
    }
}
