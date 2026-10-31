// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import com.droidance.core.device.tools.InputAction
import com.droidance.ui.overlay.VirtualDisplayTouchMapper.FramePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayTouchMapperTest {

    @Test
    fun sameAspectRatioMapsCornersAndCenter() {
        val w = 600
        val h = 1300
        assertEquals(FramePoint(0, 0), VirtualDisplayTouchMapper.mapViewToFrame(0f, 0f, w, h, w, h))
        assertEquals(FramePoint(300, 650), VirtualDisplayTouchMapper.mapViewToFrame(300f, 650f, w, h, w, h))

        val bottomRight = VirtualDisplayTouchMapper.mapViewToFrame(599.9f, 1299.9f, w, h, w, h)
        assertNotNull(bottomRight)
        assertTrue(bottomRight!!.x in 0 until w)
        assertTrue(bottomRight.y in 0 until h)
    }

    @Test
    fun portraitFrameInWideViewRejectsLeftAndRightBlackBars() {
        // frame 600x1300 (portrait) shown FIT_CENTER inside a 1300x600 (wide) view.
        val fw = 600
        val fh = 1300
        val vw = 1300
        val vh = 600

        assertNull("left black bar", VirtualDisplayTouchMapper.mapViewToFrame(100f, 300f, vw, vh, fw, fh))
        assertNull("right black bar", VirtualDisplayTouchMapper.mapViewToFrame(1200f, 300f, vw, vh, fw, fh))

        val center = VirtualDisplayTouchMapper.mapViewToFrame(650f, 300f, vw, vh, fw, fh)
        assertNotNull("view center is inside the content rect", center)
        assertTrue(center!!.x in 0 until fw)
        assertTrue(center.y in 0 until fh)
    }

    @Test
    fun landscapeFrameInTallViewRejectsTopAndBottomBlackBars() {
        // frame 1300x600 (landscape) shown FIT_CENTER inside a 600x1300 (tall) view.
        val fw = 1300
        val fh = 600
        val vw = 600
        val vh = 1300

        assertNull("top black bar", VirtualDisplayTouchMapper.mapViewToFrame(300f, 100f, vw, vh, fw, fh))
        assertNull("bottom black bar", VirtualDisplayTouchMapper.mapViewToFrame(300f, 1200f, vw, vh, fw, fh))

        val center = VirtualDisplayTouchMapper.mapViewToFrame(300f, 650f, vw, vh, fw, fh)
        assertNotNull("view center is inside the content rect", center)
        assertTrue(center!!.x in 0 until fw)
        assertTrue(center.y in 0 until fh)
    }

    @Test
    fun mappedCoordinatesStayInsideFrameBounds() {
        val fw = 591
        val fh = 1300
        val vw = 591
        val vh = 1300
        // Bottom-right-most touch must never produce frameWidth/frameHeight itself.
        val point = VirtualDisplayTouchMapper.mapViewToFrame(590.99f, 1299.99f, vw, vh, fw, fh)
        assertNotNull(point)
        assertTrue(point!!.x in 0 until fw)
        assertTrue(point.y in 0 until fh)
    }

    @Test
    fun invalidDimensionsReturnNull() {
        assertNull(VirtualDisplayTouchMapper.mapViewToFrame(10f, 10f, 0, 100, 100, 100))
        assertNull(VirtualDisplayTouchMapper.mapViewToFrame(10f, 10f, 100, 100, 100, 0))
    }

    @Test
    fun scaledPreviewMapsTouchesBackToPhysicalDisplayPixels() {
        val center = VirtualDisplayTouchMapper.mapViewToDisplay(
            viewX = 360f,
            viewY = 792f,
            viewWidth = 720,
            viewHeight = 1584,
            previewWidth = 720,
            previewHeight = 1584,
            displayWidth = 1080,
            displayHeight = 2376
        )

        assertEquals(FramePoint(540, 1188), center)
        val bottomRight = VirtualDisplayTouchMapper.mapViewToDisplay(
            viewX = 719.9f,
            viewY = 1583.9f,
            viewWidth = 720,
            viewHeight = 1584,
            previewWidth = 720,
            previewHeight = 1584,
            displayWidth = 1080,
            displayHeight = 2376
        )
        assertNotNull(bottomRight)
        assertTrue(bottomRight!!.x in 0 until 1080)
        assertTrue(bottomRight.y in 0 until 2376)
    }

    @Test
    fun handFeedbackMapsDisplayPixelsIntoTheLetterboxedPreview() {
        // 虚拟屏 1080x2376 在 720x1200 的视图里 FIT_CENTER：左右留黑边，小手必须落在内容区内。
        val scale = 1200f / 2376f
        val contentWidth = 1080 * scale
        val offsetX = (720 - contentWidth) / 2f

        val center = VirtualDisplayTouchMapper.mapDisplayToView(
            displayX = 540,
            displayY = 1188,
            viewWidth = 720,
            viewHeight = 1200,
            displayWidth = 1080,
            displayHeight = 2376
        )
        assertNotNull(center)
        assertEquals(offsetX + 540 * scale, center!!.x, 0.01f)
        assertEquals(600f, center.y, 0.01f)

        val topLeft = VirtualDisplayTouchMapper.mapDisplayToView(
            displayX = 0,
            displayY = 0,
            viewWidth = 720,
            viewHeight = 1200,
            displayWidth = 1080,
            displayHeight = 2376
        )
        assertNotNull(topLeft)
        assertEquals(offsetX, topLeft!!.x, 0.01f)
        assertEquals(0f, topLeft.y, 0.01f)

        assertNull(
            VirtualDisplayTouchMapper.mapDisplayToView(540, 1188, 0, 1200, 1080, 2376)
        )
    }

    @Test
    fun handFeedbackRoundTripsThroughTheTouchMapping() {
        val viewWidth = 720
        val viewHeight = 1584
        val displayWidth = 1080
        val displayHeight = 2376
        val viewPoint = VirtualDisplayTouchMapper.mapDisplayToView(
            displayX = 540,
            displayY = 1188,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
        assertNotNull(viewPoint)

        val back = VirtualDisplayTouchMapper.mapViewToDisplay(
            viewX = viewPoint!!.x,
            viewY = viewPoint.y,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            previewWidth = 720,
            previewHeight = 1584,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
        assertEquals(FramePoint(540, 1188), back)
    }

    @Test
    fun swipeActionsAreRewrittenIntoPreviewCoordinates() {
        val mapped = VirtualDisplayTouchMapper.mapActionToView(
            action = InputAction.Swipe(
                startX = 540,
                startY = 1782,
                endX = 540,
                endY = 594,
                durationMs = 480
            ),
            viewWidth = 720,
            viewHeight = 1584,
            displayWidth = 1080,
            displayHeight = 2376
        ) as InputAction.Swipe

        assertEquals(360, mapped.startX)
        assertEquals(1188, mapped.startY)
        assertEquals(360, mapped.endX)
        assertEquals(396, mapped.endY)
        assertEquals(480, mapped.durationMs)

        val tap = VirtualDisplayTouchMapper.mapActionToView(
            action = InputAction.Tap(x = 1080, y = 2376),
            viewWidth = 720,
            viewHeight = 1584,
            displayWidth = 1080,
            displayHeight = 2376
        ) as InputAction.Tap
        assertEquals(720, tap.x)
        assertEquals(1584, tap.y)

        assertNull(
            VirtualDisplayTouchMapper.mapActionToView(
                action = InputAction.Tap(x = 10, y = 10),
                viewWidth = 0,
                viewHeight = 1584,
                displayWidth = 1080,
                displayHeight = 2376
            )
        )
    }
}
