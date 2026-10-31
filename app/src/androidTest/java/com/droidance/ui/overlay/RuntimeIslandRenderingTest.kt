// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.content.res.Configuration
import java.util.Locale
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.ui.mascot.MascotFraming
import com.droidance.ui.mascot.MascotMood
import com.droidance.ui.mascot.MascotMotion
import com.droidance.ui.mascot.MascotPainter
import com.droidance.config.AppLanguage
import com.droidance.config.LanguageManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RuntimeIslandRenderingTest {
    @Test fun cachedSurfaceReusesItsStaticImageAndRefreshesChangedTitles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val view = RuntimeIslandSurfaceView(context)
            val renderer = RuntimeSurfaceBitmapRenderer(context)
            val layout = renderer.islandLayout()
            view.layout(0, 0, layout.widthPx, layout.heightPx)
            fun present(label: String) = view.present(label, RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                true, RuntimeIslandPresentation.State.USER_CONTROL)
            fun cached() = RuntimeIslandSurfaceView::class.java.getDeclaredField("staticLayer")
                .apply { isAccessible = true }.get(view) as Bitmap
            present("First title")
            val first = cached()
            val image = Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888)
            repeat(3) {
                image.eraseColor(Color.TRANSPARENT)
                view.draw(Canvas(image))
            }
            org.junit.Assert.assertSame("Drawing must not rebuild static pixels", first, cached())
            present("First title")
            org.junit.Assert.assertSame(first, cached())
            present("Updated title")
            org.junit.Assert.assertNotSame(first, cached())
            image.eraseColor(Color.TRANSPARENT)
            view.draw(Canvas(image))
            val expected = decode(renderer.renderIsland("Updated title", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                true, RuntimeIslandPresentation.State.USER_CONTROL, 0f))
            assertTrue("Cached and exported islands must look identical", expected.sameAs(image))
            image.recycle()
            expected.recycle()
        }
    }

    @Test fun splitLayersMatchCombinedRenderingAcrossExecutionAndHandoffStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = RuntimeSurfaceBitmapRenderer(context)
        val layout = renderer.islandLayout()
        val motionEnabled = RuntimeIslandAnimations.areEnabled(context)
        for (state in RuntimeIslandPresentation.State.entries) {
            val paused = state == RuntimeIslandPresentation.State.USER_CONTROL
            val executing = state == RuntimeIslandPresentation.State.EXECUTING && !paused
            val phase = .125f
            val expected = decode(renderer.renderIsland("Task title", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                paused, state, phase))
            val actual = Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(actual)
            renderer.drawIslandStatic(canvas, layout, "Task title", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN, paused)
            renderer.drawIslandActivity(canvas, layout, RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                executing, motionEnabled, if (motionEnabled) (phase * MascotMotion.CYCLE_MS).toLong() else 0L)
            assertTrue("Layer split changed $state", expected.sameAs(actual))
            expected.recycle()
            actual.recycle()
        }
    }

    @Test fun titleOnlyRendererStaysIdenticalWhenApplicationLanguageChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val language = LanguageManager(context)
        val previous = language.getPreference()
        try {
            val renderer = RuntimeSurfaceBitmapRenderer(context)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { language.setPreference(AppLanguage.ENGLISH) }
            val english = render(renderer, "Task", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { language.setPreference(AppLanguage.SIMPLIFIED_CHINESE) }
            val chinese = render(renderer, "Task", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN)
            assertTrue("No localized execution subtitle is drawn", english.png.contentEquals(chinese.png))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { language.setPreference(previous) }
        }
    }
    @Test fun executionAnimatesMascotAndOrbitWhileTitleAndHandoffsStayStill() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = RuntimeSurfaceBitmapRenderer(context)
        val density = context.resources.displayMetrics.density
        val first = decode(renderer.renderIsland("执行测试", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
            false, RuntimeIslandPresentation.State.EXECUTING, .125f))
        val next = decode(renderer.renderIsland("执行测试", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
            false, RuntimeIslandPresentation.State.EXECUTING, .2f))
        val padding = (12f * density).toInt()
        val mascotEnd = padding + ((RuntimeIslandStyle.LABEL_INSET_DP + RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP) * density).toInt()
        val controlLeft = padding + ((RuntimeIslandStyle.WIDTH_DP - RuntimeIslandStyle.EDGE_PADDING_DP -
            2 * RuntimeIslandStyle.CHIP_SIZE_DP - RuntimeIslandStyle.CHIP_GAP_DP) * density).toInt()
        fun changed(left: Int, right: Int) = (left until right).any { x ->
            (padding until first.height - padding).any { y -> first.getPixel(x, y) != next.getPixel(x, y) }
        }
        assertTrue("The execution mascot dances", changed(padding, mascotEnd))
        assertFalse("The title slot has no animated subtitle dots", changed(mascotEnd, controlLeft - (RuntimeIslandStyle.CHIP_GAP_DP * density).toInt()))
        assertTrue("The pause orbit rotates", changed(controlLeft - (3 * density).toInt(), controlLeft + ((RuntimeIslandStyle.CHIP_SIZE_DP + 4f) * density).toInt()))
        first.recycle()
        next.recycle()
        for (state in RuntimeIslandPresentation.State.entries.filter { it != RuntimeIslandPresentation.State.EXECUTING }) {
            val frames = listOf(.125f, .2f).map { phase ->
                renderer.renderIsland(RuntimeIslandPresentation.label(context, state),
                    RuntimeSurfaceBitmapRenderer.IslandAction.OPEN, state == RuntimeIslandPresentation.State.USER_CONTROL,
                    state, phase)
            }
            assertTrue("Waiting states stay still: $state", frames[0].png.contentEquals(frames[1].png))
        }
        File(context.getExternalFilesDir(null), "runtime-island-executing.png").writeBytes(
            renderer.renderIsland("执行测试", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                false, RuntimeIslandPresentation.State.EXECUTING, .125f).png)
    }
    @Test fun toggleArrowsStayOnOppositeSidesOfTheMascotInBothStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sheet = Bitmap.createBitmap(804, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(25, 25, 30))
        for ((row, expanded) in listOf(false, true).withIndex()) {
            val arrows = Bitmap.createBitmap(240, 144, Bitmap.Config.ARGB_8888)
            RuntimeIslandStyle.SpaceTogglePainter().draw(Canvas(arrows), 3f, 72f, expanded)
            val firstPixel = (0 until arrows.width).first { x ->
                (0 until arrows.height).any { y -> Color.alpha(arrows.getPixel(x, y)) > 0 }
            }
            assertTrue("Toggle arrows need at least the button-side inset on the left",
                firstPixel >= (RuntimeIslandStyle.EDGE_PADDING_DP * 3f).toInt())
            val center = (RuntimeIslandStyle.MASCOT_LEFT_DP +
                (RuntimeIslandStyle.MASCOT_SIZE_DP + RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP) / 2f) * 3f
            val offset = RuntimeIslandStyle.SPACE_TOGGLE_ARROW_OFFSET_DP * 3f
            val half = (3f * RuntimeIslandStyle.SCALE * 3f).toInt()
            val leftCenter = (center - offset).toInt()
            val rightCenter = (center + offset).toInt()
            for (x in leftCenter + half until rightCenter - half) for (y in 0 until 144)
                assertEquals(0, Color.alpha(arrows.getPixel(x, y)))
            for (arrowCenter in listOf(leftCenter, rightCenter)) {
                assertTrue((arrowCenter - half..arrowCenter + half).any { x ->
                    (0 until 144).any { y -> Color.alpha(arrows.getPixel(x, y)) > 0 } })
            }
            canvas.save()
            canvas.translate(0f, row * 150f)
            canvas.drawBitmap(arrows, 0f, 0f, null)
            MascotPainter(context).draw(canvas, RectF(33f, 18f, 141f, 126f), MascotFraming.FULL,
                MascotMotion.pose(2100, MascotMood.DANCING))
            canvas.restore()
            arrows.recycle()
        }
        File(context.getExternalFilesDir(null), "runtime-island-toggle-stages.png").outputStream().use {
            assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val island = RuntimeSurfaceBitmapRenderer(context).renderIsland("打开应用",
            RuntimeSurfaceBitmapRenderer.IslandAction.OPEN, false, RuntimeIslandPresentation.State.EXECUTING, 0.25f)
        val rendered = BitmapFactory.decodeByteArray(island.png, 0, island.png.size)
        assertEquals(island.widthPx, rendered.width)
        File(context.getExternalFilesDir(null), "runtime-island-preview.png").writeBytes(island.png)
        rendered.recycle()
        sheet.recycle()
    }

    @Test fun staticLabelsStayInsideTheirSlotsWithoutMovingButtons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = RuntimeSurfaceBitmapRenderer(context)
        for (kind in listOf(RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
            RuntimeSurfaceBitmapRenderer.IslandAction.TAKEOVER, RuntimeSurfaceBitmapRenderer.IslandAction.RESUME)) {
            val empty = render(renderer, "", kind)
            val title = render(renderer, "正在比较商品的价格、评价以及不同选项的详细信息", kind)
            assertTrue(title.png.contentEquals(render(renderer,
                "正在比较商品的价格、评价以及不同选项的详细信息", kind).png))
            assertEquals(empty.stopButtonLeftPx, title.stopButtonLeftPx)
            val first = decode(empty)
            val second = decode(title)
            val density = context.resources.displayMetrics.density
            val left = (12f * density).toInt() + ((RuntimeIslandStyle.LABEL_INSET_DP +
                if (kind == RuntimeSurfaceBitmapRenderer.IslandAction.OPEN) RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP else 0f) * density).toInt()
            val actionLeft = title.stopButtonLeftPx -
                ((RuntimeIslandStyle.CHIP_SIZE_DP + RuntimeIslandStyle.CHIP_GAP_DP) * density).toInt()
            val right = actionLeft - (RuntimeIslandStyle.CHIP_GAP_DP * density).toInt()
            var labelPixels = 0
            for (y in 0 until first.height) for (x in 0 until first.width) {
                if (x < left || x >= right) {
                    assertEquals("Label leaked outside its slot at $x,$y", first.getPixel(x, y), second.getPixel(x, y))
                } else if (first.getPixel(x, y) != second.getPixel(x, y)) labelPixels++
            }
            assertTrue("Label must remain visible", labelPixels > 0)
            first.recycle()
            second.recycle()
        }
    }

    @Test fun viewerAndBitmapUseIdenticalStaticLabelsInBothLocalesAndLargeFonts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        for (language in listOf("zh", "en")) for (scale in listOf(1f, 1.6f)) {
            val context = localized(base, language, scale)
            val action = if (language == "zh") "外卖搜索" else "Food search"
            val island = render(RuntimeSurfaceBitmapRenderer(context), action,
                RuntimeSurfaceBitmapRenderer.IslandAction.OPEN)
            val bitmap = decode(island)
            val density = context.resources.displayMetrics.density
            val left = (12f * density).toInt() + ((RuntimeIslandStyle.LABEL_INSET_DP +
                RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP) * density).toInt()
            // Android rounds each layout dimension separately at fractional display densities.
            val right = island.stopButtonLeftPx - (RuntimeIslandStyle.CHIP_SIZE_DP * density).toInt() -
                2 * (RuntimeIslandStyle.CHIP_GAP_DP * density).toInt()
            val top = (12f * density).toInt()
            val height = RuntimeIslandTextPainter(context).heightPx()
            val native = Bitmap.createBitmap(right - left, height, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync {
                val label = RuntimeIslandLabelView(context)
                label.setBackgroundColor(RuntimeIslandStyle.fillColor())
                label.setLabel(action, executing = true)
                assertEquals(action, label.contentDescription)
                label.layout(0, 0, native.width, native.height)
                label.draw(Canvas(native))
            }
            for (y in (2 * density).toInt() until height - (2 * density).toInt()) {
                for (x in 0 until native.width) assertEquals(bitmap.getPixel(left + x, top + y), native.getPixel(x, y))
            }
            File(base.getExternalFilesDir(null), "runtime-island-$language-$scale.png").writeBytes(island.png)
            bitmap.recycle()
            native.recycle()
        }
    }

    @Test fun longTextAndLargeFontsAreClippedEvenInANarrowSlot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = localized(instrumentation.targetContext, "en", 1.6f)
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        val slot = RectF(35f, 10f, 300f, 290f)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.MAGENTA)
        RuntimeIslandTextPainter(context).draw(canvas, slot, "Reviewing all requested information")
        var changed = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (!slot.contains(x.toFloat(), y.toFloat())) assertEquals(Color.MAGENTA, bitmap.getPixel(x, y))
            else if (bitmap.getPixel(x, y) != Color.MAGENTA) changed++
        }
        assertTrue(changed > 0)
        bitmap.recycle()
    }

    @Test fun requestedAndActiveTakeoverHaveDistinctLocalizedLabels() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for ((language, expected) in listOf("zh" to listOf("请接管", "已接管", "待授权"),
            "en" to listOf("Your turn", "Manual", "Authorize"))) {
            val context = localized(base, language, 1f)
            val states = listOf(RuntimeIslandPresentation.State.WAITING_FOR_USER,
                RuntimeIslandPresentation.State.USER_CONTROL, RuntimeIslandPresentation.State.WAITING_PERMISSION)
            assertEquals(expected, states.map { RuntimeIslandPresentation.label(context, it, "previous task label") })
            assertEquals(if (language == "zh") "执行中" else "Running",
                RuntimeIslandPresentation.label(context, RuntimeIslandPresentation.State.EXECUTING))
            assertEquals("外卖搜索", RuntimeIslandPresentation.label(context,
                RuntimeIslandPresentation.State.EXECUTING, "外卖搜索"))
        }
    }

    @Test fun changingATitleImmediatelyMatchesAFreshLabel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val height = RuntimeIslandTextPainter(context).heightPx()
        val changed = Bitmap.createBitmap(120, height, Bitmap.Config.ARGB_8888)
        val fresh = Bitmap.createBitmap(120, height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync {
            val label = RuntimeIslandLabelView(context)
            label.layout(0, 0, 120, height)
            label.setLabel("查看页面", executing = true)
            label.setLabel("整理结果", executing = true)
            label.draw(Canvas(changed))
            RuntimeIslandLabelView(context).apply {
                layout(0, 0, 120, height)
                setLabel("整理结果", executing = true)
                draw(Canvas(fresh))
            }
        }
        assertTrue(changed.sameAs(fresh))
        changed.recycle()
        fresh.recycle()
    }

    private fun localized(base: android.content.Context, language: String, scale: Float) =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
            fontScale = scale
        })

    private fun render(renderer: RuntimeSurfaceBitmapRenderer, action: String,
        kind: RuntimeSurfaceBitmapRenderer.IslandAction) = renderer.renderIsland(action, kind,
        kind == RuntimeSurfaceBitmapRenderer.IslandAction.RESUME,
        if (kind == RuntimeSurfaceBitmapRenderer.IslandAction.RESUME) RuntimeIslandPresentation.State.USER_CONTROL
        else RuntimeIslandPresentation.State.EXECUTING, phase = 0f)

    private fun decode(island: RuntimeSurfaceBitmapRenderer.IslandBitmap): Bitmap =
        BitmapFactory.decodeByteArray(island.png, 0, island.png.size)
}
