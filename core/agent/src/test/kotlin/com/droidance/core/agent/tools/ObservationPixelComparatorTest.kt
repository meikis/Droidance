// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationPixelComparatorTest {
    @Test
    fun identicalMappedFramesHaveNoChangedSamples() {
        val mapper = ModelCoordinateMapper(
            scale = 0.5f,
            modelSize = 4 to 4,
            physicalSize = 8 to 8
        )
        val samples = ObservationPixelComparator.mappedGrid(mapper, 4 to 4, 8 to 8, 2)
        val before = ObservationRaster(4, 4, IntArray(16) { 0xff336699.toInt() })
        val after = ObservationRaster(8, 8, IntArray(64) { 0xff336699.toInt() })

        val reference = ObservationPixelComparator.captureReference(before, samples)
        val comparison = ObservationPixelComparator.compare(reference, after, channelDelta = 8)

        assertEquals(4, comparison.sampledPixels)
        assertEquals(0, comparison.changedPixels)
        assertEquals(0.0, comparison.changedFraction, 0.0)
        assertTrue(comparison.available)
    }

    @Test
    fun onlyMappedPhysicalPointChangesWhenCropAndFullFrameUseSameCoordinates() {
        val mapper = ModelCoordinateMapper(
            scale = 2f,
            modelSize = 4 to 4,
            physicalSize = 12 to 12,
            physicalOrigin = ModelCoordinateMapper.Point(2, 2)
        )
        val samples = ObservationPixelComparator.mappedGrid(mapper, 4 to 4, 12 to 12, 2)
        val before = ObservationRaster(4, 4, IntArray(16) { 0xff101010.toInt() })
        val afterPixels = IntArray(12 * 12) { 0xff101010.toInt() }
        val changed = samples.first()
        afterPixels[changed.physicalY * 12 + changed.physicalX] = 0xffffffff.toInt()
        val after = ObservationRaster(12, 12, afterPixels)

        val comparison = ObservationPixelComparator.compare(
            ObservationPixelComparator.captureReference(before, samples),
            width = after.width,
            height = after.height,
            pixelAt = after::pixelAt,
            channelDelta = 8
        )

        assertEquals(4, comparison.sampledPixels)
        assertEquals(1, comparison.changedPixels)
        assertEquals(0.25, comparison.changedFraction, 0.0)
        assertTrue(ObservationPixelComparator.exceedsChangeFraction(comparison, 0.20))
        assertFalse(ObservationPixelComparator.exceedsChangeFraction(comparison, 0.25))
    }

    @Test
    fun aMovingAnimationProducesAChangeSignalWithoutAssigningMeaningToIt() {
        val samples = listOf(
            ObservationMappedSample(0, 0, 0, 0),
            ObservationMappedSample(1, 0, 1, 0),
            ObservationMappedSample(0, 1, 0, 1),
            ObservationMappedSample(1, 1, 1, 1)
        )
        val reference = ObservationPixelComparator.captureReference(
            ObservationRaster(2, 2, IntArray(4) { 0xff202020.toInt() }),
            samples
        )
        val animationFrame = ObservationRaster(
            2,
            2,
            intArrayOf(0xff202020.toInt(), 0xfff0f0f0.toInt(), 0xff202020.toInt(), 0xfff0f0f0.toInt())
        )

        val comparison = ObservationPixelComparator.compare(reference, animationFrame, channelDelta = 8)

        assertEquals(4, comparison.sampledPixels)
        assertEquals(2, comparison.changedPixels)
        assertTrue(comparison.changedFraction > 0.0)
    }

    @Test
    fun smallChannelNoiseBelowThresholdIsNotMarkedChanged() {
        val sample = ObservationMappedSample(0, 0, 0, 0)
        val reference = ObservationPixelComparator.captureReference(
            ObservationRaster(1, 1, intArrayOf(0xff808080.toInt())),
            listOf(sample)
        )
        val comparison = ObservationPixelComparator.compare(
            reference,
            ObservationRaster(1, 1, intArrayOf(0xff858585.toInt())),
            channelDelta = 8
        )

        assertEquals(1, comparison.sampledPixels)
        assertEquals(0, comparison.changedPixels)
        assertFalse(comparison.changedFraction > 0.0)
    }

    @Test
    fun noValidSamplesAreUnavailableInsteadOfAnUnchangedResult() {
        val reference = ObservationPixelReference(
            samples = listOf(ObservationMappedSample(0, 0, 99, 99)),
            colors = intArrayOf(0xff000000.toInt())
        )

        val comparison = ObservationPixelComparator.compare(
            reference,
            ObservationRaster(2, 2, IntArray(4)),
            channelDelta = 8
        )

        assertEquals(0, comparison.sampledPixels)
        assertFalse(comparison.available)
        assertEquals(1.0, comparison.changedFraction, 0.0)
    }

    @Test
    fun changedSamplesAccuratelyIdentifyChangedRegionAndOcclusion() {
        val samples = listOf(
            ObservationMappedSample(modelX = 100, modelY = 200, physicalX = 100, physicalY = 200),
            ObservationMappedSample(modelX = 500, modelY = 200, physicalX = 500, physicalY = 200),
            ObservationMappedSample(modelX = 100, modelY = 700, physicalX = 100, physicalY = 700),
            ObservationMappedSample(modelX = 500, modelY = 700, physicalX = 500, physicalY = 700)
        )
        val before = ObservationRaster(800, 1000, IntArray(800 * 1000) { 0xffffffff.toInt() })
        val afterPixels = IntArray(800 * 1000) { 0xffffffff.toInt() }
        // Simulate keyboard opening in bottom half (y >= 600)
        for (y in 600 until 1000) {
            for (x in 0 until 800) {
                afterPixels[y * 800 + x] = 0xff000000.toInt()
            }
        }
        val after = ObservationRaster(800, 1000, afterPixels)
        val reference = ObservationPixelComparator.captureReference(before, samples)
        val comparison = ObservationPixelComparator.compare(reference, after, channelDelta = 8)

        assertEquals(4, comparison.sampledPixels)
        assertEquals(2, comparison.changedPixels)
        assertEquals(2, comparison.changedSamples.size)

        val region = requireNotNull(ObservationPixelComparator.changedRegion(comparison.changedSamples))
        assertEquals(100, region.minModelX)
        assertEquals(500, region.maxModelX)
        assertEquals(700, region.minModelY)
        assertEquals(700, region.maxModelY)

        // Target at top (y=200) is NOT occluded
        assertFalse(region.containsOrNear(modelX = 300, modelY = 200))
        assertFalse(region.overlapsBounds(listOf(250, 180, 350, 220)))

        // Target at bottom (y=720) IS occluded
        assertTrue(region.containsOrNear(modelX = 300, modelY = 720))
        assertTrue(region.overlapsBounds(listOf(250, 680, 350, 750)))
    }
}
