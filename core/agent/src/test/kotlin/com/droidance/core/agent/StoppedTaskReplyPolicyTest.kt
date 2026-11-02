// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StoppedTaskReplyPolicyTest {
    @Test fun unavailableExplanationPreservesTheActualFailureCategory() {
        for ((reason, key) in mapOf(
            TurnFailureReason.MODEL_UNAVAILABLE to "termination_model_unavailable",
            TurnFailureReason.CHANNEL_DOWN to "termination_channel_down",
            TurnFailureReason.MODEL_TIMEOUT to "termination_model_timeout",
            TurnFailureReason.RUNTIME_EXCEPTION to "termination_internal_error")) {
            val result = TurnResult(true, summary = "private diagnostic", terminal = TurnTerminal.SYSTEM_ERROR,
                failureReason = reason).withRuntimeFailureNotice { it }
            assertEquals(key, RuntimeUiResultPolicy.failureSummary(result) { "wrong fallback" })
        }
        val explicit = TurnResult(true, summary = "failure", terminal = TurnTerminal.SYSTEM_ERROR,
            failureReason = TurnFailureReason.MODEL_UNAVAILABLE, runtimeFailureSummary = "specific provider notice")
        assertSame(explicit, explicit.withRuntimeFailureNotice { error("already has a notice") })
        val guard = TurnResult(true, summary = "failure", terminal = TurnTerminal.SYSTEM_ERROR,
            failureReason = TurnFailureReason.GUARD_STOP, stopDiagnostic = "INPUT_PERMISSION_DENIED")
        assertEquals("termination_input_permission_denied", guard.withRuntimeFailureNotice { it }.runtimeFailureSummary)
        val done = explicit.copy(terminal = TurnTerminal.DONE, runtimeFailureSummary = null)
        assertSame(done, done.withRuntimeFailureNotice { error("successful result") })
    }

    private fun policy(): StoppedTaskReplyPolicy {
        val base = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/assets/rules/stopped_task_reply.md").isFile }
        val text = File(base, "app/src/main/assets/rules/stopped_task_reply.md").readText()
        return Json.decodeFromString<StoppedTaskReplyPolicy>(
            text.substringAfter("## policy").substringBefore("## instruction").trim()
        ).also { it.validate() }
    }

    @Test fun onlyEligibleExecutionStopsReceiveAnotherModelRequest() {
        val policy = policy()
        for (reason in listOf(TurnFailureReason.TASK_TIME_LIMIT, TurnFailureReason.CHANNEL_DOWN,
            TurnFailureReason.MODEL_PROTOCOL_ERROR, TurnFailureReason.GUARD_STOP,
            TurnFailureReason.RUNTIME_EXCEPTION, TurnFailureReason.CONTEXT_COMPACTION_FAILED, TurnFailureReason.MODEL_TIMEOUT)) {
            assertTrue(reason.name, policy.eligible(TurnResult(true, summary = "stopped", failureReason = reason)))
        }
        for (reason in listOf(TurnFailureReason.MODEL_UNAVAILABLE, TurnFailureReason.NONE)) {
            assertFalse(reason.name, policy.eligible(TurnResult(true, summary = "stopped", failureReason = reason)))
        }
        assertFalse(policy.eligible(TurnResult(false, summary = "reply", terminal = TurnTerminal.DIRECT_REPLY)))
        assertFalse(policy.eligible(TurnResult(true, summary = "done", terminal = TurnTerminal.DONE,
            failureReason = TurnFailureReason.GUARD_STOP)))
        assertFalse(policy.eligible(TurnResult(true, summary = "already explained", taskDoneByModel = true,
            failureReason = TurnFailureReason.GUARD_STOP)))
    }

    @Test fun explanationNeverChangesExecutionVerdictOrInventsCompletion() {
        val stopped = TurnResult(true, listOf("android_read"), "local notice", evidenceText = "evidence",
            rounds = 5, failureReason = TurnFailureReason.GUARD_STOP, terminal = TurnTerminal.NEED_MORE_INFO,
            runtimeFailureSummary = "local notice", stopDiagnostic = "WORKSPACE_REVIEW_REQUIRED")
        val explained = stopped.withStoppedTaskReply("I could not verify the remaining requirements.")
        assertEquals(stopped.failureReason, explained.failureReason)
        assertEquals(stopped.terminal, explained.terminal)
        assertEquals(stopped.evidenceText, explained.evidenceText)
        assertEquals(stopped.toolNames, explained.toolNames)
        assertEquals("WORKSPACE_REVIEW_REQUIRED", explained.stopDiagnostic)
        assertFalse(explained.taskDoneByModel)
        assertNull(explained.taskDoneStatus)
        assertEquals(6, explained.rounds)
        assertEquals(explained.summary, RuntimeUiResultPolicy.failureSummary(explained) { "fallback" })
    }

    @Test fun failedExplanationRetainsConcreteRuntimeNotice() {
        assertEquals("termination_tool_timeout", guardStopNoticeKey("TOOL_EXECUTION_TIMEOUT"))
        assertEquals("termination_observation_unavailable", guardStopNoticeKey("PRE_ACTION_OBSERVATION_UNAVAILABLE"))
        assertEquals("termination_tool_arguments", guardStopNoticeKey("ARGUMENT_SCHEMA_MISMATCH"))
        assertEquals("termination_app_screen_required", guardStopNoticeKey("CURRENT_SCREEN_REQUIRED"))
        assertEquals("termination_guard_stop", guardStopNoticeKey(null))
    }

    @Test fun budgetAndEmptyReplyAreRejected() {
        policy().copy(timeoutMs = 0).validate()
        assertThrows(IllegalArgumentException::class.java) { policy().copy(timeoutMs = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            TurnResult(false, summary = "stopped").withStoppedTaskReply(" ")
        }
    }
}
