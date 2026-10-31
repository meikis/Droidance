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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.device.debug.InputProbeActivity
import com.droidance.debug.VirtualSessionProbeAccess
import com.droidance.core.agent.tools.TapPrecision
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual viewer/backend regression with an isolated animated probe, without model requests. */
@RunWith(AndroidJUnit4::class)
class VirtualSpaceViewerMirrorInstrumentedTest {
    @Test fun mirrorLifecycleKeepsFreshBackgroundScreenshotsAndInput() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "viewer-mirror-stage.txt").writeText("")
        fun stage(value: String) { File(context.filesDir, "viewer-mirror-stage.txt").appendText("${SystemClock.uptimeMillis()} $value\n") }
        stage("resolve_entry_point")
        val access = EntryPointAccessors.fromApplication(context, VirtualSessionProbeAccess::class.java)
        stage("resolve_execution_store")
        val store = access.executionContextStore()
        assertNull("A user execution must not be interrupted", store.current())
        stage("prepare_channel")
        withContext(Dispatchers.IO) {
            stage("resolve_pairing")
            val pairing = access.airModePairingManager()
            stage("pairing_resolved")
            val bridge = access.sidecarBridge()
            stage("bridge_resolved")
            if (!pairing.isShellReady && !bridge.isResidentBinderAlive()) pairing.reconnect()
            stage("waiting_channel")
            withTimeout(30_000) { while (!pairing.isShellReady && !bridge.isResidentBinderAlive()) delay(200) }
            stage("channel_ready")
            stage("refresh_capabilities")
            access.capabilityRegistry().refresh(force = true)
            stage("ensure_ready")
            val ready = access.virtualSpaceReadiness().ensureReadyResult()
            assertTrue(ready.message, ready.ready)
        }
        stage("resolve_manager")
        val manager = access.virtualSpaceManager()
        val binding = checkNotNull(manager.activeBinding())
        val strategy = DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE))
        val execution = ExecutionContext.fromStrategy("viewer-mirror-verification", binding.backendSessionId, strategy, binding.displayId)
        store.start(execution)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        val report = mutableListOf<String>()
        try {
            val intent = Intent(context, InputProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            stage("launch_source")
            ActivityScenario.launch<InputProbeActivity>(intent,
                ActivityOptions.makeBasic().setLaunchDisplayId(binding.displayId).toBundle()).use { source ->
                delay(300)
                val clicks = AtomicInteger()
                val drawnAt = CopyOnWriteArrayList<Long>()
                var tapX = 0; var tapY = 0
                source.onActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity.editor.windowToken, 0)
                    activity.editor.clearFocus()
                    activity.setContentView(object : View(activity) {
                        private val paint = Paint()
                        private var lastNavigationColor = 0
                        override fun onDraw(canvas: Canvas) {
                            val navigationColor = if ((SystemClock.uptimeMillis() / 300L) % 2L == 0L) Color.BLACK else Color.WHITE
                            if (navigationColor != lastNavigationColor) {
                                @Suppress("DEPRECATION")
                                activity.window.navigationBarColor = navigationColor
                                lastNavigationColor = navigationColor
                            }
                            canvas.drawColor(Color.rgb(25, 70, 180))
                            paint.color = Color.MAGENTA
                            canvas.drawRect(10f, 10f, 160f, 160f, paint)
                            paint.color = Color.WHITE
                            val x = (SystemClock.uptimeMillis() % 2000) / 2000f * width
                            canvas.drawRect(x, height * 0.7f, x + 12, height * 0.9f, paint)
                            drawnAt += SystemClock.uptimeMillis()
                            postInvalidateOnAnimation()
                        }
                    }.apply {
                        setOnClickListener { clicks.incrementAndGet() }
                        post {
                            val position = IntArray(2)
                            getLocationOnScreen(position)
                            tapX = position[0] + width / 2
                            tapY = position[1] + height / 2
                        }
                    })
                }
                delay(500)
                val backend = access.deviceBackend()
                suspend fun verifyBackend(phase: String) {
                    assertEquals(binding, manager.activeBinding())
                    val first = checkNotNull(backend.captureScreen(true)) { backend.screenFailureMessage() }
                    delay(120)
                    val second = checkNotNull(backend.captureScreen(true)) { backend.screenFailureMessage() }
                    assertEquals(binding.displayId, second.displayId)
                    assertTrue("Screenshot timestamp must advance ($phase)", second.capturedAtMs > first.capturedAtMs)
                    assertFalse("Animated pixels must change ($phase)", first.pngBytes.contentEquals(second.pngBytes))
                    assertNotNull(backend.samplePixels(listOf(tapX to tapY)))
                    val before = clicks.get()
                    val tap = backend.tap(tapX, tapY, TapPrecision.EXACT)
                    assertTrue(tap.message, tap.success)
                    withTimeout(3_000) { while (clicks.get() <= before) delay(25) }
                    report += "$phase: fresh_screenshot=true changed_pixels=true tap_received=true display=${binding.displayId}"
                }
                stage("source_ready")
                verifyBackend("before_viewer")
                val bridge = access.sidecarBridge()
                val resolver = VirtualSpaceSidecarBridge::class.java.getDeclaredMethod("serviceOrNull", RuntimeMode::class.java)
                    .apply { isAccessible = true }
                val service = resolver.invoke(bridge, RuntimeMode.STANDARD) as IVirtualSpaceSidecar
                suspend fun awaitMirror(attached: Boolean) {
                    withTimeout(10_000) {
                        while (!withContext(Dispatchers.IO) { service.status.contains("viewer_mirror=$attached") }) delay(50)
                    }
                }
                val viewerIntent = Intent(context, VirtualSpaceViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                stage("launch_viewer")
                ActivityScenario.launch<VirtualSpaceViewerActivity>(viewerIntent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()).use { viewer ->
                    awaitMirror(true)
                    stage("mirror_attached")
                    viewer.onActivity { activity ->
                        val bounds = activity.windowManager.maximumWindowMetrics.bounds
                        assertEquals(bounds.width() to bounds.height(), manager.activeDisplaySize())
                        val preview = VirtualSpaceViewerActivity::class.java.getDeclaredField("previewView")
                            .apply { isAccessible = true }.get(activity) as View
                        assertEquals(bounds.width(), preview.width)
                        assertEquals(bounds.height(), preview.height)
                    }
                    verifyBackend("viewer_visible")
                    val start = SystemClock.uptimeMillis()
                    stage("measure_presentation")
                    delay(8_000)
                    val end = SystemClock.uptimeMillis()
                    val fps = drawnAt.count { it in start..end } * 1000.0 / (end - start)
                    report += "source_draw_fps=$fps (source rendering, not physical presentation)"
                    assertTrue("Animated source must sustain >=55 FPS; measured $fps", fps >= 55)
                    automation.takeScreenshot()?.let { bitmap ->
                        val visiblePixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(visiblePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertTrue("Probe pixels must be visible on the physical screen",
                            visiblePixels.count { it == Color.rgb(25, 70, 180) } > visiblePixels.size / 3)
                        File(context.filesDir, "viewer-mirror-visible.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    var navigationHeight = 0
                    repeat(6) {
                        delay(300)
                        viewer.onActivity { activity ->
                            val background = VirtualSpaceViewerActivity::class.java.getDeclaredField("navigationBackground")
                                .apply { isAccessible = true }.get(activity) as View
                            navigationHeight = background.height
                            assertTrue(navigationHeight > 0)
                            assertEquals(Color.rgb(25, 70, 180), (background.background as android.graphics.drawable.ColorDrawable).color)
                        }
                        val screen = checkNotNull(automation.takeScreenshot())
                        val bottom = screen.getPixel(screen.width / 4, screen.height - navigationHeight + 4)
                        assertTrue("Physical navigation background must stay blue while source decor alternates: $bottom",
                            Color.blue(bottom) > 120 && Color.red(bottom) < 150)
                        screen.recycle()
                    }
                    report += "physical_size_matches=true navigation_background_stable_with_alternating_source_decor=true"
                    assertTrue(bridge.detachViewerMirror(binding, "stale-viewer-owner").ok)
                    assertTrue(service.status.contains("viewer_mirror=true"))
                    viewer.onActivity { assertTrue(it.moveTaskToBack(true)) }
                    awaitMirror(false)
                    verifyBackend("viewer_paused")
                    context.startActivity(viewerIntent, ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
                    awaitMirror(true)
                    verifyBackend("viewer_resumed")
                }
                awaitMirror(false)
                verifyBackend("viewer_closed")
                report += "mirror_detached_after_close=true"
            }
        } finally {
            File(context.filesDir, "viewer-mirror-verification.txt").writeText(report.joinToString("\n"))
            automation.dropShellPermissionIdentity()
            store.finish(execution.executionId)
            manager.stop()
        }
    }
}
