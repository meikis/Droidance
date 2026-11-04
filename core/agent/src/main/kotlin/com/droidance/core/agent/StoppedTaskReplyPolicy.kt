// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.serialization.Serializable

/** Mechanical limits and eligibility loaded from Markdown, never business routing. */
@Serializable
data class StoppedTaskReplyPolicy(
    val eligibleReasons: Set<TurnFailureReason>,
    val timeoutMs: Long,
    val maxOutputTokens: Int,
    val maxContextChars: Int,
    val reasoningEffort: com.droidance.core.model.llm.models.ReasoningEffort
) {
    fun validate() {
        require(timeoutMs >= 0 && maxOutputTokens > 0 && maxContextChars > 0)
        require(TurnFailureReason.NONE !in eligibleReasons)
    }

    fun eligible(result: TurnResult): Boolean =
        result.failureReason in eligibleReasons &&
            result.terminal != TurnTerminal.DONE && result.terminal != TurnTerminal.DIRECT_REPLY &&
            !result.taskDoneByModel
}

/** A separately committed explanation cannot change execution status or certify completion. */
fun TurnResult.withStoppedTaskReply(reply: String): TurnResult {
    require(reply.isNotBlank())
    return copy(summary = reply, operatorOutput = reply, modelFailureSummary = reply,
        runtimeFailureSummary = null, contentStreamed = false, rounds = rounds + 1)
}

/** Every error keeps a useful local notice even when another model request cannot be made. */
internal fun TurnResult.withRuntimeFailureNotice(text: (String) -> String): TurnResult {
    if (terminal != TurnTerminal.SYSTEM_ERROR || !runtimeFailureSummary.isNullOrBlank()) return this
    val key = when (failureReason) {
        TurnFailureReason.RUNTIME_EXCEPTION, TurnFailureReason.NONE -> "termination_internal_error"
        TurnFailureReason.GUARD_STOP -> guardStopNoticeKey(stopDiagnostic)
        else -> "termination_${failureReason.name.lowercase(java.util.Locale.ROOT)}"
    }
    return copy(runtimeFailureSummary = text(key))
}

/** Only execution diagnostics select these localized runtime notices; no task semantics. */
internal fun guardStopNoticeKey(diagnostic: String?): String = when (diagnostic) {
    "INPUT_PERMISSION_DENIED" -> "termination_input_permission_denied"
    "INPUT_DELIVERY_FAILED" -> "termination_input_delivery_failed"
    "FOREGROUND_IS_DROIDANCE", "CURRENT_SCREEN_REQUIRED" -> "termination_app_screen_required"
    "TOOL_EXECUTION_TIMEOUT" -> "termination_tool_timeout"
    "OBSERVATION_RECOVERY_BUDGET_EXHAUSTED", "PRE_ACTION_OBSERVATION_UNAVAILABLE",
    "POST_ACTION_OBSERVATION_UNAVAILABLE", "MAIN_OBSERVATION_TIMEOUT" -> "termination_observation_unavailable"
    "ARGUMENT_SCHEMA_MISMATCH", "SCREEN_ACT_BATCH_REJECTED" -> "termination_tool_arguments"
    "TOOL_BINDING_MISMATCH" -> "termination_tool_binding_error"
    "TOOL_EXECUTION_FAILED" -> "termination_internal_error"
    else -> "termination_guard_stop"
}
