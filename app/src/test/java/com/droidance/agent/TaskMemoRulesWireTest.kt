// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.blob.InMemoryBlobStore
import com.droidance.core.agent.event.InMemoryAgentEventStore
import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.config.markdownSection
import com.droidance.core.model.llm.OpenAIResponsesProtocol
import com.droidance.core.model.llm.OpenAIResponsesTurnState
import com.droidance.core.agent.tools.TaskMemoTool
import java.io.File
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMemoRulesWireTest {
    @Test
    fun runtimeMemoDefinitionCarriesThePackagedOutputContractOnTheProviderWire() {
        val document = File("src/main/assets/rules/workspace.md").readText()
        val outputContract = markdownSection(document, "output_contract")
        val executionPlan = markdownSection(document, "execution_plan")
        val tool = TaskMemoTool(
            TaskWorkspace(InMemoryAgentEventStore(), InMemoryBlobStore()),
            { TurnEventScope("wire-thread", "wire-turn") },
            { section -> markdownSection(document, section) }
        )
        val request = OpenAIResponsesProtocol.buildRequest(
            model = "test-model", messages = emptyList(), tools = listOf(tool.definition),
            systemPrompt = null, supportsVision = false, maxOutputTokens = null,
            state = OpenAIResponsesTurnState()
        )
        val description = request.getValue("tools").jsonArray.single().jsonObject
            .getValue("description").jsonPrimitive.content

        assertTrue(outputContract.isNotBlank())
        assertTrue(executionPlan.isNotBlank())
        assertTrue(description.contains(outputContract))
        assertTrue(description.contains(executionPlan))
        assertTrue(description.indexOf(outputContract) < description.indexOf(executionPlan))
    }
}
