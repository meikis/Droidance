// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.ToolExecutionResult
import kotlinx.coroutines.flow.MutableStateFlow

/** Runtime-owned lifecycle and presentation boundary for a single turn. */
interface TurnHost {
    val onPrepareDevice: suspend () -> com.droidance.core.agent.device.DeviceExecutionStrategy? get() = { null }
    val onExecutionStarted: suspend (firstToolName: String) -> Unit get() = {}
    val onStreamContent: suspend (AssistantStreamChunk) -> Unit get() = {}
    val onActionUpdate: (String) -> Unit get() = {}
    val onTaskTitle: (String) -> Unit get() = {}
    val onTaskDeadline: suspend (TaskDeadlineProtocol.Bound) -> Unit get() = {}
    val checkTaskDeadline: () -> Unit get() = {}
    val onManualTakeoverNeeded: (reason: String) -> Unit get() = {}
    val stateFlow: MutableStateFlow<AgentState>? get() = null
    val waitIfPaused: suspend () -> Unit get() = {}
    val isPaused: () -> Boolean get() = { false }
    val runInterruptibleTool: suspend (suspend () -> ToolExecutionResult) -> ToolExecutionResult get() = { it() }
    val takeManualResumeInstruction: () -> String? get() = { null }
    /** Rebuilds the memory context for the Turn's user text and, when observed, its foreground package. */
    val refreshMemoryContext: suspend (String, String?) -> com.droidance.core.agent.event.MemoryContextLoadedEventPayload? get() = { _, _ -> null }
    val trySealInput: suspend () -> Boolean get() = { true }
    val roundContext: () -> String get() = { "" }
}
