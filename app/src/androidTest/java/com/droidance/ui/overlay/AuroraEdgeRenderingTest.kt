// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.device.agent.device.AuroraEdgePainter
import org.junit.Assert.*
import org.junit.Test

class AuroraEdgeRenderingTest {
    @Test fun previewAndMainScreenHaveIdenticalPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val preview = Bitmap.createBitmap((240 * density).toInt(), (480 * density).toInt(), Bitmap.Config.ARGB_8888)
        val mainScreen = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            val view = AuroraEdgeView(context)
            view.layout(0, 0, preview.width, preview.height)
            view.alpha = 1f
            view.draw(Canvas(preview))
            val painter = AuroraEdgePainter(context.resources.displayMetrics.density)
            painter.resize(mainScreen.width, mainScreen.height, view.cornerRadii.toPathRadii())
            painter.draw(Canvas(mainScreen), 0f, 1f)
        }
        assertTrue(preview.sameAs(mainScreen))
        assertEquals(0, Color.alpha(mainScreen.getPixel(mainScreen.width / 2, mainScreen.height / 2)))
        assertTrue(Color.alpha(mainScreen.getPixel(0, mainScreen.height / 2)) > 0)
        preview.recycle()
        mainScreen.recycle()
    }

    @Test fun flowLoopsContinuouslyAndFadePreservesTheTransparentCenter() {
        val painter = AuroraEdgePainter(1f)
        painter.resize(240, 480, DeviceCornerRadii(12f, 24f, 36f, 48f).toPathRadii())
        fun frame(phase: Float, opacity: Float = 1f) =
            Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888).also {
                painter.draw(Canvas(it), phase, opacity)
            }
        val first = frame(0f)
        val next = frame(0.25f)
        val end = frame(1f)
        val hidden = frame(0f, 0f)
        assertFalse(first.sameAs(next))
        assertTrue(first.sameAs(end))
        assertEquals(0, Color.alpha(hidden.getPixel(0, 240)))
        for (bitmap in listOf(first, next, end, hidden)) {
            assertEquals(0, Color.alpha(bitmap.getPixel(120, 240)))
            bitmap.recycle()
        }
    }
}
