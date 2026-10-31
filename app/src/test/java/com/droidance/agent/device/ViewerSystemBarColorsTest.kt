// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import org.junit.Assert.*
import org.junit.Test

class ViewerSystemBarColorsTest {
    @Test fun navigationSamplesStayAboveTheOemDecorationAtFullDisplaySize() {
        val points = ViewerSystemBarColors.samplePoints(1080, 2376, 120, 48)
        assertEquals(8, points.size)
        assertTrue(points.take(4).all { it.second == 60 })
        assertTrue(points.drop(4).all { it.second == 2326 })
        // Black/white changes inside the final 48 pixels cannot change these samples.
        for (decoration in listOf(0xff000000.toInt(), 0xffffffff.toInt())) {
            val sampled = points.map { (_, y) -> if (y >= 2328) decoration else 0xfffafafa.toInt() }.toIntArray()
            assertEquals(0xfffafafa.toInt(), ViewerSystemBarColors.fromSamples(sampled).navigation)
        }
    }

    @Test fun colorsRetainTheirRgbChannelsAndLightAppearanceHasHysteresis() {
        val colors = ViewerSystemBarColors.fromSamples(IntArray(8) { if (it < 4) 0xff203040.toInt() else 0xffe0f0ff.toInt() })
        assertEquals(0xff203040.toInt(), colors.status)
        assertEquals(0xffe0f0ff.toInt(), colors.navigation)
        assertFalse(ViewerSystemBarColors.isLight(colors.status, true))
        assertTrue(ViewerSystemBarColors.isLight(colors.navigation, false))
        assertTrue(ViewerSystemBarColors.isLight(0xff969696.toInt(), true))
        assertFalse(ViewerSystemBarColors.isLight(0xff969696.toInt(), false))
        assertTrue(ViewerSystemBarColors.samplePoints(1, 1, 120, 48).all { it == 0 to 0 })
    }
}
