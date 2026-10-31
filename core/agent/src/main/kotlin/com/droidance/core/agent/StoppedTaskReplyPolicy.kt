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

/** Only execution diagnostics select these localized runtime notices; no task semantics. */
internal fun guardStopNoticeKey(diagnostic: String?): String = when (diagnostic) {
    "FOREGROUND_IS_DROIDANCE", "CURRENT_SCREEN_REQUIRED" -> "termination_app_screen_required"
    "TOOL_EXECUTION_TIMEOUT" -> "termination_tool_timeout"
    "OBSERVATION_RECOVERY_BUDGET_EXHAUSTED", "PRE_ACTION_OBSERVATION_UNAVAILABLE",
    "POST_ACTION_OBSERVATION_UNAVAILABLE", "MAIN_OBSERVATION_TIMEOUT" -> "termination_observation_unavailable"
    "ARGUMENT_SCHEMA_MISMATCH", "SCREEN_ACT_BATCH_REJECTED" -> "termination_tool_arguments"
    else -> "termination_guard_stop"
}
