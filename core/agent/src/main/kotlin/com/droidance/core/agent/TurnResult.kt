// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

/** Canonical result for one user turn, shared by the model loop and runtime. */
data class TurnResult(
    /** Whether the turn executed at least one tool call. */
    val hasToolCalls: Boolean,
    /** Tools actually executed during this turn. */
    val toolNames: List<String> = emptyList(),
    /** Concise terminal summary. */
    val summary: String,
    /** Grounded evidence for terminal validation; not durable memory by itself. */
    val evidenceText: String = "",
    /** User-visible model output after runtime terminal normalization. */
    val operatorOutput: String = "",
    /** Model rounds consumed by this turn. */
    val rounds: Int = 0,
    /** Whether the model explicitly called done; runtime failures still take precedence. */
    val taskDoneByModel: Boolean = false,
    /** Status argument passed to done, when present. */
    val taskDoneStatus: String? = null,
    /** Runtime-level stop category, separate from user-facing wording. */
    val failureReason: TurnFailureReason = TurnFailureReason.NONE,
    /** Canonical terminal state for this turn. */
    val terminal: TurnTerminal = TurnTerminal.FAILED,
    /** Whether public content has already been streamed. */
    val contentStreamed: Boolean = false,
    /** Whether persistent takeover configuration was stored successfully. */
    val takeoverManaged: Boolean = false,
    /** Explicit runtime-authored failure notice; never populated from candidate model output. */
    val runtimeFailureSummary: String? = null,
    /** Committed no-tool explanation of an already stopped task; not a completion verdict. */
    val modelFailureSummary: String? = null,
    /** Exact runtime stop trigger; a guard rejection does not imply device stagnation. */
    val stopDiagnostic: String? = null
)

enum class TurnTerminal {
    DIRECT_REPLY,
    DONE,
    NEED_MORE_INFO,
    FAILED,
    SYSTEM_ERROR
}

enum class TurnFailureReason {
    NONE,
    TASK_TIME_LIMIT,
    CHANNEL_DOWN,
    MODEL_TIMEOUT,
    MODEL_UNAVAILABLE,
    MODEL_PROTOCOL_ERROR,
    CONTEXT_COMPACTION_FAILED,
    GUARD_STOP,
    RUNTIME_EXCEPTION
}
