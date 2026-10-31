// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools.observe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionFrameScalerTest {
    private val config = VisionFrameScaler.Config(
        maxLongEdge = 1568,
        maxPixels = 1_150_000,
        jpegQuality = 85
    )

    @Test
    fun scaleIsOneWhenImageAlreadyFitsGrid() {
        val scale = VisionFrameScaler.computeScale(800, 1200, config)

        assertEquals(1f, scale)
        assertEquals(800 to 1200, VisionFrameScaler.targetSize(800, 1200, scale))
    }

    @Test
    fun longEdgeLimitControlsTallPhoneScreenshot() {
        val scale = VisionFrameScaler.computeScale(1080, 2400, config)
        val size = VisionFrameScaler.targetSize(1080, 2400, scale)

        assertEquals(1568, size.second)
        assertTrue(size.first <= 1568)
        assertTrue(size.first * size.second <= config.maxPixels)
    }

    @Test
    fun pixelLimitControlsWideFrameWhenLongEdgeFits() {
        val scale = VisionFrameScaler.computeScale(1400, 900, config)
        val size = VisionFrameScaler.targetSize(1400, 900, scale)

        assertTrue(size.first <= config.maxLongEdge)
        assertTrue(size.second <= config.maxLongEdge)
        assertTrue(size.first * size.second <= config.maxPixels)
    }

    @Test
    fun extremeLongImageKeepsAtLeastOnePixelPerSide() {
        val scale = VisionFrameScaler.computeScale(100, 20_000, config)
        val size = VisionFrameScaler.targetSize(100, 20_000, scale)

        assertTrue(size.first >= 1)
        assertTrue(size.second <= config.maxLongEdge)
    }
}
