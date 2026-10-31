// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCornerRadiusTest {

    @Test
    fun cornerRadiiMapsClockwiseToEightPathElements() {
        val radii = DeviceCornerRadii(
            topLeft = 10f,
            topRight = 20f,
            bottomRight = 30f,
            bottomLeft = 40f
        )
        val expected = floatArrayOf(
            10f, 10f, // TL
            20f, 20f, // TR
            30f, 30f, // BR
            40f, 40f  // BL
        )
        assertArrayEquals(expected, radii.toPathRadii(), 0.001f)
    }

    @Test
    fun cornerRadiiDetectsZeroAndUniformStates() {
        val zero = DeviceCornerRadii.ZERO
        assertTrue(zero.isAllZero())

        val uniform = DeviceCornerRadii.uniform(38f)
        assertFalse(uniform.isAllZero())
        assertEquals(38f, uniform.topLeft, 0.001f)
        assertEquals(38f, uniform.topRight, 0.001f)
        assertEquals(38f, uniform.bottomRight, 0.001f)
        assertEquals(38f, uniform.bottomLeft, 0.001f)
    }

    @Test
    fun deviceCornerRadiusResolvesAllFourCornerPositions() {
        val helper = projectFile("app/src/main/java/com/droidance/ui/overlay/DeviceCornerRadius.kt").readText()

        assertTrue(helper.contains("RoundedCorner.POSITION_TOP_LEFT"))
        assertTrue(helper.contains("RoundedCorner.POSITION_TOP_RIGHT"))
        assertTrue(helper.contains("RoundedCorner.POSITION_BOTTOM_RIGHT"))
        assertTrue(helper.contains("RoundedCorner.POSITION_BOTTOM_LEFT"))
        assertTrue(helper.contains("getSystemDimenPx(context, \"rounded_corner_radius_top\")"))
        assertTrue(helper.contains("getSystemDimenPx(context, \"rounded_corner_radius_bottom\")"))
        assertTrue(helper.contains("getSystemDimenPx(context, \"rounded_corner_radius\")"))
        assertTrue(helper.contains("const val DEFAULT_FALLBACK_RADIUS_DP = 38f"))
    }

    @Test
    fun auroraEdgeViewUsesDynamicCornerRadiiAndPath() {
        val aurora = projectFile("app/src/main/java/com/droidance/ui/overlay/AuroraEdgeView.kt").readText()

        assertTrue(aurora.contains("DeviceCornerRadius.resolve(context"))
        assertTrue(aurora.contains("fun updateWindowInsets(insets: WindowInsets?)"))
        assertTrue(aurora.contains("painter.resize(width, height, cornerRadii.toPathRadii())"))
        assertTrue(aurora.contains("painter.draw(canvas, phase, 1f)"))
        val painter = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/AuroraEdgePainter.java").readText()
        assertTrue(painter.contains("canvas.drawPath(edgePath, haloPaint)"))
        assertTrue(painter.contains("canvas.drawPath(edgePath, weavePaint)"))
        assertTrue(painter.contains("canvas.drawPath(edgePath, corePaint)"))
        // 不再写死固定的 val cornerR = dp * 38f
        assertFalse(aurora.contains("val cornerR = dp * 38f"))
    }

    @Test
    fun auroraEdgePainterUsesCompactGlowWidthToPreventBleeding() {
        val painter = projectFile("core/device/src/main/java/com/droidance/core/device/agent/device/AuroraEdgePainter.java").readText()
        assertTrue(painter.contains("haloPaint = stroke(density, 8f, 8f)"))
        assertTrue(painter.contains("weavePaint = stroke(density, 5f, 5f)"))
        assertTrue(painter.contains("corePaint = stroke(density, 2.5f, 2f)"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
