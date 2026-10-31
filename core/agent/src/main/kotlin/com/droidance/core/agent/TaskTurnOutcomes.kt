// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.harness.DurableModelRoundCoordinator
import com.droidance.core.agent.event.ToolExecutionResult

internal sealed interface ToolRoundOutcome {
    data object ContinueRound : ToolRoundOutcome
    data object RestartRound : ToolRoundOutcome
    data class Terminal(val result: TurnResult) : ToolRoundOutcome
}

internal data class ModelRoundOutput(
    val toolCalls: Map<Int, ToolCallBuilder>,
    val content: String,
    val reasoning: String,
    val pendingScreenshot: String?,
    val nativeFrameBytes: ByteArray?,
    val sourceObservationId: String?,
    val pendingFrameSize: Pair<Int, Int>?,
    val pendingPhysicalFrameSize: Pair<Int, Int>?,
    val canAttachScreenshot: Boolean,
    val inputMetadata: Map<String, String>,
    val presentedToolNames: Set<String>,
    val textObservation: String?,
    val detailId: String,
    val reusePostActionObservation: Boolean,
    val durableRound: DurableModelRoundCoordinator.RoundHandle,
    val titleRequest: ConversationTitleSession.Request,
    val taskTitleRequested: Boolean,
    val mutationsRestricted: Boolean = false
)

internal data class CompletionEvidenceGuards(
    val evidenceBlockResult: ToolExecutionResult?
)

internal data class ToolCallGuardEvaluation(
    val repeatObservationKey: String?,
    val exactRepeatEligible: Boolean,
    val exactActionRepeatBlockResult: ToolExecutionResult?,
    val blindActionBlockResult: ToolExecutionResult?,
    val staleObservationBlockResult: ToolExecutionResult?,
    val successfulDoneRequested: Boolean,
    val completionEvidenceBlockResult: ToolExecutionResult?,
    val hardBlockResult: ToolExecutionResult?,
    val prematureTaskDone: Boolean,
    val guardedRejectionResult: ToolExecutionResult?,
    val rejectedCompletionResult: ToolExecutionResult?,
    val locallyRejectedResult: ToolExecutionResult?
)

internal sealed interface ModelRoundOutcome {
    data class Ready(val output: ModelRoundOutput) : ModelRoundOutcome
    data object RestartRound : ModelRoundOutcome
    data class Terminal(val result: TurnResult) : ModelRoundOutcome
}

internal fun completionTerminal(status: String, executionStarted: Boolean): TurnTerminal = when (status) {
    "done" -> if (executionStarted) TurnTerminal.DONE else TurnTerminal.DIRECT_REPLY
    "need_more_info" -> TurnTerminal.NEED_MORE_INFO
    else -> TurnTerminal.FAILED
}
