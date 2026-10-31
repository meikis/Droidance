// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.device.tools.observe.VisionFrameScaler
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FrameDerivationCacheTest {
    private val config = VisionFrameScaler.Config(1568, 1_150_000, 85)

    @Test fun concurrentConsumersShareOneEncodingForTheSameConfiguration() {
        val cache = FrameDerivationCache<VisionFrameScaler.Config, Any>()
        val encodings = AtomicInteger()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll(List(16) { Callable {
                cache.getOrCompute(config.copy()) { encodings.incrementAndGet(); Any() }
            } }).map { it.get() }
            assertEquals(1, encodings.get())
            results.forEach { assertSame(results.first(), it) }
        } finally { workers.shutdownNow() }
    }

    @Test fun changedGeometryOrQualityCannotReuseAnOlderRepresentation() {
        val cache = FrameDerivationCache<VisionFrameScaler.Config, Any>()
        var previous = cache.getOrCompute(config) { Any() }
        for (changed in listOf(config.copy(maxLongEdge = 900), config.copy(maxPixels = 500_000),
            config.copy(jpegQuality = 60))) {
            val current = cache.getOrCompute(changed) { Any() }
            assertNotSame(previous, current)
            previous = current
        }
        assertNotSame(previous, cache.getOrCompute(config) { Any() })
    }

    @Test fun aFailedNewEncodingDoesNotReturnOldPixelsOrPoisonTheNextAttempt() {
        val cache = FrameDerivationCache<VisionFrameScaler.Config, Any>()
        val original = cache.getOrCompute(config) { Any() }
        val changed = config.copy(maxLongEdge = 700)
        assertNull(cache.getOrCompute(changed) { null })
        assertSame(original, cache.getOrCompute(config) { error("lost successful encoding") })
        val repaired = cache.getOrCompute(changed) { Any() }
        assertNotSame(original, repaired)
        assertSame(repaired, cache.getOrCompute(changed) { error("encoded twice") })
    }

    @Test fun differentCapturedFramesNeverShareTheirRepresentationCache() {
        val first = FrameDerivationCache<VisionFrameScaler.Config, Any>()
        val next = FrameDerivationCache<VisionFrameScaler.Config, Any>()
        assertNotSame(first.getOrCompute(config) { Any() }, next.getOrCompute(config) { Any() })
    }
}
