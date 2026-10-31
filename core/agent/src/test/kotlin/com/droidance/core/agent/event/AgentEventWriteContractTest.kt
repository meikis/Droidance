// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionAttemptTransitions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventWriteContractTest {
    @Test
    fun tool_scope_requires_attempt_and_execution_identity_together() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolLifecycleEventScope(
                threadId = "thread",
                turnId = "turn",
                roundId = "round",
                callId = "call",
                attemptId = "attempt"
            )
        }
    }

    @Test
    fun turn_result_requires_one_non_blank_public_terminal_message() {
        assertThrows(IllegalArgumentException::class.java) {
            TurnResult(status = "COMPLETED", assistantReply = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnResult(
                status = "COMPLETED",
                assistantReply = "done",
                systemNotice = "failed"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnResult(status = "COMPLETED", systemNotice = "failed", totalDurationMillis = -1L)
        }
    }

    @Test
    fun attempt_transition_graph_has_no_terminal_escape_or_pause_shortcut() {
        assertTrue(
            ExecutionAttemptTransitions.allows(
                ExecutionAttemptState.CREATED,
                ExecutionAttemptState.RUNNING
            )
        )
        assertFalse(
            ExecutionAttemptTransitions.allows(
                ExecutionAttemptState.CREATED,
                ExecutionAttemptState.PAUSED_USER
            )
        )
        ExecutionAttemptTransitions.terminalStates.forEach { terminal ->
            ExecutionAttemptState.entries.forEach { next ->
                assertFalse(ExecutionAttemptTransitions.allows(terminal, next))
            }
        }
    }
}
