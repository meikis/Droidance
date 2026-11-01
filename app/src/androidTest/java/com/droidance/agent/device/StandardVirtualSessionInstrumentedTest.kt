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
import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droidance.core.device.debug.InputProbeActivity
import com.droidance.debug.VirtualSessionProbeAccess
import com.droidance.core.agent.tools.TapPrecision
import com.droidance.core.device.tools.TextInputService
import com.droidance.core.agent.LocalActionEvidence
import com.droidance.core.agent.event.ToolExecutionStatus
import kotlinx.serialization.json.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Requires a paired ADB channel or an already resident Standard Sidecar. Never falls back to Root or display zero. */
@RunWith(AndroidJUnit4::class)
class StandardVirtualSessionInstrumentedTest {
    @Test fun virtualFrameTapAndRetiredRoutesStayOnVirtualDisplay() = verifyVirtualSession(checkText = false)

    @Test fun virtualTextReachesTheExactEditor() = verifyVirtualSession(checkText = true)

    @Test fun causalBatchInputChecksTheRealEditorBeforeInjecting() = verifyVirtualSession(checkText = true, boundText = true)

    @Test fun causalBatchInputBindsAnEditorWithoutAResourceId() =
        verifyVirtualSession(checkText = true, boundText = true, withoutResourceId = true)

