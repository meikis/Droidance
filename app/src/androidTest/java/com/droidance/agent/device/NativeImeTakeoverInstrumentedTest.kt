// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.MainActivity
import com.droidance.agent.AgentRuntime
import com.droidance.core.agent.AgentState
import com.droidance.agent.RuntimeCapabilityRegistry
import com.droidance.ui.overlay.VirtualDisplayTouchMapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses the selected real IME and the production mirror/input path; no model task is sent. */
class NativeImeTakeoverInstrumentedTest {
    @Test fun nativeKeyboardBindsTheVirtualEditorAndIsReleasedWithTheViewer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val selectedIme = shell("settings get secure default_input_method").trim()
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            lateinit var runtime: AgentRuntime
            lateinit var manager: VirtualSpaceManager
            host.onActivity {
                runtime = it.agentRuntime
                manager = field(runtime, "virtualSpaceManager")
            }
            assertEquals("Requires an idle device", AgentState.IDLE, runtime.state.value)
            assertFalse(runtime.isPaused.value)
            assertNull("Requires an unoccupied virtual display", manager.activeBinding())
            val active = field<MutableStateFlow<Boolean>>(runtime, "_isMobileUseTaskActive")
            val state = field<MutableStateFlow<AgentState>>(runtime, "_state")
            var stage = "starting"
            automation.adoptShellPermissionIdentity()
            try {
                // Keep the synthetic takeover out of the idle-watch cleanup; no model turn runs.
                state.value = AgentState.EXECUTING_TOOL
                val registry = field<RuntimeCapabilityRegistry>(manager, "capabilityRegistry")
                val strategy = registry.awaitTaskStrategy()
                val mode = strategy.selection.effectiveMode
                assertNotNull(manager.lastFailureReason(), manager.start(mode))
                val binding = checkNotNull(manager.activeBinding())
                active.value = true
                // The editor is a different UID in the standalone test APK.
                val editorComponent = "${instrumentation.context.packageName}/com.droidance.test.InputProbeActivity"
                assertTrue(manager.launchActivity(editorComponent).commandSent)
                try {
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
                        viewer.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                        fun tapEditor() {
                            val position = shell("run-as ${instrumentation.context.packageName} cat files/input-probe-position.txt")
                                .trim().split(',').map(String::toInt)
                            assertEquals(binding.displayId, position[0])
                            val x = position[1]
                            val y = position[2]
                            viewer.onActivity {
                                val preview = field<View>(it, "previewView")
                                val size = checkNotNull(manager.activeDisplaySize())
                                val point = checkNotNull(VirtualDisplayTouchMapper.mapDisplayToView(
                                    x, y, preview.width, preview.height, size.first, size.second))
                                val downAt = SystemClock.uptimeMillis()
                                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                                    val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                                    try { assertTrue(preview.dispatchTouchEvent(event)) } finally { event.recycle() }
                                }
                            }
                        }
                        stage = "opening"
                        tapEditor()
                        withTimeout(15_000) {
                            var ready = false
                            while (!ready) {
                                viewer.onActivity { ready = field<Int>(it, "nativeImeBottomInsetPx") > 0 }
                                ready = nativeKeyboardShown(binding.displayId) && ready
                                if (!ready) delay(50)
                            }
                        }
                        delay(500) // Allow the native IME's surface animation to finish before the screenshot.
                        File(context.filesDir, "native-ime-platform-state.txt").writeText(shell("dumpsys input_method"))
                        assertTrue("The native keyboard must reserve space on the virtual display",
                            (manager.viewerImeBottomInset(binding) ?: 0) > 0)
                        capture("native-ime-open.png")
                        InstrumentationRegistry.getArguments().getString("native_ime_expected_text")?.let { expected ->
                            stage = "entering text"
                            withTimeout(300_000) {
                                var actual = ""
                                while (actual != expected) {
                                    actual = shell("run-as ${instrumentation.context.packageName} cat files/input-probe.txt").trim()
                                    if (actual != expected) delay(100)
                                }
                            }
                            capture("native-ime-text-entered.png")
                        }
                        stage = "returning to AI"
                        assertTrue(runtime.resumeManualTakeover())
                        withTimeout(5_000) {
                            var hidden = false
                            while (!hidden) {
                                hidden = !nativeKeyboardShown()
                                if (!hidden) delay(50)
                            }
                        }
                        capture("native-ime-returned-to-ai.png")
                        stage = "reopening"
                        withTimeout(5_000) {
                            while (!runtime.beginManualTakeoverFromOverlay()) delay(50)
                        }
                        withTimeout(5_000) {
                            var attached = false
                            while (!attached) {
                                viewer.onActivity { attached = field<Any?>(it, "displayedBinding") == binding }
                                if (!attached) delay(50)
                            }
                        }
                        tapEditor()
                        withTimeout(5_000) {
                            var visible = false
                            while (!visible) {
                                viewer.onActivity { visible = field<Int>(it, "nativeImeBottomInsetPx") > 0 }
                                visible = nativeKeyboardShown(binding.displayId) && visible
                                if (!visible) delay(50)
                            }
                        }
                        delay(500)
                        capture("native-ime-reopened.png")
                        stage = "backgrounding viewer"
                        assertTrue(manager.launchHostActivity(android.content.ComponentName(context, MainActivity::class.java).flattenToString()))
                        withTimeout(5_000) {
                            while (manager.viewerImeBottomInset(binding) != 0 || nativeKeyboardShown()) delay(50)
                        }
                        assertEquals(binding, manager.activeBinding())
                        stage = "resuming viewer"
                        assertTrue(manager.launchHostActivity(android.content.ComponentName(context, VirtualSpaceViewerActivity::class.java).flattenToString()))
                        withTimeout(10_000) {
                            var attached = false
                            while (!attached) {
                                viewer.onActivity { attached = field<Any?>(it, "displayedBinding") == binding }
                                if (!attached) delay(50)
                            }
                        }
                        tapEditor()
                        withTimeout(10_000) {
                            var visible = false
                            while (!visible) {
                                viewer.onActivity { visible = field<Int>(it, "nativeImeBottomInsetPx") > 0 }
                                visible = nativeKeyboardShown(binding.displayId) && visible
                                if (!visible) delay(50)
                            }
                        }
                        delay(500)
                        capture("native-ime-viewer-resumed.png")
                        stage = "closing"
                        viewer.onActivity { it.finish() }
                        withTimeout(5_000) {
                            while (manager.viewerImeBottomInset(binding) != 0 || nativeKeyboardShown()) delay(50)
                        }
                        assertEquals(binding, manager.activeBinding())
                        assertTrue(runtime.resumeManualTakeover())
                    }
                    stage = "agent keyboard mode"
                    val executionId = "native-ime-device-test"
                    assertTrue(manager.beginExecution(executionId, "ime-test").commandSent)
                    assertTrue(manager.setExecutionKeyboard(binding, executionId, true))
                    val position = shell("run-as ${instrumentation.context.packageName} cat files/input-probe-position.txt")
                        .trim().split(',').map(String::toInt)
                    assertTrue(manager.injectTap(position[1], position[2]))
                    withTimeout(10_000) {
                        while (!nativeKeyboardShown(binding.displayId)) delay(50)
                    }
                    // A hardware-key route can dismiss the IME without changing the editor.
                    assertTrue(manager.injectKey(android.view.KeyEvent.KEYCODE_A))
                    assertTrue(manager.setExecutionKeyboard(binding, executionId, true))
                    assertTrue(manager.injectTap(position[1], position[2]))
                    withTimeout(10_000) {
                        while (!nativeKeyboardShown(binding.displayId)) delay(50)
                    }
                    assertTrue(manager.setExecutionKeyboard(binding, executionId, false))
                    withTimeout(5_000) { while (nativeKeyboardShown()) delay(50) }
                    assertTrue(manager.setExecutionKeyboard(binding, executionId, true))
                    assertTrue(manager.injectTap(position[1], position[2]))
                    withTimeout(10_000) {
                        while (!nativeKeyboardShown(binding.displayId)) delay(50)
                    }
                    assertTrue(manager.endExecutionKeyboard(executionId))
                    withTimeout(5_000) { while (nativeKeyboardShown()) delay(50) }
                    stage = "native IME text commit"
                    val tools = field<Any>(runtime, "toolExecutor")
                    val backend = field<AndroidDeviceBackend>(tools, "deviceBackend")
                    val input = field<NativeImeTextInput>(backend, "nativeImeTextInput")
                    val bridge = field<VirtualSpaceSidecarBridge>(manager, "sidecarBridge")
                    val execution = ExecutionContext.fromStrategy(executionId, binding.backendSessionId, strategy, binding.displayId)
                    val executionContexts = field<ExecutionContextStore>(backend, "executionContextStore")
                    executionContexts.start(execution)
                    try {
                        for ((payload, clear, expected) in listOf(
                            Triple("中文 💟 123", true, "中文 💟 123"),
                            Triple("追加。", false, "中文 💟 123追加。"),
                            Triple("替换完成", true, "替换完成")
                        )) {
                            val focus = checkNotNull(bridge.getInputFocusState(binding.displayId, binding.runtimeMode, binding.backendSessionId))
                            val result = input.type(execution, payload, clear, focus, position[1] to position[2])
                            assertTrue(result.message, result.success)
                            assertTrue(result.message, result.message.contains("initial_ime_restored=true"))
                            withTimeout(5_000) {
                                while (shell("run-as ${instrumentation.context.packageName} cat files/input-probe.txt").trim() != expected) delay(50)
                            }
                            assertEquals(selectedIme, shell("settings get secure default_input_method").trim())
                        }
                        val currentFocus = checkNotNull(bridge.getInputFocusState(binding.displayId, binding.runtimeMode, binding.backendSessionId))
                        assertFalse(input.type(execution, "must-not-arrive", true, currentFocus + "-stale", position[1] to position[2]).success)
                        assertFalse(input.type(execution.copy(executionId = "stale-execution"), "must-not-arrive", true, currentFocus, position[1] to position[2]).success)
                        assertEquals("替换完成", shell("run-as ${instrumentation.context.packageName} cat files/input-probe.txt").trim())
                        assertEquals(selectedIme, shell("settings get secure default_input_method").trim())
                        stage = "orphaned native IME selection"
                        instrumentation.runOnMainSync {
                            AgentTextInputMethodService.beginSession(context, selectedIme)
                            AgentTextInputMethodService.endSession(context, false)
                        }
                        val imeComponent = android.content.ComponentName(context, AgentTextInputMethodService::class.java).flattenToShortString()
                        assertTrue(manager.setExecutionKeyboard(binding, executionId, true))
                        shell("ime enable $imeComponent")
                        shell("ime set $imeComponent")
                        withTimeout(5_000) {
                            while (shell("settings get secure default_input_method").trim() != selectedIme) delay(50)
                        }
                        shell("ime disable $imeComponent")
                        instrumentation.runOnMainSync { AgentTextInputMethodService.endSession(context, true) }
                        assertTrue(manager.endExecutionKeyboard(executionId))
                    } finally { executionContexts.finish(executionId) }
                } finally {
                    manager.injectKey(android.view.KeyEvent.KEYCODE_BACK)
                }
            } catch (failure: Throwable) {
                File(context.filesDir, "native-ime-failure.txt").writeText("stage=$stage\n" + shell("dumpsys input_method"))
                capture("native-ime-failure.png")
                throw failure
            } finally {
                if (runtime.isPaused.value) runtime.resumeManualTakeover()
                active.value = false
                manager.stop()
                state.value = AgentState.IDLE
                automation.dropShellPermissionIdentity()
            }
        }
        assertEquals("Takeover must preserve the user's selected IME", selectedIme,
            shell("settings get secure default_input_method").trim())
    }

    private fun nativeKeyboardShown(displayId: Int? = null): Boolean {
        val state = shell("dumpsys input_method")
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "native-ime-pending.txt").writeText(state)
        return state.contains("mInputShown=true") &&
            (displayId == null || state.contains("mSelfReportedDisplayId=$displayId}") &&
                state.contains("mDisplayIdToShowIme=$displayId"))
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
}
