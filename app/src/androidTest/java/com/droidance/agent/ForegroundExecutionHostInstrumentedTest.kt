// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.DroidanceApp
import com.droidance.MainActivity
import com.droidance.agent.device.VirtualSpaceViewerActivity
import com.droidance.core.device.debug.InputProbeActivity
import com.droidance.debug.VirtualSessionProbeAccess
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises task-state observers and display retention without starting a model task. */
@RunWith(AndroidJUnit4::class)
class ForegroundExecutionHostInstrumentedTest {
    @Test fun terminalStatesLeaveViewerAndHomeUnchangedAndRetainTheVirtualDisplay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val access = EntryPointAccessors.fromApplication(context, VirtualSessionProbeAccess::class.java)
        assertNull("A user execution must not be interrupted", access.executionContextStore().current())
        withContext(Dispatchers.IO) {
            val pairing = access.airModePairingManager()
            val bridge = access.sidecarBridge()
            if (!pairing.isShellReady && !bridge.isResidentBinderAlive()) pairing.reconnect()
            withTimeout(30_000) {
                while (!pairing.isShellReady && !bridge.isResidentBinderAlive()) delay(200)
            }
            access.capabilityRegistry().refresh(force = true)
            val ready = access.virtualSpaceReadiness().ensureReadyResult()
            assertTrue(ready.message, ready.ready)
        }
        val manager = access.virtualSpaceManager()
        val binding = checkNotNull(manager.activeBinding())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val report = File(context.filesDir, "completion-no-navigation-verification.txt")
        report.writeText("virtual_display=${binding.displayId}\n")
        automation.adoptShellPermissionIdentity()
        try {
            val mainIntent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ActivityScenario.launch<MainActivity>(mainIntent,
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()).use { main ->
                lateinit var original: MainActivity
                lateinit var runtime: AgentRuntime
                lateinit var surface: com.droidance.ui.overlay.RuntimeSurfaceController
                main.onActivity {
                    original = it
                    runtime = it.agentRuntime
                    surface = field((context.applicationContext as DroidanceApp).agentStatusOverlay,
                        "runtimeSurfaceController")
                    assertEquals(AgentState.IDLE, it.agentRuntime.state.value)
                    assertFalse("An app takeover watch must not be interrupted",
                        it.agentRuntime.isMobileUseTaskActive.value)
                }
                val sourceIntent = Intent(context, InputProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                ActivityScenario.launch<InputProbeActivity>(sourceIntent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(binding.displayId).toBundle()).use { source ->
                    source.onActivity {
                        it.getSystemService(InputMethodManager::class.java)
                            .hideSoftInputFromWindow(it.editor.windowToken, 0)
                        it.editor.clearFocus()
                    }
                    suspend fun verifyNoNavigation(phase: String, viewerVisible: Boolean) {
                        val state = field<MutableStateFlow<AgentState>>(runtime, "_state")
                        val active = field<MutableStateFlow<Boolean>>(runtime, "_isMobileUseTaskActive")
                        val paused = field<MutableStateFlow<Boolean>>(runtime, "_isPaused")
                        val manual = field<MutableStateFlow<Boolean>>(runtime, "_manualTakeoverAvailable")
                        val originalPaused = paused.value
                        val originalManual = manual.value
                        try {
                            // Cover a direct reply, device execution, and manual control. Both
                            // successful and failed task settlement publish IDLE; errors can also
                            // publish ERROR. None may change the user's current Activity.
                            for (mobileUse in listOf(false, true)) {
                                for (terminal in listOf(AgentState.IDLE, AgentState.ERROR)) {
                                    for (manualControl in listOf(false, true)) {
                                        active.value = mobileUse
                                        paused.value = false
                                        manual.value = false
                                        state.value = AgentState.EXECUTING_TOOL
                                        delay(300)
                                        paused.value = manualControl
                                        manual.value = manualControl
                                        active.value = false
                                        state.value = terminal
                                        delay(500)
                                        instrumentation.runOnMainSync {
                                            assertFalse("Conversation must stay covered ($phase)",
                                                original.lifecycle.currentState == Lifecycle.State.RESUMED)
                                        }
                                        assertEquals("Viewer visibility must stay unchanged ($phase)",
                                            viewerVisible, surface.isViewerExpanded)
                                    }
                                }
                            }
                        } finally {
                            active.value = false
                            paused.value = originalPaused
                            manual.value = originalManual
                            state.value = AgentState.IDLE
                        }
                        assertEquals(binding, manager.activeBinding())
                        source.onActivity { assertEquals(binding.displayId, it.display?.displayId) }
                        automation.takeScreenshot()?.let { bitmap ->
                            File(context.filesDir, "completion-no-navigation-$phase.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                        report.appendText("$phase: no_navigation=true virtual_binding_preserved=true\n")
                    }
                    assertTrue(withContext(Dispatchers.IO) {
                        manager.launchHostActivity(android.content.ComponentName(context,
                            VirtualSpaceViewerActivity::class.java).flattenToString())
                    })
                    withTimeout(10_000) {
                        while (!surface.isViewerExpanded) delay(50)
                    }
                    verifyNoNavigation("viewer", viewerVisible = true)
                    automation.executeShellCommand("input -d 0 keyevent KEYCODE_HOME").close()
                    withTimeout(10_000) {
                        while ((context.applicationContext as DroidanceApp).foregroundState.isInForeground ||
                            surface.isViewerExpanded) delay(50)
                    }
                    verifyNoNavigation("home", viewerVisible = false)
                    // Exercise the idle scheduler's production refresh path after completion;
                    // a display lease is independent of foreground-service/task residency.
                    withContext(Dispatchers.IO) {
                        assertTrue(manager.beginExecution("retention:first", "retention:conversation").commandSent)
                        val firstOwner = checkNotNull(manager.retainedExecution.value)
                        repeat(6) {
                            original.agentRuntime.refreshAppTakeoverResidency()
                            delay(250)
                            assertEquals(binding, manager.activeBinding())
                            assertEquals(firstOwner, manager.retainedExecution.value)
                        }
                        original.agentRuntime.stopCurrentMobileUse("retention-regression")
                        delay(300)
                        assertEquals(firstOwner, manager.retainedExecution.value)
                        assertEquals(binding, manager.activeBinding())
                        assertTrue(manager.beginExecution("retention:next", "retention:conversation").commandSent)
                        assertEquals(firstOwner.threadId, manager.retainedExecution.value?.threadId)
                        assertTrue(manager.beginExecution("retention:other", "retention:other-conversation").commandSent)
                        assertFalse(manager.launchHostActivity(android.content.ComponentName(context,
                            VirtualSpaceViewerActivity::class.java).flattenToString(), firstOwner))
                        assertEquals("retention:other-conversation", manager.retainedExecution.value?.threadId)
                    }
                    source.onActivity { assertEquals(binding.displayId, it.display?.displayId) }
                    report.appendText("idle_scheduler_and_stop: retained=true stale_owner_rejected=true\n")
                }
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T
}
