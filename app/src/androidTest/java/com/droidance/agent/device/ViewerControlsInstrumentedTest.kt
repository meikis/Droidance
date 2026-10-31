// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.ImageButton
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.R
import com.droidance.debug.MascotPreviewActivity
import com.droidance.ui.overlay.RuntimeIslandTextPainter
import com.droidance.ui.overlay.RuntimeIslandPresentation
import com.droidance.ui.overlay.RuntimeIslandStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ViewerControlsInstrumentedTest {
    @Test fun horizontalAndDiagonalDragsCancelButtonClicksAndCommitTheUpPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            var clicks = 0
            var ended = 0
            val moves = mutableListOf<Pair<Float, Float>>()
            val island = ViewerControlIslandView(context, { clicks++ }, { clicks++ }, { clicks++ },
                { _, _ -> }, { x, y -> moves += x to y }, { ended++ })
            val width = (RuntimeIslandStyle.WIDTH_DP * density).toInt()
            val height = RuntimeIslandTextPainter(context).heightPx()
            island.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            island.layout(0, 0, width, height)
            val x = width - RuntimeIslandStyle.EDGE_PADDING_DP * density -
                RuntimeIslandStyle.CHIP_SIZE_DP * density / 2f
            val y = height / 2f
            fun gesture(dx: Float, dy: Float) {
                moves.clear()
                val down = SystemClock.uptimeMillis()
                for ((action, delta) in listOf(MotionEvent.ACTION_DOWN to (0f to 0f),
                    MotionEvent.ACTION_MOVE to (dx to dy),
                    MotionEvent.ACTION_UP to (dx + 5f to dy + 7f))) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        x + delta.first, y + delta.second, 0)
                    try { assertTrue(island.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                assertEquals(x + dx + 5f to y + dy + 7f, moves.last())
            }
            gesture(-100f, 0f)
            gesture(-100f, -100f)
            assertEquals(0, clicks)
            assertEquals(2, ended)
        }
    }

    @Test fun executionKeepsAnimatingAfterFourSecondsWithoutActivityRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MascotPreviewActivity::class.java).use { scenario ->
            lateinit var island: ViewerControlIslandView
            scenario.onActivity { activity ->
                island = ViewerControlIslandView(activity, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
                island.render("执行测试", false, false, RuntimeIslandPresentation.State.EXECUTING)
                val width = (RuntimeIslandStyle.WIDTH_DP * activity.resources.displayMetrics.density).toInt()
                activity.addContentView(island, ViewGroup.LayoutParams(width, RuntimeIslandTextPainter(activity).heightPx()))
            }
            SystemClock.sleep(4_400L)
            lateinit var first: Bitmap
            scenario.onActivity {
                assertTrue(island.isShown)
                first = capture(island)
                File(it.getExternalFilesDir(null), "runtime-viewer-executing.png").outputStream().use { out ->
                    assertTrue(first.compress(Bitmap.CompressFormat.PNG, 100, out))
                }
            }
            SystemClock.sleep(180L)
            scenario.onActivity {
                val next = capture(island)
                assertFalse("Execution keeps animating after four seconds", first.sameAs(next))
                first.recycle()
                next.recycle()
                island.render("已接管", true, false, RuntimeIslandPresentation.State.USER_CONTROL)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { first = capture(island) }
            SystemClock.sleep(180L)
            scenario.onActivity {
                val next = capture(island)
                assertTrue("Manual takeover stops execution cues", first.sameAs(next))
                first.recycle()
                next.recycle()
            }
        }
    }

    private fun capture(island: View): Bitmap =
        Bitmap.createBitmap(island.width, island.height, Bitmap.Config.ARGB_8888).also {
            island.draw(Canvas(it))
        }

    @Test
    fun islandRendersLocalizedControlStateAndDispatchesOnlyHostCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            var toggles = 0
            var stops = 0
            val island = ViewerControlIslandView(context, {}, { toggles++ }, { stops++ }, { _, _ -> }, { _, _ -> }, {})
            island.render("Current task", manual = false, teaching = false, state = RuntimeIslandPresentation.State.EXECUTING)
            val buttons = descendants(island).filterIsInstance<ImageButton>().toList()
            val toggle = buttons.first { it.contentDescription == context.getString(R.string.takeover_start_control) }
            val stop = buttons.first { it.contentDescription == context.getString(R.string.common_stop) }
            val size = (context.resources.displayMetrics.density * RuntimeIslandStyle.CHIP_SIZE_DP).toInt()
            buttons.forEach {
                assertEquals(size, it.layoutParams.width)
                assertEquals(size, it.layoutParams.height)
            }
            toggle.performClick()
            stop.performClick()
            assertEquals(1, toggles)
            assertEquals(1, stops)
            island.render("Current task", manual = true, teaching = false, state = RuntimeIslandPresentation.State.USER_CONTROL)
            assertEquals(context.getString(R.string.takeover_return_control), toggle.contentDescription)
            island.render("Current task", manual = true, teaching = true, state = RuntimeIslandPresentation.State.DEMONSTRATING)
            assertEquals(View.GONE, toggle.visibility)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
