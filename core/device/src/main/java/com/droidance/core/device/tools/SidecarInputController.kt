// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.device.ExecutionContextStore
import com.droidance.core.device.agent.device.SidecarSessionBinding
import com.droidance.core.device.agent.device.VirtualSpaceSidecarBridge
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.device.debug.DiagnosticsCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Exact endpoints, including the initial DOWN point; no randomized path or timing. */
internal fun exactDragPath(x1: Int, y1: Int, x2: Int, y2: Int): List<Pair<Int, Int>> =
    (0..16).map { index ->
        (x1 + ((x2.toLong() - x1) * index / 16).toInt()) to
            (y1 + ((y2.toLong() - y1) * index / 16).toInt())
    }

/** Inputs are delivered only to the frozen Standard virtual display. */
@Singleton
class SidecarInputController @Inject constructor(
    private val sidecarBridge: VirtualSpaceSidecarBridge,
    private val executionContextStore: ExecutionContextStore,
    private val inputActionLifecycle: InputActionLifecycle,
    private val diagnostics: DiagnosticsCollector
) {
    val activeDisplayId: Int? get() = executionContextStore.current()?.displayId?.takeIf { it > 0 }
    private fun binding(): SidecarSessionBinding {
        val execution = executionContextStore.requireCurrent()
        require(execution.mode == RuntimeMode.STANDARD && execution.isVirtualSpace && execution.displayId > 0)
        return SidecarSessionBinding(execution.mode, execution.displayId, execution.backendSessionId)
    }
    suspend fun tap(x: Int, y: Int) = exactTap(Humanizer.jitterXY(x, sigma = 3), Humanizer.jitterXY(y, sigma = 3))
    suspend fun preciseTap(x: Int, y: Int) = tap(x, y)
    suspend fun exactTap(x: Int, y: Int) = inject("tap $x $y", ExecutedActionEvent.Tap(x, y))
    suspend fun longPress(x: Int, y: Int, durationMs: Int = 500) =
        inject("longpress $x $y $durationMs", ExecutedActionEvent.Tap(x, y))
    suspend fun doubleTap(x: Int, y: Int) = inject("doubletap $x $y", ExecutedActionEvent.Tap(x, y))
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500): DeviceInputResult {
        val sx = Humanizer.jitterSwipeEndpoint(x1); val sy = Humanizer.jitterSwipeEndpoint(y1)
        val ex = Humanizer.jitterSwipeEndpoint(x2); val ey = Humanizer.jitterSwipeEndpoint(y2)
        val duration = Humanizer.jitterDuration(durationMs)
        return inject("swipe $sx $sy $ex $ey $duration", ExecutedActionEvent.Swipe(sx, sy, ex, ey, duration))
    }
    /**
     * Press, move, then rest on the end point before releasing. The release carries no velocity,
     * so scrollable content, pickers and sliders stop where the finger stopped instead of flinging.
     */
    suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): DeviceInputResult =
        executeContinuousPath(exactDragPath(x1, y1, x2, y2), durationMs + 300, 300,
            DRAG_RELEASE_REST_MS)

    private suspend fun inject(args: String, event: ExecutedActionEvent): DeviceInputResult = withContext(Dispatchers.IO) {
        val frozen = binding()
        inputActionLifecycle.execute(event.toInputAction(), DeviceInputResult::success) {
            val result = sidecarBridge.injectNative(frozen, args)
            diagnostics.recordInjection(args.substringBefore(' '), frozen.runtimeMode.name,
                "bound-sidecar:${frozen.displayId}", result.ok, result.detail)
            DeviceInputResult(result.ok, result.detail, actionEvent = event.takeIf { result.ok },
                diagnosticCode = sidecarInputDiagnostic(result))
        }
    }
    suspend fun keyevent(keycode: Int): DeviceInputResult = withContext(Dispatchers.IO) {
        val result = sidecarBridge.injectKey(binding(), keycode)
        DeviceInputResult(result.ok, result.detail, diagnosticCode = sidecarInputDiagnostic(result))
    }
    suspend fun combo(name: String): DeviceInputResult = withContext(Dispatchers.IO) {
        val codes = when (name.lowercase().replace(" ", "")) {
            "ctrl+a" -> listOf(113, 29)
            "ctrl+c" -> listOf(113, 31)
            "ctrl+v" -> listOf(113, 50)
            "ctrl+x" -> listOf(113, 52)
            "ctrl+z" -> listOf(113, 54)
            "ctrl+shift+z" -> listOf(113, 59, 54)
            else -> return@withContext DeviceInputResult(false, "Unsupported key combination")
        }
        val result = sidecarBridge.injectKeyCombo(binding(), codes)
        DeviceInputResult(result.ok, result.detail, diagnosticCode = sidecarInputDiagnostic(result))
    }
    suspend fun awaitInputFocus(timeoutMs: Long): com.droidance.core.device.agent.device.InputFocusReadiness = withContext(Dispatchers.IO) {
        val executionContext = executionContextStore.current() ?: return@withContext com.droidance.core.device.agent.device.InputFocusReadiness(null, "INPUT_EXECUTION_UNAVAILABLE")
        sidecarBridge.awaitInputFocus(
            displayId = executionContext.displayId,
            mode = executionContext.mode,
            expectedBackendSessionId = executionContext.backendSessionId,
            timeoutMs = timeoutMs
        )
    }

    /** 在冻结的执行后端和 display 上执行一次可核验的文本粘贴。 */
    suspend fun inputText(text: String, clearExisting: Boolean, expectedFocusTarget: String, method: TextInputMethod): DeviceInputResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val context = executionContextStore.requireCurrent()
        val route = "sidecar-display:${context.displayId}:${context.mode.name.lowercase()}"
        val sidecarResult = sidecarBridge.injectText(
            displayId = context.displayId,
            text = text,
            clearExisting = clearExisting,
            mode = context.mode,
            expectedBackendSessionId = context.backendSessionId,
            expectedFocusTarget = expectedFocusTarget,
            method = method
        )
        val durationMs = System.currentTimeMillis() - startMs
        val evidence = sidecarResult.textInputEvidence
        if (evidence == null) {
            diagnostics.logTypeText(text, "failed/$route", false, durationMs, sidecarResult.detail.take(200))
            val channelMarker = if (sidecarResult.channelUnavailable) "$CHANNEL_UNAVAILABLE_MARKER " else ""
            return@withContext DeviceInputResult(
                success = false,
                message = "${channelMarker}text input failed: ${sidecarResult.detail}",
                diagnosticCode = sidecarInputDiagnostic(sidecarResult)
            )
        }

        diagnostics.logTypeText(text, route, evidence.eventInjected, durationMs, sidecarResult.detail.take(200))
        DeviceInputResult(
            success = evidence.eventInjected,
            message = "Text input ${evidence.outcome.name.lowercase()}: ${sidecarResult.detail}",
            textInputEvidence = evidence,
            diagnosticCode = sidecarInputDiagnostic(sidecarResult).takeUnless { evidence.eventInjected }
        )
    }

    suspend fun clearFocusedText(): DeviceInputResult = withContext(Dispatchers.IO) {
        TextInputRoutes.clearFocusedText(
            selectAll = { combo("ctrl+a") },
            forwardDelete = { keyevent(112) }
        )
    }

    suspend fun traceAlongPath(points: List<android.graphics.Point>, durationMs: Int = 1500): DeviceInputResult {
        if (points.size !in 2..128) return DeviceInputResult(false, "Path requires 2..128 points")
        return executeContinuousPath(points.map { Humanizer.jitterXY(it.x, 2) to Humanizer.jitterXY(it.y, 2) },
            Humanizer.jitterDuration(durationMs, ratio = 0.10f), 0)
    }

    private suspend fun executeContinuousPath(points: List<Pair<Int, Int>>, durationMs: Int, holdMs: Int,
                                              releaseRestMs: Int = 0): DeviceInputResult =
        withContext(Dispatchers.IO) {
            val execution = executionContextStore.requireCurrent()
            val binding = com.droidance.core.device.agent.device.SidecarSessionBinding(execution.mode, execution.displayId, execution.backendSessionId)
            if (!sidecarBridge.isBoundSessionAlive(binding)) return@withContext DeviceInputResult(false,
                "Continuous touch unavailable on the frozen backend", diagnosticCode = "CHANNEL_DOWN")
            val cancellationBinding = binding
            val first = points.first(); val last = points.last()
            val event = ExecutedActionEvent.Swipe(first.first, first.second, last.first, last.second, durationMs)
            inputActionLifecycle.execute(event.toInputAction(), { it: DeviceInputResult -> it.success }) {
                val downTime = android.os.SystemClock.uptimeMillis()
                var attemptedDown = false
                var released = false
                var current = first
                var lastDelivery: VirtualSpaceSidecarBridge.CommandResult? = null
                fun failed(message: String, fallbackCode: String) = DeviceInputResult(false,
                    "$message: ${lastDelivery?.detail.orEmpty()}",
                    diagnosticCode = lastDelivery?.let(::sidecarInputDiagnostic) ?: fallbackCode)
                suspend fun send(action: Int, point: Pair<Int, Int>): Boolean {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (executionContextStore.current()?.executionId != execution.executionId) return false
                    current = point
                    return sidecarBridge.injectTouch(binding, action, point.first, point.second,
                        downTime, android.os.SystemClock.uptimeMillis()).also { lastDelivery = it }.ok
                }
                try {
                    attemptedDown = true
                    if (!send(android.view.MotionEvent.ACTION_DOWN, first))
                        return@execute failed("Touch down failed", "GESTURE_DOWN_FAILED")
                    for (index in 1 until points.size) {
                        val due = downTime + holdMs + ((durationMs - holdMs).coerceAtLeast(1).toLong() * index / (points.size - 1))
                        kotlinx.coroutines.delay((due - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
                        if (!send(android.view.MotionEvent.ACTION_MOVE, points[index]))
                            return@execute failed("Path interrupted at point $index", "GESTURE_MOVE_FAILED")
                    }
                    // Stationary moves age the earlier motion out of the velocity window.
                    val restUntil = android.os.SystemClock.uptimeMillis() + releaseRestMs
                    while (android.os.SystemClock.uptimeMillis() < restUntil) {
                        kotlinx.coroutines.delay(RELEASE_REST_INTERVAL_MS)
                        if (!send(android.view.MotionEvent.ACTION_MOVE, last))
                            return@execute failed("Path interrupted while resting", "GESTURE_MOVE_FAILED")
                    }
                    released = send(android.view.MotionEvent.ACTION_UP, last)
                    DeviceInputResult(released,
                        "Continuous path ${if (released) "delivered" else "release failed"}; points=${points.size}; " +
                            "physical_pixels_start=${first.first},${first.second}; physical_pixels_end=${last.first},${last.second}; " +
                            "elapsed_ms=${android.os.SystemClock.uptimeMillis() - downTime}; channel=sidecar-touch; display=${execution.displayId}",
                        actionEvent = event, diagnosticCode = if (released) null else
                            lastDelivery?.let(::sidecarInputDiagnostic) ?: "GESTURE_RELEASE_FAILED")
                } finally {
                    if (attemptedDown && !released) withContext(kotlinx.coroutines.NonCancellable) {
                        sidecarBridge.injectTouch(cancellationBinding, android.view.MotionEvent.ACTION_CANCEL, current.first, current.second,
                            downTime, android.os.SystemClock.uptimeMillis())
                    }
                }
            }
        }

    private fun ExecutedActionEvent.toInputAction(): InputAction = when (this) {
        is ExecutedActionEvent.Tap -> InputAction.Tap(x, y)
        is ExecutedActionEvent.Swipe -> InputAction.Swipe(x1, y1, x2, y2, duration)
    }

    companion object {
        const val CHANNEL_UNAVAILABLE_MARKER = "[CHANNEL_DOWN]"
        /** Longer than Android's 100 ms velocity horizon, so a drag release measures no motion. */
        private const val DRAG_RELEASE_REST_MS = 150
        private const val RELEASE_REST_INTERVAL_MS = 16L
    }
}
