// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.tools.CompletionState
import com.droidance.tools.CompletionToolRegistrar
import com.droidance.core.agent.tools.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionTerminalTest {
    @Test
    fun firstCompletionUsesTheSameSchemaValidationAsLaterRounds() {
        val tools = mutableListOf<Tool>()
        CompletionToolRegistrar({ tools.add(it) }, { CompletionState() }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, state ->
            if (state.record(candidate)) com.droidance.core.agent.tools.ToolProtocolResult.success("recorded")
            else com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        val definition = tools.single().definition
        val scheduler = OperatorRoundScheduler(listOf(RegisteredToolContract(
            name = definition.name, description = definition.description,
            binding = ToolBinding.TURN_LOCAL, effect = ToolEffect.COMPLETION,
            parameters = definition.parameters
        )))
        for (started in listOf(false, true)) {
            for (args in listOf(
                """{"status":"done","summary":123}""",
                """{"status":"done","summary":"OK","notify_user":"false"}""",
                """{"status":"unknown","summary":"OK"}"""
            )) {
                val plan = scheduler.plan(emptyList(), listOf(ToolCall("done", "done", args)),
                    startCommitted = started, presentedToolNames = setOf("done"))
                assertTrue(plan.selectedCalls.isEmpty())
                assertEquals("ARGUMENT_SCHEMA_MISMATCH", plan.rejectedCalls.single().diagnosticCode)
            }
        }
    }
}
