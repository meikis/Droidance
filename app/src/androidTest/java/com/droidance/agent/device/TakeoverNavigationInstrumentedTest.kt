// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.MainActivity
import com.droidance.R
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.debug.TakeoverNavigationProbeActivity
import com.droidance.debug.VirtualSessionProbeAccess
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual system edge gestures and production takeover routing, without a model request. */
@RunWith(AndroidJUnit4::class)
class TakeoverNavigationInstrumentedTest {
    @Test fun bothSystemEdgesReturnInsideTheAppAndCollapseRetainsItsDisplay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val access = EntryPointAccessors.fromApplication(context, VirtualSessionProbeAccess::class.java)
        val store = access.executionContextStore()
        assertNull("A user execution must not be interrupted", store.current())
        val report = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            lateinit var runtime: AgentRuntime
            host.onActivity { runtime = it.agentRuntime }
            assertEquals("This regression requires an idle device", AgentState.IDLE, runtime.state.value)
            assertFalse(runtime.isPaused.value)
            withContext(Dispatchers.IO) {
                val pairing = access.airModePairingManager()
                val bridge = access.sidecarBridge()
                if (!pairing.isShellReady && !bridge.isResidentBinderAlive()) pairing.reconnect()
                withTimeout(30_000) { while (!pairing.isShellReady && !bridge.isResidentBinderAlive()) delay(200) }
                access.capabilityRegistry().refresh(force = true)
                val ready = access.virtualSpaceReadiness().ensureReadyResult()
                assertTrue(ready.message, ready.ready)
            }
            val manager = access.virtualSpaceManager()
            val binding = checkNotNull(manager.activeBinding())
            val strategy = DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
                ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE))
            val execution = ExecutionContext.fromStrategy("takeover-navigation-verification", binding.backendSessionId,
                strategy, binding.displayId)
            store.start(execution)
            val automation = instrumentation.uiAutomation
            automation.adoptShellPermissionIdentity()
            try {
                val probeIntent = Intent(context, TakeoverNavigationProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                ActivityScenario.launch<TakeoverNavigationProbeActivity>(probeIntent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(binding.displayId).toBundle()).use { source ->
                    assertTrue(runtime.beginManualTakeoverFromOverlay())
                    val viewerIntent = Intent(context, VirtualSpaceViewerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ActivityScenario.launch<VirtualSpaceViewerActivity>(viewerIntent,
                        ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()).use { viewer ->
                        withTimeout(10_000) {
                            var attached = false
                            while (!attached) {
                                viewer.onActivity { attached = field<Any?>(it, "displayedBinding") == binding }
                                if (!attached) delay(50)
                            }
                        }
                        suspend fun awaitBack(expected: Int) {
                            withTimeout(5_000) {
                                var received = false
                                while (!received) {
                                    source.onActivity { received = it.backCount == expected }
                                    if (!received) delay(25)
                                }
                            }
                            assertEquals(binding, manager.activeBinding())
                            source.onActivity {
                                assertEquals(binding.displayId, it.display?.displayId)
                                assertEquals(3 - expected, it.pageDepth)
                            }
                            viewer.onActivity { assertFalse(it.isFinishing) }
                        }
                        val image = checkNotNull(automation.takeScreenshot())
                        val screenWidth = image.width
                        val screenHeight = image.height
                        image.recycle()
                        for ((index, fromLeft) in listOf(true, false).withIndex()) {
                            val startX = if (fromLeft) 1f else screenWidth - 2f
                            val endX = if (fromLeft) screenWidth * 0.35f else screenWidth * 0.65f
                            val downAt = SystemClock.uptimeMillis()
                            fun inject(action: Int, x: Float) {
                                val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action,
                                    x, screenHeight * 0.5f, 0).apply { this.source = InputDevice.SOURCE_TOUCHSCREEN }
                                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                            }
                            inject(MotionEvent.ACTION_DOWN, startX)
                            for (step in 1..16) {
                                delay(20)
                                inject(MotionEvent.ACTION_MOVE, startX + (endX - startX) * step / 16)
                            }
                            inject(MotionEvent.ACTION_UP, endX)
                            awaitBack(index + 1)
                            report += "${if (fromLeft) "left" else "right"}_edge: app_back_received=true viewer_open=true display=${binding.displayId}"
                        }
                        capture(context, "takeover-navigation-after-both-edges.png")
                        viewer.onActivity {
                            val island = field<ViewGroup>(it, "islandView")
                            val collapse = (0 until island.childCount).map(island::getChildAt)
                                .single { view -> view.contentDescription == it.getString(R.string.takeover_close_viewer) }
                            assertTrue(collapse.performClick())
                        }
                        withTimeout(5_000) { while (viewer.state != androidx.lifecycle.Lifecycle.State.DESTROYED) delay(25) }
                        assertEquals(binding, manager.activeBinding())
                        source.onActivity { assertEquals(binding.displayId, it.display?.displayId) }
                        assertTrue(runtime.isPaused.value)
                        assertTrue(runtime.resumeManualTakeover())
                        assertEquals(binding, manager.activeBinding())
                        report += "collapse_and_resume: viewer_closed=true same_display=true manual_resume=true"
                    }
                }
            } finally {
                File(context.filesDir, "takeover-navigation-verification.txt").writeText(report.joinToString("\n"))
                if (runtime.isPaused.value) runtime.resumeManualTakeover()
                automation.dropShellPermissionIdentity()
                store.finish(execution.executionId)
                manager.stop()
            }
        }
    }

    private fun capture(context: Context, name: String) {
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
}