    private fun verifyVirtualSession(checkText: Boolean, boundText: Boolean = false,
                                     withoutResourceId: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val access = EntryPointAccessors.fromApplication(context, VirtualSessionProbeAccess::class.java)
        val store = access.executionContextStore()
        assertNull("A user execution must not be interrupted by this test", store.current())
        withContext(Dispatchers.IO) {
            val pairing = access.airModePairingManager()
            if (!pairing.isShellReady && !access.sidecarBridge().isResidentBinderAlive()) pairing.reconnect()
            withTimeout(30_000) { while (!pairing.isShellReady && !access.sidecarBridge().isResidentBinderAlive()) delay(200) }
            access.capabilityRegistry().refresh(force = true)
            val ready = access.virtualSpaceReadiness().ensureReadyResult()
            assertTrue(ready.message, ready.ready)
        }
        val binding = checkNotNull(access.virtualSpaceManager().activeBinding())
        assertEquals(RuntimeMode.STANDARD, binding.runtimeMode)
        assertTrue(binding.displayId > 0)
        val strategy = DeviceExecutionStrategy(DeviceModeSelection(RuntimeMode.STANDARD, RuntimeMode.STANDARD),
            ExecutionSpaceSelection(ExecutionSpace.VIRTUAL_SPACE, ExecutionSpace.VIRTUAL_SPACE))
        val execution = ExecutionContext.fromStrategy("standard-virtual-verification", binding.backendSessionId, strategy, binding.displayId)
        store.start(execution)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        try {
            val intent = Intent(context, InputProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            ActivityScenario.launch<InputProbeActivity>(intent,
                ActivityOptions.makeBasic().setLaunchDisplayId(binding.displayId).toBundle()).use { scenario ->
                val clicks = AtomicInteger()
                var x = 0; var y = 0
                scenario.onActivity {
                    assertEquals(binding.displayId, it.display?.displayId)
                    if (withoutResourceId) {
                        it.editor.id = android.view.View.NO_ID
                        it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(it.editor)
                    }
                    val point = IntArray(2)
                    it.editor.getLocationOnScreen(point)
                    x = point[0] + it.editor.width / 2
                    y = point[1] + it.editor.height / 2
                    it.editor.setOnClickListener { clicks.incrementAndGet() }
                }
                val backend = access.deviceBackend()
                delay(300)
                val frame = checkNotNull(backend.captureScreen(true)) { backend.screenFailureMessage() }
                assertEquals(binding.displayId, frame.displayId)
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size))
                assertTrue(bitmap.width > 100 && bitmap.height > 100)
                try {
                    val reference = checkNotNull(frame.pixelReference)
                    reference.samples.forEachIndexed { index, point ->
                        // Decision frames are JPEG, so the retained image may differ slightly per channel.
                        val pixel = bitmap.getPixel(point.physicalX, point.physicalY)
                        for (shift in listOf(16, 8, 0)) {
                            val delta = ((reference.colors[index] shr shift) and 255) - ((pixel shr shift) and 255)
                            assertTrue("Retained evidence must preserve the native sampled pixel", kotlin.math.abs(delta) <= 24)
                        }
                    }
                } finally { bitmap.recycle() }
                val tap = backend.tap(x, y, TapPrecision.EXACT)
                assertTrue(tap.message, tap.success)
                withTimeout(2000) { while (clicks.get() == 0) delay(25) }
                assertNotNull(backend.samplePixels(listOf(x to y)))
                val bridge = access.sidecarBridge()
                assertFalse(bridge.injectNative(0, "tap 10 10", RuntimeMode.STANDARD).ok)
                assertFalse(bridge.injectKey(SidecarSessionBinding(RuntimeMode.STANDARD, 0, binding.backendSessionId), 4).ok)
                assertFalse(bridge.isBoundSessionAlive(binding.copy(runtimeMode = RuntimeMode.ULTRA)))
                assertFalse(bridge.isBoundSessionAlive(binding.copy(backendSessionId = "stale-session")))
                if (checkText) {
                    val focus = backend.awaitInputFocus(3000)
                    assertNotNull(focus.diagnosticCode, focus.target)
                    assertTrue(checkNotNull(focus.target).contains(";window_id="))
                    val text = "Droidance虚拟空间V10"
                    if (boundText) {
                        val before = LocalActionEvidence.ObservationLite(screenHash = frame.pngBytes.contentHashCode().toLong(),
                            foregroundPackage = context.packageName,
                            displayId = binding.displayId, backendSessionId = binding.backendSessionId)
                        val service = TextInputService(backend,
                            captureObservation = { error("Batch input must defer capture") },
                            captureSettledObservation = { error("Batch input must defer capture") },
                            stagePostActionObservation = {}, verifyTextDelivery = { _, _, _, _, _ -> false },
                            inputFocusProbeTimeoutMs = { 3000L }, resolveSemanticTarget = { null },
                            wasTextInputVerified = { _, _, _ -> false }, recordTextInputVerification = { _, _, _, _ -> },
                            recordInputDiagnostic = { _, _, _ -> }, validate = { _, _, _, _, _ -> error("Batch input must defer validation") },
                            appendValidation = { result, _ -> result }, recordExecutedActionResult = {})
                        val arguments = buildJsonObject { put("text", text); put("clear", true) }.toString()
                        val rejected = service.typeText(arguments, before, focus.target + "-changed")
                        assertEquals(ToolExecutionStatus.NOT_EXECUTED, rejected.executionStatus)
                        scenario.onActivity { assertNotEquals(text, it.editor.text.toString()) }
                        val accepted = service.typeText(arguments, before, checkNotNull(focus.target))
                        assertEquals(accepted.outputText, ToolExecutionStatus.SUCCEEDED, accepted.executionStatus)
                        assertEquals(1L, accepted.metrics["text_input_injected"])
                        assertEquals(0L, accepted.metrics["text_input_observation_ms"])
                    } else {
                        val input = backend.typeText(text, true, checkNotNull(focus.target))
                        assertTrue(input.message, input.success)
                    }
                    withTimeout(3000) {
                        while (true) {
                            var actual = ""
                            scenario.onActivity { actual = it.editor.text.toString() }
                            if (actual == text) break
                            delay(50)
                        }
                    }
                }
                java.io.File(context.filesDir, "standard-virtual-verification.txt").writeText(
                    "display=${binding.displayId}; mode=${binding.runtimeMode}; tap_received=${clicks.get()}; exact_text=$checkText; frame=true; retired_routes_rejected=true")
            }
        } finally {
            automation.dropShellPermissionIdentity()
            store.finish(execution.executionId)
            access.virtualSpaceManager().stop()
        }
    }
}
