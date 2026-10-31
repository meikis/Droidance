// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.ScreenChangeSignature
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.coroutineContext

/** Repeats a model-grounded drag, observing every effect without interpreting row values. */
class DragRepeatExecutor(
    private val capture: suspend () -> Frame?,
    private val drag: suspend () -> ToolExecutionResult,
    private val onProgress: (String) -> Unit = {}
) {
    data class Frame(val signature: ScreenChangeSignature, val surface: String, val stable: Boolean)

    suspend fun execute(arguments: String, repeat: Int): ToolExecutionResult {
        require(repeat > 0)
        var attempted = 0
        var delivered = 0
        var changed = 0
        var last: ToolExecutionResult? = null
        fun details(reason: String) = buildJsonObject {
            put("requested", JsonPrimitive(repeat))
            put("attempted", JsonPrimitive(attempted))
            put("delivered", JsonPrimitive(delivered))
            put("changed", JsonPrimitive(changed))
            put("stop_reason", JsonPrimitive(reason))
        }
        fun summary(reason: String) = "[DRAG_REPEAT] ${details(reason)}"
        fun finish(base: ToolExecutionResult, reason: String): ToolExecutionResult {
            val state = details(reason)
            val output = summary(reason)
            onProgress(output)
            return base.copy(outputText = output + "\n" + base.outputText,
                data = buildJsonObject { put("drag_repeat", state) },
                metrics = base.metrics + mapOf("drag_repeat_requested" to repeat.toLong(),
                    "drag_repeat_attempted" to attempted.toLong(), "drag_repeat_delivered" to delivered.toLong(),
                    "drag_repeat_changed" to changed.toLong()))
        }
        fun stop(code: String, reason: String): ToolExecutionResult {
            val base = if (attempted == 0) ToolProtocolResult.notExecuted(code, true)
                else ToolProtocolResult.interrupted(code)
            return finish(base.copy(actionEvidence = last?.actionEvidence ?: base.actionEvidence), reason)
        }
        try {
            var before = capture() ?: return stop("DRAG_REPEAT_OBSERVATION_UNAVAILABLE", "observation_unavailable")
            if (!before.stable) return stop("DRAG_REPEAT_UNSETTLED", "unsettled_before_drag")
            for (index in 1..repeat) {
                coroutineContext.ensureActive()
                attempted = index
                onProgress(summary("in_flight"))
                val result = drag()
                last = result
                if (result.executionStatus != ToolExecutionStatus.SUCCEEDED) {
                    // A later NOT_EXECUTED cannot erase an earlier delivered repetition.
                    val base = if (delivered > 0 && result.executionStatus == ToolExecutionStatus.NOT_EXECUTED)
                        result.copy(executionStatus = ToolExecutionStatus.FAILED) else result
                    return finish(base, "delivery_stopped")
                }
                delivered++
                onProgress(summary("awaiting_observation"))
                val after = capture() ?: return stop("DRAG_REPEAT_OBSERVATION_UNAVAILABLE", "observation_unavailable")
                if (after.surface != before.surface) return stop("DRAG_REPEAT_SURFACE_CHANGED", "surface_changed")
                if (!after.stable) return stop("DRAG_REPEAT_UNSETTLED", "unsettled_after_drag")
                val moved = GestureEffect.changed(arguments, before.signature, after.signature)
                    ?: return stop("DRAG_REPEAT_OBSERVATION_UNAVAILABLE", "incomparable_frames")
                if (!moved) return finish(ToolProtocolResult.failed("DRAG_REPEAT_NO_CHANGE", true)
                    .copy(actionEvidence = result.actionEvidence), "no_change_at_$index")
                changed++
                onProgress(summary("observed"))
                before = after
            }
            return finish(checkNotNull(last), "completed")
        } catch (cancelled: CancellationException) {
            onProgress(summary("cancelled_effect_may_be_unobserved"))
            throw cancelled
        } catch (_: Exception) {
            return stop("DRAG_REPEAT_OBSERVATION_UNAVAILABLE", "execution_or_observation_failed")
        }
    }
}
