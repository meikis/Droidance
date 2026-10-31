// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

/** Shared terminal invariant for persistent storage and the in-memory test implementation. */
object TurnSettlementContract {
    fun requireSettledTools(turnId: String, events: List<AgentEventEnvelope>) {
        val turnEvents = events.filter { it.turnId == turnId }
        val lifecycleByCall = turnEvents.filter { it.callId != null }.groupBy { it.callId }
        turnEvents.filter { it.eventKind == AgentEventKind.TOOL_CALL }.forEach { declaration ->
            val lifecycle = lifecycleByCall[declaration.callId].orEmpty().filter {
                it.threadId == declaration.threadId && it.roundId == declaration.roundId &&
                    it.attemptId == declaration.attemptId && it.executionId == declaration.executionId
            }
            val started = lifecycle.count { it.eventKind == AgentEventKind.TOOL_STARTED }
            val rejected = lifecycle.count { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED }
            val finished = lifecycle.count { it.eventKind == AgentEventKind.TOOL_FINISHED }
            if (!((started == 1 && rejected == 0 && finished == 1) ||
                    (started == 0 && rejected == 1 && finished == 0))) {
                throw AgentEventConflictException("Turn $turnId cannot complete with unsettled Tool ${declaration.callId}")
            }
        }
    }
}
