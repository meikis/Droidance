// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.core.device.agent.device.CursorMotion
import com.droidance.core.device.agent.device.RobotHandPainter
import com.droidance.core.device.tools.InputAction
import com.droidance.ui.overlay.TouchIndicatorView
import com.droidance.ui.overlay.HandCursorBitmap
import com.droidance.ui.overlay.VirtualSpaceHandCue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MascotRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun bothRenderersPlaceTheFingertipAtTheCurrentGesturePosition() {
        val hand = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        RobotHandPainter().draw(Canvas(bitmap), hand, 20f, 40f, 80f, 40f,
            CursorMotion.sample(1300, 1000, 0, 600, true), true, false, 1f)
        assertEquals(Color.RED, bitmap.getPixel(52, 42))
        assertNotEquals(Color.RED, bitmap.getPixel(22, 42))
        assertNotEquals(Color.RED, bitmap.getPixel(82, 42))
    }

    @Test fun importedSpritesHaveRealAlphaAndOpaqueFaces() {
        for (id in listOf(R.drawable.droidance_mascot_head, R.drawable.droidance_mascot_body)) {
            val bitmap = MascotSprites.load(context, id)
            assertTrue("Asset must contain a real alpha channel", bitmap.hasAlpha())
            assertEquals(255, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)))
            for (x in 0 until bitmap.width) {
                assertEquals("Black backdrop at top edge", 0, Color.alpha(bitmap.getPixel(x, 0)))
                assertEquals("Black backdrop at bottom edge", 0, Color.alpha(bitmap.getPixel(x, bitmap.height - 1)))
            }
            for (y in 0 until bitmap.height) {
                assertEquals("Black backdrop at left edge", 0, Color.alpha(bitmap.getPixel(0, y)))
                assertEquals("Black backdrop at right edge", 0, Color.alpha(bitmap.getPixel(bitmap.width - 1, y)))
            }
            assertNoDetachedOpaquePixels(bitmap)
        }
    }

    @Test fun phoneStaysSoftBlueWhileTheShellRemainsNeutralWhite() {
        val body = MascotSprites.load(context, R.drawable.droidance_mascot_body)
        val (red, green, blue) = averageOpaqueColor(body, RectF(.58f, .37f, .65f, .51f), "Phone back")
        assertTrue("Phone back should remain soft blue: $red, $green, $blue",
            blue - red in 35f..160f && green - red in 15f..70f)
        assertTrue("Phone back should retain a light, muted material: $red, $green, $blue",
            red in 100f..205f && blue in 185f..255f)
        assertNeutralWhite(body, RectF(.35f, .30f, .43f, .60f), "Torso")
        val head = MascotSprites.load(context, R.drawable.droidance_mascot_head)
        assertNeutralWhite(head, RectF(.37f, .31f, .63f, .40f), "Head shell")
    }

    @Test fun facePanelStaysDarkWithoutThePreviousBlueCast() {
        val head = MascotSprites.load(context, R.drawable.droidance_mascot_head)
        var blueExcess = 0L
        var brightness = 0L
        var count = 0
        for (y in (head.height * .56f).toInt() until (head.height * .77f).toInt()) {
            for (x in (head.width * .36f).toInt() until (head.width * .64f).toInt()) {
                val pixel = head.getPixel(x, y)
                assertEquals("Face panel stays opaque", 255, Color.alpha(pixel))
                blueExcess += Color.blue(pixel) - Color.red(pixel)
                brightness += maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                count++
            }
        }
        assertTrue("Face panel should stay dark", brightness.toFloat() / count < 100f)
        assertTrue("Face panel should be charcoal, with only subtle cool shading", blueExcess.toFloat() / count < 25f)
    }

    @Test fun antennaKeepsItsRoundBulbAndHasAShortStem() {
        val head = MascotSprites.load(context, R.drawable.droidance_mascot_head)
        // Read the actual silhouette through its center, independent of the painter's transforms.
        val widths = IntArray((head.height * .34f).toInt()) { y ->
            val center = head.width / 2
            if (Color.alpha(head.getPixel(center, y)) < 128) 0 else {
                var left = center
                var right = center
                while (left > 0 && Color.alpha(head.getPixel(left - 1, y)) >= 128) left--
                while (right + 1 < head.width && Color.alpha(head.getPixel(right + 1, y)) >= 128) right++
                right - left + 1
            }
        }
        val bulbRows = 0 until (head.height * .25f).toInt()
        val bulbWidth = bulbRows.maxOf { widths[it] }
        assertTrue("Keep the original bulb diameter: ${bulbWidth.toFloat() / head.width}",
            bulbWidth.toFloat() / head.width in 0.13f..0.17f)
        val bulbMiddle = bulbRows.first { widths[it] == bulbWidth }
        val narrowStart = (bulbMiddle until widths.size).firstOrNull {
            widths[it] > 0 && widths[it] < bulbWidth * .6f
        }
        assertNotNull("A visible short stem must remain below the bulb", narrowStart)
        val stemBottom = (narrowStart!! until widths.size).firstOrNull { widths[it] >= bulbWidth * .6f }
        assertNotNull("The stem must join the head shell", stemBottom)
        val narrowHeight = (stemBottom!! - narrowStart).toFloat() / head.height
        assertTrue("The narrow section should be short, not a tall antenna: $narrowHeight",
            narrowHeight in 0.01f..0.05f)
    }

    @Test fun pointerLoaderProducesTransparentBackgroundAtDisplaySize() {
        val hand = HandCursorBitmap.of(context, 108)
        assertTrue(hand.hasAlpha())
        assertEquals(0, Color.alpha(hand.getPixel(0, 0)))
        assertEquals(0, Color.alpha(hand.getPixel(107, 107)))
        assertEquals(255, Color.alpha(hand.getPixel(54, 54)))
    }

    @Test fun reopenedPreviewRestoresTheSettledPointerUntilExplicitClear() {
        instrumentation.runOnMainSync {
            val view = TouchIndicatorView(context)
            view.layout(0, 0, 300, 300)
            val now = CursorMotion.nowMs()
            view.showCue(VirtualSpaceHandCue("expired", 1, InputAction.Swipe(40, 40, 220, 220, 600), now - 5000, now - 4400))
            val frame = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(frame))
            val pixels = IntArray(300 * 300)
            frame.getPixels(pixels, 0, 300, 0, 0, 300, 300)
            assertTrue(pixels.any { Color.alpha(it) > 0 })
            assertEquals(0, Color.alpha(frame.getPixel(40, 40)))
            view.showCue(null)
            frame.eraseColor(Color.TRANSPARENT)
            view.draw(Canvas(frame))
            frame.getPixels(pixels, 0, 300, 0, 0, 300, 300)
            assertTrue(pixels.all { Color.alpha(it) == 0 })
        }
    }

    @Test fun greetingAndBlinkRenderDifferentFacesWithStableFraming() {
        val painter = MascotPainter(context)
        fun render(time: Long): Bitmap = Bitmap.createBitmap(180, 180, Bitmap.Config.ARGB_8888).apply {
            painter.draw(Canvas(this), RectF(0f, 0f, 180f, 180f), MascotFraming.HEAD,
                MascotMotion.pose(time, MascotMood.LISTENING))
        }
        assertFalse(render(1000).sameAs(render(2800)))
    }

    @Test fun exportNativeContactSheet() {
        val painter = MascotPainter(context)
        val sheet = Bitmap.createBitmap(1080, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(12, 12, 16))
        for ((row, framing) in MascotFraming.entries.withIndex()) {
            for ((col, mood) in listOf(MascotMood.WELCOME, MascotMood.THINKING, MascotMood.TAKEOVER).withIndex()) {
                val left = 40f + col * 350f
                val top = 20f + row * 320f
                painter.draw(canvas, RectF(left, top, left + 300f, top + 300f), framing,
                    MascotMotion.pose(2100, mood))
            }
        }
        File(context.getExternalFilesDir(null), "mascot-contact-sheet.png").outputStream().use {
            assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    @Test fun dancingKeepsTheWholeCharacterInsideItsFrame() {
        val painter = MascotPainter(context)
        val output = File(context.getExternalFilesDir(null), "mascot-dance").apply { mkdirs() }
        for (frame in 0 until 80) {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            painter.draw(Canvas(bitmap), RectF(0f, 0f, 256f, 256f), MascotFraming.FULL,
                MascotMotion.pose(frame * 100L, MascotMood.DANCING))
            for (p in 0 until 256) {
                assertEquals("Top edge at frame $frame", 0, Color.alpha(bitmap.getPixel(p, 0)))
                assertEquals("Bottom edge at frame $frame", 0, Color.alpha(bitmap.getPixel(p, 255)))
                assertEquals("Left edge at frame $frame", 0, Color.alpha(bitmap.getPixel(0, p)))
                assertEquals("Right edge at frame $frame", 0, Color.alpha(bitmap.getPixel(255, p)))
            }
            File(output, "frame-%03d.png".format(frame)).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun exportBothSurfacesAtDisplaySizes() {
        val sheet = Bitmap.createBitmap(840, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        val painter = MascotPainter(context)
        for ((column, background) in listOf(Color.rgb(250, 250, 252), Color.rgb(20, 19, 26)).withIndex()) {
            val backgroundPaint = android.graphics.Paint().apply { color = background }
            canvas.drawRect(column * 420f, 0f, (column + 1) * 420f, 640f, backgroundPaint)
            for ((row, size) in listOf(300f, 152f, 84f).withIndex()) {
                val left = column * 420f + (420f - size) / 2
                val top = listOf(20f, 340f, 520f)[row]
                painter.draw(canvas, RectF(left, top, left + size, top + size),
                    MascotFraming.FULL, MascotPose())
            }
        }
        File(context.getExternalFilesDir(null), "mascot-surface-comparison.png").outputStream().use {
            assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    @Test fun exportOperatingIslandsWithStableButtonsAndAnimatedDevice() {
        val renderer = com.droidance.ui.overlay.RuntimeSurfaceBitmapRenderer(context)
        val output = File(context.getExternalFilesDir(null), "operating-island").apply { mkdirs() }
        val frames = listOf(0f, 0.0625f).map { phase ->
            renderer.renderIsland("查看当前页面",
                com.droidance.ui.overlay.RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                false, com.droidance.ui.overlay.RuntimeIslandPresentation.State.EXECUTING, phase)
        }
        assertEquals(frames[0].widthPx, frames[1].widthPx)
        assertEquals(frames[0].stopButtonLeftPx, frames[1].stopButtonLeftPx)
        assertFalse(frames[0].png.contentEquals(frames[1].png))
        frames.forEachIndexed { index, frame -> File(output, "frame-$index.png").writeBytes(frame.png) }
    }

    private fun assertNeutralWhite(bitmap: Bitmap, region: RectF, label: String) {
        val channels = averageOpaqueColor(bitmap, region, label)
        assertTrue("$label should remain white: $channels", channels.min() > 185f)
        assertTrue("$label should not have a blue or purple cast: $channels", channels.max() - channels.min() < 8f)
    }

    private fun averageOpaqueColor(bitmap: Bitmap, region: RectF, label: String): List<Float> {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in (region.top * bitmap.height).toInt() until (region.bottom * bitmap.height).toInt()) {
            for (x in (region.left * bitmap.width).toInt() until (region.right * bitmap.width).toInt()) {
                val pixel = bitmap.getPixel(x, y)
                assertEquals("$label should remain opaque", 255, Color.alpha(pixel))
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        return listOf(red.toFloat() / count, green.toFloat() / count, blue.toFloat() / count)
    }

    private fun assertNoDetachedOpaquePixels(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var start = 0
        var end = 1
        val seed = bitmap.height / 2 * bitmap.width + bitmap.width / 2
        queue[0] = seed
        visited[seed] = true
        while (start < end) {
            val index = queue[start++]
            val x = index % bitmap.width
            val y = index / bitmap.width
            for (dy in -1..1) for (dx in -1..1) {
                val nextX = x + dx
                val nextY = y + dy
                if (nextX !in 0 until bitmap.width || nextY !in 0 until bitmap.height) continue
                val next = nextY * bitmap.width + nextX
                if (!visited[next] && Color.alpha(pixels[next]) >= 32) {
                    visited[next] = true
                    queue[end++] = next
                }
            }
        }
        assertEquals("No detached bright specks may remain after backdrop removal", 0,
            pixels.indices.count { !visited[it] && Color.alpha(pixels[it]) >= 192 })
    }
}
