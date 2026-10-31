// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.LLMStreamEvent
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProtocolRepairIntegrationTest {
    @Test fun modelReceivesOwnedSemanticsAfterAllPublicParametersAreAdded() = runBlocking {
        val statusDescription = ruleSection("completion_recovery.md", "status_parameter")
        val schemaPolicy = ruleSection("tool_context.md", "schema_context_policy")
        val parameterDescriptions = Json.decodeFromString<com.droidance.core.agent.tools.ToolSchemaContextPolicy>(schemaPolicy)
            .parameterDescriptions
        val descriptions = parameterDescriptions.getValue("done")
        // This fixture's done definition carries only status; drop documentation for its other paths.
        val fixturePolicy = Json.encodeToString(com.droidance.core.agent.tools.ToolSchemaContextPolicy.serializer(),
            Json.decodeFromString<com.droidance.core.agent.tools.ToolSchemaContextPolicy>(schemaPolicy).let { policy ->
                policy.copy(parameterDescriptions = policy.parameterDescriptions + ("done" to
                    policy.parameterDescriptions.getValue("done").filterKeys { it.startsWith("status") ||
                        it.startsWith("results") || it.startsWith("unresolved_requirements") || it.startsWith("requirement_checks") }) +
                    // The fixture card carries no persistent-goal fields.
                    ("present_result_card" to policy.parameterDescriptions.getValue("present_result_card").filterKeys {
                        it !in setOf("checkpoint", "next_check_seconds", "goal_complete", "notify_user") }),
                    // A fixture tool stands in for the configured rarely used tools.
                    helpOnlyParameterTools = policy.helpOnlyParameterTools + "ask_human")
            })
        val fixture = TaskCoordinatorTestFixture(toolSchemaPolicy = fixturePolicy)
        fixture.toolExecutor.definitionOverride = { name ->
            if (name == "done") ToolDefinition(name, "Finish with the requested outcome", mapOf(
                "status" to ToolParameter("string", statusDescription, true,
                    enumValues = listOf("done", "failed", "need_more_info"))))
            else if (name == "screen_act") ToolDefinition(name, "Act on the bound display", mapOf(
                "actions" to ToolParameter("array", "Ordered screen primitives", true,
                    items = ToolParameter("object", "One screen primitive.", properties = mapOf(
                        "op" to ToolParameter("string", "Screen primitive operation."),
                        "ms" to ToolParameter("integer", "Bounded duration", false)))),
                "wait_for_frame_ms" to ToolParameter("integer", "Optional pause", false)))
            else null
        }
        val started = fixture.startTurn("schema-documentation", "Read the requested information", activate = false)
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("The information is read."))
        fixture.coordinator.coordinateTask(taskGoal = "Read the requested information", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        val tools = fixture.model.recordedTools.first()
        val workspace = tools.single { it.name == "workspace" }
        assertTrue(workspace.parameters.getValue("task_title").description.contains("agent_core.md#task_title_parameter"))
        assertTrue(workspace.parameters.getValue("conversation_title").description.contains("conversation_title.md#tool_parameter"))
        val deadline = workspace.parameters.getValue("task_deadline")
        assertTrue(deadline.description.contains("agent_core.md#task_deadline_parameter"))
        assertEquals("object", deadline.type)
        assertTrue(deadline.properties!!.getValue("duration_seconds").description.isNotBlank())
        assertEquals("integer", deadline.properties!!.getValue("duration_seconds").type)
        assertTrue(deadline.properties!!.containsKey("deadline_at"))
        assertFalse(deadline.required)
        val askHuman = tools.single { it.name == "ask_human" }
        assertEquals("", askHuman.parameters.getValue("boundary_type").description)
        assertEquals(5, askHuman.parameters.getValue("boundary_type").enumValues!!.size)
        assertTrue(askHuman.parameters.getValue("task_title").description.contains("agent_core.md#task_title_parameter"))
        val screenAct = tools.single { it.name == "screen_act" }
        assertEquals(parameterDescriptions.getValue("screen_act").getValue("wait_for_frame_ms"),
            screenAct.parameters.getValue("wait_for_frame_ms").description)
        assertEquals(parameterDescriptions.getValue("screen_act").getValue("actions[].ms"),
            screenAct.parameters.getValue("actions").items!!.properties!!.getValue("ms").description)
        val done = tools.single { it.name == "done" }
        assertEquals(descriptions.getValue("status"), done.parameters.getValue("status").description)
        assertEquals(listOf("done", "failed", "need_more_info"), done.parameters.getValue("status").enumValues)
        assertEquals(descriptions.getValue("results"), done.parameters.getValue("results").description)
        assertEquals(descriptions.getValue("results[].text"), done.parameters.getValue("results").items!!.properties!!.getValue("text").description)
        assertEquals(descriptions.getValue("unresolved_requirements"), done.parameters.getValue("unresolved_requirements").description)
    }
    @Test fun fieldRepairKeepsPriorWorkAndAValidReadAllowsTheAgentToChooseWhatFollows() = runBlocking {
        val fixture = TaskCoordinatorTestFixture()
        val started = fixture.startTurn("field-repair", "Retain the findings", activate = false)
        fixture.toolExecutor.resultOverride = { call, _ ->
            if (call.name == "workspace" && "new_text" in call.arguments)
                ToolProtocolResult.notExecuted("ARGUMENT_SCHEMA_MISMATCH", true,
                    details = buildJsonObject { put("missing_parameter", "text") })
            else null
        }
        fun call(id: String, args: String) = listOf(LLMStreamEvent.ToolCallChunk(0, id, "workspace", args))
        fixture.model.roundResponses += call("seed", """{"op":"write","path":"work.md","text":"supported finding"}""")
        fixture.model.roundResponses += call("rejected", """{"op":"edit","path":"work.md","new_text":"retained addition"}""")
        fixture.model.roundResponses += call("read", """{"op":"read","path":"work.md"}""")
        fixture.model.roundResponses += call("repair", """{"op":"edit","path":"work.md","old_text":"supported finding","text":"retained addition","expected_ref":"inspected-version"}""")
        fixture.model.roundResponses += listOf(LLMStreamEvent.Content("The findings are retained."))
        fixture.coordinator.coordinateTask(taskGoal = "Retain the findings", additionalContext = "",
            durableTurn = started.active, turnContext = "", expectedBackend = null,
            executionStrategy = started.executionStrategy, toolProfile = started.profile, mode = started.mode)
        val repair = fixture.model.recordedRequests[2]
        assertTrue(repair.any { message -> message.toolCalls.orEmpty().any { "supported finding" in it.arguments } })
        val feedback = repair.mapNotNull { runCatching { Json.parseToJsonElement(it.content).jsonObject }.getOrNull() }
            .single { "rejected_call_id" in it }
        assertEquals("retained addition", feedback.getValue("arguments").jsonObject["new_text"]?.jsonPrimitive?.content)
        assertFalse(fixture.model.recordedRequests[3].any { "rejected_call_id" in it.content })
        assertFalse(fixture.model.recordedRequests[4].any { "rejected_call_id" in it.content })
        assertTrue(fixture.toolExecutor.deviceCalls.isEmpty())
        val rounds = fixture.eventStore.eventsForTurn(started.active.scope.turnId)
            .filter { it.eventKind == com.droidance.core.agent.event.AgentEventKind.MODEL_ROUND_COMPLETED }
            .map { Json.decodeFromString<com.droidance.core.agent.event.ModelRoundCompletedEventPayload>(it.payloadJson).finishMetadata }
        assertEquals("true", rounds[2]["protocol_repair_active"])
        assertEquals("false", rounds[2]["source_context_isolated"])
        assertTrue("A field repair keeps the cacheable history prefix", repair.any { it.cacheablePrefix })
        assertEquals("false", rounds[3]["protocol_repair_active"])
        assertEquals("false", rounds[3]["source_context_isolated"])
    }

    private fun ruleSection(fileName: String, heading: String): String {
        val directory = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "app/src/main/assets/rules") }.first { it.isDirectory }
        val lines = File(directory, fileName).readLines()
        val start = lines.indexOf("## $heading")
        check(start >= 0) { "Missing rule section $fileName#$heading" }
        return lines.drop(start + 1).takeWhile { !it.startsWith("## ") }.joinToString("\n").trim()
    }
}
