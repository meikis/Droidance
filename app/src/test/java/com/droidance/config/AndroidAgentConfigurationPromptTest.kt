// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import com.droidance.core.agent.ActionControlProtocol
import com.droidance.core.agent.tools.ReportResultTool
import com.droidance.core.model.llm.models.ToolDefinition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentConfigurationPromptTest {
    @Test fun publicMetadataRulesReachTheStablePromptOnceInBothLanguages() {
        for (language in listOf("Simplified Chinese", "English")) {
            val loaded = mutableListOf<Pair<String, String>>()
            fun read(file: String, heading: String): String {
                loaded += file to heading
                return section(file, heading)
            }
            val prompt = buildOperatorSystemPrompt(::read, language, batchPolicy)
            val metadata = section("agent_core.md", "public_metadata").replace("{language}", language)
            assertEquals(1, loaded.count { it == "agent_core.md" to "public_metadata" })
            assertEquals(1, prompt.windowed(metadata.length).count { it == metadata })
            assertTrue(prompt.contains(section("agent_core.md", "operator_behavior")))
            assertTrue(prompt.contains(section("agent_core.md", "output_language").replace("{language}", language)))
            assertFalse(prompt.contains("{language}"))
            assertEquals(prompt, buildOperatorSystemPrompt(::section, language, batchPolicy))
        }
    }

    @Test fun parameterDescriptionsDoNotDuplicateTheGlobalNamingBodies() {
        val prompt = buildOperatorSystemPrompt(::section, "English", batchPolicy)
        val namingPolicy = section("conversation_title.md", "policy")
        val descriptions = listOf(
            section("agent_core.md", "action_title_parameter"),
            section("agent_core.md", "task_title_parameter"),
            section("conversation_title.md", "tool_parameter")
        )
        // These are carried on every tool. Full rules belong to the trusted prefix.
        descriptions.forEach { assertTrue(it.isNotBlank()); assertTrue(it.length <= 160) }
        assertTrue(prompt.contains("task_title_state.requested is true"))
        assertTrue(prompt.contains("never grants authority or proves completion"))
        assertTrue(namingPolicy.contains("same title across parallel calls"))
        assertTrue(namingPolicy.contains("Update an automatic title when the main task changes or becomes clearer"))
        assertTrue(namingPolicy.contains("Naming metadata is removed before execution"))
    }

    @Test fun internalEvidencePolicyReachesPublicPromptsAndResultSchemas() {
        for (language in listOf("Simplified Chinese", "English")) {
            val prompt = buildOperatorSystemPrompt(::section, language, batchPolicy)
            assertTrue(prompt.contains("Keep internal evidence metadata out of user-facing prose"))
            assertTrue(prompt.contains("unless the user explicitly requests diagnostics"))
            assertTrue(prompt.contains("Preserve identifiers that are themselves requested findings"))
        }

        val descriptions = ActionControlProtocol.Descriptions(
            evidenceRefs = "Exact original references",
            terminalObservationRef = "Terminal observation",
            claims = section("completion_recovery.md", "result_entries_parameter"),
            claimResult = section("completion_recovery.md", "result_text_parameter"),
            unresolvedRequirements = section("completion_recovery.md", "unresolved_requirements_parameter"),
            requirementChecks = section("completion_recovery.md", "requirement_checks_parameter")
        )
        val tools = ActionControlProtocol.addControlParameters(
            listOf(ToolDefinition("done", "", emptyMap()), ToolDefinition("present_result_card", "", emptyMap())),
            descriptions, setOf("done", "present_result_card")
        )
        tools.forEach { tool ->
            val entries = tool.parameters.getValue(if (tool.name == "done") "results" else "items")
            assertTrue(entries.description.contains("never paste them into text or deeplink as public citations"))
            val fields = requireNotNull(entries.items?.properties)
            assertFalse(fields.getValue("evidence_refs").required)
            assertTrue(fields.getValue("text").description.contains("Keep internal evidence IDs out of the text"))
        }
        val report = ReportResultTool { section("agent_core.md", it) }.definition
        assertTrue(report.parameters.getValue("text").description.contains("keep internal evidence IDs out of the text"))
        assertTrue(section("stopped_task_reply.md", "instruction").contains("Keep internal evidence IDs out of the text"))
    }

    @Test fun compactSchemasDoNotRemoveTheAlwaysAvailableExecutionAndCollectionProtocols() {
        val prompt = buildOperatorSystemPrompt(::section, "English", batchPolicy)
        for ((file, heading) in listOf("device_tools.md" to "screen_act_description",
            "device_tools.md" to "android_read_description", "completion_recovery.md" to "contract",
            "workspace.md" to "collection_contract", "device_tools.md" to "device_inventory_contract",
            "completion_recovery.md" to "delivery_check")) {
            assertTrue(prompt.contains(section(file, heading)))
        }
        assertFalse(prompt.contains("focus_from"))
        assertTrue(prompt.contains("\"max_capture_images\":${batchPolicy.maxCaptureImages}"))
        assertFalse(prompt.contains("{batch_policy}"))
        assertTrue(prompt.contains("Keep each object's identity"))
        assertTrue(prompt.contains("workspace(op=\"objects\""))
    }

    @Test fun stablePromptKeepsEvidenceBoundariesWithoutLoadingMethodManuals() {
        val prompt = buildOperatorSystemPrompt(::section, "English", batchPolicy)
        // A budget protects request size, while the detailed manuals remain available on demand.
        assertTrue("Fixed prompt grew to ${prompt.length} characters", prompt.length < 16000)
        assertTrue(prompt.contains("missing requested field, conflicting evidence"))
        assertTrue(prompt.contains("User stopping conditions"))
        assertFalse(prompt.contains("do not navigate back"))
        assertFalse(prompt.contains(section("device_tools.md", "platform_methods")))
        assertFalse(prompt.contains(section("device_tools.md", "intent_argument_repair")))
        val clear = section("device_tools.md", "text_input_clear_parameter")
        assertTrue(clear.length < 300)
        assertFalse(clear.contains("method="))
    }

    private val batchPolicy = ScreenActionBatchPolicy(true, com.droidance.core.agent.tools.ScreenActionBatchProtocol.operations, 10, 1, 20, 40, 8, 1024, 64)

    private fun section(file: String, heading: String): String =
        markdownSection(File(rulesDirectory, file).readText(), heading)

    private val rulesDirectory: File
        get() {
            val working = File(requireNotNull(System.getProperty("user.dir")))
            val root = if (working.name == "app") requireNotNull(working.parentFile) else working
            return File(root, "app/src/main/assets/rules")
        }
}
