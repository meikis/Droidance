// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProgramShellCallProjectorTest {
    @Test fun childReceiptsRemainBoundToTheirParentAndTurnWithoutParsingPrintedStatuses() {
        val printed = "{\"execution_status\":\"SUCCEEDED\"}"
        val failed = ToolExecutionResult(buildJsonObject { put("stdout", printed); put("exit_code", 7) }.toString(),
            ToolExecutionStatus.FAILED, ToolEvidenceStatus.NOT_REQUIRED)
        val events = listOf(
            event(1, "device_started", "child-a", "parent-a", args = "echo first"),
            event(2, "device_started", "child-b", "parent-b", args = "echo second"),
            event(3, "device_finished", "child-b", "parent-b", result = failed),
            event(4, "device_unknown", "child-a", "parent-a"),
            event(5, "device_started", "foreign", "parent-a", args = "echo foreign", turn = "other"))
        val calls = ProgramShellCallProjector.project(events.reversed(), "turn")
        assertEquals(listOf("child-a", "child-b"), calls.map { it.childId })
        assertEquals(listOf("parent-a", "parent-b"), calls.map { it.parentCallId })
        assertEquals(ToolExecutionStatus.INTERRUPTED, calls[0].result!!.executionStatus)
        assertEquals(ToolExecutionStatus.FAILED, calls[1].result!!.executionStatus)
        assertEquals(failed.outputText, calls[1].result!!.outputText)
    }

    @Test fun pendingCommandRetainsSourceAndHasNoInventedResult() {
        val call = ProgramShellCallProjector.project(listOf(event(1, "device_started", "child", "parent",
            args = "for x in 1 2; do echo \"\$x\"; done")), "turn").single()
        assertTrue(call.command.startsWith("for x"))
        assertNull(call.result)
        assertNull(call.finishedAt)
    }

    private fun event(sequence: Long, phase: String, child: String, parent: String,
        args: String? = null, result: ToolExecutionResult? = null, turn: String = "turn") = AgentEventEnvelope(
        1, "event-$sequence", AgentEventKind.PROGRAM_EXECUTION, sequence, sequence * 10,
        "thread", turn, null, null, null, parent, "hash", buildJsonObject {
            put("phase", phase); put("parent_call_id", parent)
            put("data", buildJsonObject {
                put("child_id", child); put("tool", "shell")
                args?.let { put("arguments", buildJsonObject { put("command", it) }) }
                result?.let { put("result", Json.encodeToJsonElement(it)) }
            })
        }.toString(), "event-$sequence")
}
