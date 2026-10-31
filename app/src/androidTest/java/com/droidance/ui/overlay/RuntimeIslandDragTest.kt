// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.DroidanceApp
import com.droidance.MainActivity
import com.droidance.core.agent.AgentState
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sends real display input while the host Activity is in the background. No model is invoked. */
class RuntimeIslandDragTest {
    @Test fun backgroundIslandTracksBothAxesWhileItsAnimationIsRunning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<DroidanceApp>()
        val controller = field<RuntimeSurfaceController>(app.agentStatusOverlay, "runtimeSurfaceController")
        val previousX = RuntimeIslandPosition.readX(app)
        val previousY = RuntimeIslandPosition.read(app)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertEquals("Requires an idle device", AgentState.IDLE, it.agentRuntime.state.value) }
            try {
                RuntimeIslandPosition.write(app, 0, 0)
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SystemClock.sleep(1_200L)
                assertFalse(app.foregroundState.isInForeground)
                controller.showIndicator("Drag regression", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                    false, RuntimeIslandPresentation.State.EXECUTING)
                await { field<Any?>(controller, "inputSurface") != null }
                val draws = AtomicInteger()
                val drawnLabel = AtomicReference("")
                val listening = CountDownLatch(1)
                val island = field<RuntimeIslandSurfaceView>(controller, "inputIslandView")
                await { island.isAttachedToWindow && island.handler != null }
                island.handler.post {
                    island.viewTreeObserver.addOnDrawListener(ViewTreeObserver.OnDrawListener {
                        draws.incrementAndGet()
                        drawnLabel.set(island.contentDescription.toString())
                    })
                    listening.countDown()
                }
                assertTrue(listening.await(3, TimeUnit.SECONDS))
                SystemClock.sleep(800L)
                assertTrue("The background island must draw continuously, not six frames per second", draws.get() >= 16)
                controller.showIndicator("Updated while on desktop", RuntimeSurfaceBitmapRenderer.IslandAction.OPEN,
                    false, RuntimeIslandPresentation.State.EXECUTING)
                await { drawnLabel.get() == "Updated while on desktop" }
                val width = field<Int>(controller, "inputWidthPx")
                val height = field<Int>(controller, "inputHeightPx")
                val baseTop = field<Int>(controller, "lastIslandBaseTopPx")
                val startOffsetX = field<Int>(controller, "islandDragOffsetX")
                val startOffsetY = field<Int>(controller, "islandDragOffsetY")
                val x = (app.resources.displayMetrics.widthPixels - width) / 2f + startOffsetX + width / 3f
                val y = baseTop + startOffsetY + height / 2f
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, dx: Float, dy: Float): Long {
                    val now = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(down, now, action, x + dx, y + dy, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                    return SystemClock.uptimeMillis() - now
                }
                send(MotionEvent.ACTION_DOWN, 0f, 0f)
                val beforeDrag = draws.get()
                val delays = mutableListOf<Long>()
                for (step in 1..40) {
                    delays += send(MotionEvent.ACTION_MOVE, step * 2f, step * 4f)
                    SystemClock.sleep(8L)
                }
                send(MotionEvent.ACTION_UP, 80f, 160f)
                assertTrue("Drawing must continue while dragging", draws.get() - beforeDrag >= 4)
                await { field<Long>(controller, "inputDownAtMs") == 0L }
                assertEquals(startOffsetX + 80, field<Int>(controller, "islandDragOffsetX"))
                assertEquals(startOffsetY + 160, field<Int>(controller, "islandDragOffsetY"))
                android.util.Log.i("RuntimeIslandDragTest", "MOVE injection latency: $delays")
                assertTrue("MOVE stalled: ${delays.max()}", delays.sorted()[delays.size * 95 / 100] < 80L)
            } finally {
                controller.hideAll()
                await { field<Any?>(controller, "inputSurface") == null }
                RuntimeIslandPosition.write(app, previousX, previousY)
            }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20L)
        assertTrue("Runtime island condition timed out", predicate())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
}
