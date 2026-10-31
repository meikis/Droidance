// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.harness.RegisteredToolContract
import com.droidance.core.agent.harness.ToolArgumentSchemaDiagnosticCode
import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.agent.event.ToolExecutionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionToolRegistrarTest {
    @Test
    fun fullMarkdownBodyIsPreservedIndependentlyOfEvidenceClaims() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tools[it.name] = it }, { state }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        val body = "# Findings\n\n" + (1..60).joinToString("\n") { "- Source $it: observed value and qualification." }
        val arguments = kotlinx.serialization.json.buildJsonObject {
            put("status", kotlinx.serialization.json.JsonPrimitive("done"))
            put("results", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(body)); put("evidence_refs", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("source"))))
            }) }); put("unresolved_requirements", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        tools.getValue("done").execute(arguments.toString())
        assertEquals(body, state.result?.summary)
        assertEquals("Complete public answer", tools.getValue("done").definition.parameters.getValue("results").description)
    }

    @Test
    fun doneRecordsStatusAndSummaryOnce() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tool -> tools[tool.name] = tool }, { state }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        assertEquals(
            listOf("done", "failed", "need_more_info"),
            tools.getValue("done").definition.parameters.getValue("status").enumValues
        )
        assertEquals("Outcome status policy", tools.getValue("done").definition.parameters.getValue("status").description)

        val first = tools.getValue("done").execute(
            """{"status":"done","results":[{"text":"完成","evidence_refs":["source"]}],"unresolved_requirements":[]}"""
        )
        val second = tools.getValue("done").execute(
            """{"status":"failed","results":[{"text":"不应覆盖","evidence_refs":["source"]}],"unresolved_requirements":[]}"""
        )

        assertEquals(CompletionResult("done", "完成"), state.result)
        assertEquals(ToolExecutionStatus.SUCCEEDED, first.executionStatus)
        assertTrue(first.outputText.contains("recorded", ignoreCase = true))
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, second.executionStatus)
        assertEquals(
            "COMPLETION_ALREADY_RECORDED",
            second.diagnosticCode
        )
    }

    @Test
    fun doneRejectsUnknownStatusAndBlankSummary() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tool -> tools[tool.name] = tool }, { state }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()

        val unknownStatus = tools.getValue("done").execute("""{"status":"partial","results":[{"text":"x","evidence_refs":["source"]}],"unresolved_requirements":[]}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, unknownStatus.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", unknownStatus.diagnosticCode)
        val blankSummary = tools.getValue("done").execute("""{"status":"done","results":[{"text":" ","evidence_refs":["source"]}],"unresolved_requirements":[]}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, blankSummary.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", blankSummary.diagnosticCode)
        assertNull(state.result)
    }

    @Test
    fun doneRejectsStringBooleans() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tool -> tools[tool.name] = tool }, { state }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        val done = tools.getValue("done")

        val result = done.execute("""{"status":"done","results":[{"text":"x","evidence_refs":["source"]}],"unresolved_requirements":[],"goal_complete":"true"}""")
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", result.diagnosticCode)
        assertNull(state.result)
    }

    @Test
    fun doneSchemaSupportsPersistentTurnCheckpointFields() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tool -> tools[tool.name] = tool }, { state }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()

        val tool = tools.getValue("done")
        assertEquals(
            setOf("status", "results", "unresolved_requirements", "experience_updates", "checkpoint", "next_check_seconds", "goal_complete", "notify_user"),
            tool.definition.parameters.keys
        )
    }

    @Test
    fun malformedOptionalExperienceIsDeferredWithoutWeakeningCompletionValidation() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tools[it.name] = it }, { state }, "Complete public answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            if (current.record(candidate)) ToolProtocolResult.success("recorded")
            else ToolProtocolResult.notExecuted("COMPLETION_ALREADY_RECORDED", false)
        }.registerAll()
        val done = tools.getValue("done")
        val contract = RegisteredToolContract(done.name, done.definition.description,
            ToolBinding.TURN_LOCAL, ToolEffect.COMPLETION, done.definition.parameters)
        assertFalse(done.definition.parameters.getValue("experience_updates").required)
        assertTrue(done.definition.parameters.getValue("experience_updates").deferredValidation)

        val malformedExperience = """{"status":"done","results":[{"text":"Completed","evidence_refs":["obs-current"]}],"unresolved_requirements":[],"experience_updates":"invalid optional method"}"""
        assertNull(ToolArgumentSchemaValidator.diagnosticFor(contract, malformedExperience))
        assertEquals(ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
            ToolArgumentSchemaValidator.diagnosticFor(contract, malformedExperience.replace("\"status\":\"done\"", "\"status\":false")))

        val result = done.execute(malformedExperience)
        assertEquals(ToolExecutionStatus.SUCCEEDED, result.executionStatus)
        assertEquals("Completed", state.result?.summary)
    }

    @Test
    fun cumulativeStateIsNotClippedAndTimedContinuationIsDistinctFromGoalCompletion() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tools[it.name] = it }, { state }, "Answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            current.record(candidate)
            ToolProtocolResult.success("recorded")
        }.registerAll()
        val checkpoint = "object-A:waiting;object-C:answered;" + "evidence-and-effects;".repeat(300)
        val args = kotlinx.serialization.json.buildJsonObject {
            put("status", kotlinx.serialization.json.JsonPrimitive("done"))
            put("results", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive("Progress saved; goal continues.")) })))
            put("checkpoint", kotlinx.serialization.json.JsonPrimitive(checkpoint))
            put("next_check_seconds", kotlinx.serialization.json.JsonPrimitive(30))
            put("unresolved_requirements", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        assertTrue(tools.getValue("done").execute(args.toString()).isVerifiedSuccess)
        assertEquals(checkpoint, state.result!!.checkpoint)
        assertEquals(30L, state.result!!.nextCheckSeconds)
        assertFalse(state.result!!.goalComplete)
    }

    @Test
    fun invalidContinuationNeverCommitsACompletion() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tools[it.name] = it }, { state }, "Answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            current.record(candidate)
            ToolProtocolResult.success("recorded")
        }.registerAll()
        for (extra in listOf("\"next_check_seconds\":-1", "\"next_check_seconds\":\"1\"",
            "\"next_check_seconds\":9223372036854775807", "\"goal_complete\":true,\"next_check_seconds\":0",
            "\"checkpoint\":\"${"x".repeat(16_001)}\"")) {
            val result = tools.getValue("done").execute("""{"status":"done","results":[{"text":"Progress"}],"unresolved_requirements":[],$extra}""")
            assertEquals(extra.take(80), ToolExecutionStatus.NOT_EXECUTED, result.executionStatus)
            assertNull(state.result)
        }
    }

    @Test fun omittedGapsCannotCommitButListedGapsCommitWithTheAnswer() = runBlocking {
        val state = CompletionState()
        val tools = mutableMapOf<String, Tool>()
        CompletionToolRegistrar({ tools[it.name] = it }, { state }, "Answer", "Outcome status policy", { emptyMap() }) { candidate, current ->
            current.record(candidate)
            ToolProtocolResult.success("recorded")
        }.registerAll()
        assertTrue(tools.getValue("done").definition.parameters.getValue("unresolved_requirements").required)
        assertEquals(ToolExecutionStatus.NOT_EXECUTED,
            tools.getValue("done").execute("""{"status":"done","results":[{"text":"Partial"}]}""").executionStatus)
        assertNull(state.result)
        assertTrue(tools.getValue("done").execute(
            """{"status":"done","results":[{"text":"Partial"}],"unresolved_requirements":["Required field remains unread"]}""").isVerifiedSuccess)
        assertTrue(tools.getValue("done").execute("""{"status":"done","results":[{"text":"Field unknown as explicitly allowed by the user"}],"unresolved_requirements":[]}""").isVerifiedSuccess)
    }
}
