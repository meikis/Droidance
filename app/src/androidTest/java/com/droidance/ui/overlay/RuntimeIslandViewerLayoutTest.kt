// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.MainActivity
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.core.agent.TurnJobs
import com.droidance.agent.RuntimeCapabilityRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import com.droidance.agent.device.VirtualSpaceManager
import com.droidance.agent.device.VirtualSpaceViewerActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production viewer on an idle device, without invoking a model or target app. */
class RuntimeIslandViewerLayoutTest {
    @Test fun stopCancelsExecutingTaskAndClosesViewer() = assertViewerStop(paused = false)

    @Test fun stopCancelsPausedTaskAndClosesViewer() = assertViewerStop(paused = true)

    private fun assertViewerStop(paused: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            lateinit var runtime: AgentRuntime
            lateinit var manager: VirtualSpaceManager
            host.onActivity {
                runtime = it.agentRuntime
                manager = field(runtime, "virtualSpaceManager")
            }
            assertEquals("Requires an idle device", AgentState.IDLE, runtime.state.value)
            val previousBinding = manager.activeBinding()
            val registry = field<RuntimeCapabilityRegistry>(manager, "capabilityRegistry")
            val mode = previousBinding?.runtimeMode ?: runBlocking {
                registry.awaitTaskStrategy().selection.effectiveMode
            }
            val jobs = field<TurnJobs>(runtime, "turnJobs")
            val job = Job()
            val taskId = "viewer-stop-regression"
            try {
                assertNotNull(manager.lastFailureReason(), manager.start(mode))
                val binding = manager.activeBinding()
                ActivityScenario.launch(VirtualSpaceViewerActivity::class.java).use { scenario ->
                    lateinit var viewer: VirtualSpaceViewerActivity
                    val stopBounds = Rect()
                    scenario.onActivity {
                        viewer = it
                        jobs.begin(taskId, job)
                        field<MutableStateFlow<AgentState>>(runtime, "_state").value = AgentState.EXECUTING_TOOL
                        field<MutableStateFlow<Boolean>>(runtime, "_isMobileUseTaskActive").value = true
                        field<MutableStateFlow<String>>(runtime, "_currentTaskTitle").value = "Stop regression"
                        if (paused) assertTrue(runtime.beginManualTakeoverFromOverlay())
                        refresh(it, paused)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        val island = field<View>(it, "islandView")
                        val stop = field<ImageButton>(island, "stopView")
                        assertTrue(stop.getGlobalVisibleRect(stopBounds))
                    }
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                            stopBounds.exactCenterX(), stopBounds.exactCenterY(), 0)
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                        finally { event.recycle() }
                    }
                    val deadline = SystemClock.uptimeMillis() + 5_000L
                    while ((!job.isCancelled || !viewer.isFinishing) && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(20L)
                    }
                    assertTrue("Stop must cancel the running task", job.isCancelled)
                    assertTrue("Explicit stop must leave the viewer", viewer.isFinishing)
                    assertEquals(AgentState.IDLE, runtime.state.value)
                    assertFalse(runtime.isMobileUseTaskActive.value)
                    assertFalse(runtime.isPaused.value)
                    assertEquals("Stopping must retain the display for later review", binding, manager.activeBinding())
                }
            } finally {
                job.cancel()
                jobs.release(taskId, job)
                runtime.cancelCurrentTask(origin = "viewer_stop_regression_cleanup")
                field<MutableStateFlow<Boolean>>(runtime, "_isMobileUseTaskActive").value = false
                if (previousBinding == null) manager.stop()
            }
        }
    }

    @Test fun taskTitleStaysFixedAndControlIslandStaysInsideTheSafeScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var runtime: AgentRuntime
        lateinit var manager: VirtualSpaceManager
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            host.onActivity { activity ->
                runtime = activity.agentRuntime
                manager = field(runtime, "virtualSpaceManager")
            }
            assertEquals("This layout test requires an idle device", AgentState.IDLE, runtime.state.value)
            assertFalse(runtime.isPaused.value)
            val title = field<MutableStateFlow<String>>(runtime, "_currentTaskTitle")
            val action = field<MutableStateFlow<String>>(runtime, "_currentAction")
            val active = field<MutableStateFlow<Boolean>>(runtime, "_isMobileUseTaskActive")
            val previousTitle = title.value
            val previousAction = action.value
            val previouslyActive = active.value
            val previousBinding = manager.activeBinding()
            try {
                title.value = "外卖搜索"
                active.value = true
                val registry = field<RuntimeCapabilityRegistry>(manager, "capabilityRegistry")
                val mode = previousBinding?.runtimeMode ?: runBlocking {
                    registry.awaitTaskStrategy().selection.effectiveMode
                }
                val display = manager.start(mode)
                assertNotNull(manager.lastFailureReason(), display)
                ActivityScenario.launch(VirtualSpaceViewerActivity::class.java).use { scenario ->
                    instrumentation.waitForIdleSync()
                    var runningLabelWidth = 0
                    scenario.onActivity { activity ->
                        val label = viewerLabel(activity)
                        runningLabelWidth = label.width
                        assertEquals("外卖搜索", label.contentDescription)
                    }
                    for (description in listOf("点击", "执行一组动作", "滑动页面")) {
                        action.value = description
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            assertEquals("外卖搜索", viewerLabel(activity).contentDescription)
                            assertEquals(runningLabelWidth, viewerLabel(activity).width)
                        }
                    }
                    capture("runtime-viewer-fixed-task-title.png")
                    scenario.onActivity { activity ->
                        assertTrue(runtime.beginManualTakeoverFromOverlay())
                        refresh(activity, true)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val label = viewerLabel(activity)
                        assertEquals(activity.getString(com.droidance.R.string.runtime_island_user_control), label.contentDescription)
                        assertEquals(runningLabelWidth, label.width)
                        assertTrue(label.width > 0)
                    }
                    capture("runtime-viewer-manual-island.png")
                    scenario.onActivity { activity ->
                        for (offset in listOf(-100_000, 100_000)) {
                            VirtualSpaceViewerActivity::class.java.getDeclaredMethod("applyIslandOffset", Int::class.javaPrimitiveType)
                                .apply { isAccessible = true }.invoke(activity, offset)
                            assertControlsInsideSafeScreen(activity)
                        }
                    }
                    scenario.onActivity { activity ->
                        assertTrue(runtime.resumeManualTakeover())
                        refresh(activity, false)
                        assertEquals(runningLabelWidth, viewerLabel(activity).width)
                        assertEquals("外卖搜索", viewerLabel(activity).contentDescription)
                    }
                }
            } finally {
                if (runtime.isPaused.value) runtime.resumeManualTakeover()
                if (previousBinding == null) manager.stop()
                title.value = previousTitle
                action.value = previousAction
                active.value = previouslyActive
            }
        }
    }

    private fun viewerLabel(activity: VirtualSpaceViewerActivity): RuntimeIslandLabelView =
        field(field<View>(activity, "islandView"), "statusView")

    private fun refresh(activity: VirtualSpaceViewerActivity, manual: Boolean) {
        VirtualSpaceViewerActivity::class.java.getDeclaredMethod("refreshControlState", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, manual)
    }

    private fun assertControlsInsideSafeScreen(activity: VirtualSpaceViewerActivity) {
        val root = field<View>(activity, "rootContainer")
        val top = field<Int>(activity, "currentTopInsetPx")
        val bottom = root.height - field<Int>(activity, "currentBottomInsetPx")
        val island = field<View>(activity, "islandView")
        assertTrue("Control above safe area: ${island.y} < $top", island.y >= top - 1f)
        assertTrue("Control below safe area: ${island.y + island.height} > $bottom", island.y + island.height <= bottom + 1f)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
}
