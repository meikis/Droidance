// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPolicySourceContractTest {
    @Test fun autonomyRulesReachTheLoadedPromptWithoutGlobalCollectionRecipes() {
        val core = projectFile("app/src/main/assets/rules/agent_core.md").readText()
        val active = com.droidance.config.markdownSection(core, "operator_behavior")
        val prompt = com.droidance.config.buildOperatorSystemPrompt({ file, heading ->
            com.droidance.config.markdownSection(projectFile("app/src/main/assets/rules/$file").readText(), heading)
        }, "English", com.droidance.core.agent.config.ScreenActionBatchPolicy(true, com.droidance.core.agent.tools.ScreenActionBatchProtocol.operations, 10, 1, 20, 40, 8, 1024, 64))
        assertTrue(prompt.contains(active))
        assertTrue(prompt.contains("Plan each batch from the current frame"))
        assertTrue(prompt.contains("How Android interfaces behave"))
        assertTrue(active.contains("Structured records are optional memory for longer work"))
        assertFalse(active.contains("Different listings or stores remain separate entries"))
        assertFalse(active.contains("Count the exact eligible entries"))
        val device = projectFile("app/src/main/assets/rules/device_tools.md").readText()
        assertFalse(device.contains("## source_collection"))
        assertFalse(device.contains("## screen_decision"))
    }

    @Test fun namingPolicyReachesTheSystemPromptAndOnlyNamingStateIsDynamic() {
        val coordinator = com.droidance.agent.coordinationSources()
        val systemBuilder = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/TaskTurnServices.kt")
            .readText().substringAfter("internal fun trustedOperatorSystemPrompt(")
            .substringBefore("internal fun guardText(")
        assertTrue(systemBuilder.contains("readMarkdownSection(\"conversation_title.md\", \"policy\")"))
        val dynamicContext = coordinator.substringAfter("val titleContext = ").substringBefore("val requestMemoryText")
        assertFalse(dynamicContext.contains("readMarkdownSection"))
        val module = projectFile("app/src/main/java/com/droidance/di/AppModule.kt").readText()
        assertFalse(module.contains("provideTurnActivationGate"))
        val policy = projectFile("app/src/main/assets/rules/conversation_title.md").readText()
        assertTrue(policy.contains("update_allowed is true"))
        assertTrue(policy.contains("Keep a fitting title for ordinary refinements"))
    }

    @Test
    fun modelSystemPromptsAreNotEmbeddedAsKotlinStringLiterals() {
        val offenders = productionKotlinFiles()
            .filter { Regex("systemPrompt\\s*=\\s*\\\"").containsMatchIn(it.readText()) }
            .map { it.relativeTo(projectRoot).path }
            .toList()

        assertTrue("system prompts must come from Markdown policy: $offenders", offenders.isEmpty())
    }

    @Test
    fun deviceProfileUsesEnglishGenericFieldsWithoutAppSpecificDetection() {
        val onboarding = File("src/main/java/com/droidance/ui/viewmodel/OnboardingViewModel.kt").readText()

        assertTrue(onboarding.contains("appendLine(\"# Device Profile\")"))
        assertTrue(onboarding.contains("appendLine(\"## Runtime capabilities\")"))
        assertTrue(onboarding.contains("appendLine(\"## Installed user applications ("))
        assertFalse(onboarding.contains("cameraPackage"))
        listOf("com.oneplus.camera", "com.oppo.camera", "com.miui.camera", "com.huawei.camera").forEach {
            assertFalse(onboarding.contains(it))
        }
    }

    @Test
    fun actionProtocolAddsNoPlanningMetadataToOrdinaryActions() {
        val protocol = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/ActionControlProtocol.kt").readText()

        listOf(
            "task_step",
            "action_intent",
            "action_target",
            "previous_action_verdict",
            "previous_action_evidence"
        ).forEach { assertFalse(protocol.contains(it)) }
        assertFalse(protocol.contains("expected_effect"))
        assertTrue(protocol.contains("evidence_refs"))
    }

    @Test
    fun promptFamiliesKeepDefaultSoulAsASectionOfTheAgentCore() {
        val utility = File("src/main/assets/rules/utility_prompts.md").readText()
        val automation = File("src/main/assets/rules/automation_prompts.md").readText()
        val agentCore = File("src/main/assets/rules/agent_core.md").readText()
        val persona = File("src/main/assets/rules/persona.md")
        val workspace = File("src/main/java/com/droidance/agent/AgentWorkspaceStore.kt").readText()

        assertFalse(utility.contains("## task_creation_prompt"))
        assertFalse(utility.contains("## recorder_script_prompt"))
        assertFalse(utility.contains("## phase_a_zero_tool"))
        assertTrue(automation.contains("## app_takeover_run_prompt"))
        assertFalse(automation.contains("## loop_continuation_prompt"))
        assertTrue(agentCore.contains("## default_soul"))
        assertFalse(persona.exists())
        assertTrue(workspace.contains("readMarkdownSection(\"agent_core.md\", \"default_soul\")"))
        assertFalse(workspace.contains("persona.md"))
    }

    @Test
    fun retiredActivationGateHasNoProductionPromptOrProvider() {
        assertFalse(projectRoot.resolve(
            "core/agent/src/main/kotlin/com/droidance/core/agent/TurnActivationGate.kt").exists())
        assertFalse(projectFile("app/src/main/java/com/droidance/di/AppModule.kt").readText()
            .contains("provideTurnActivationGate"))
        val utility = File("src/main/assets/rules/utility_prompts.md").readText()
        assertFalse(utility.contains("phase_a_zero_tool"))
        assertFalse(utility.contains("phase_a_repair"))
    }

    @Test
    fun evidenceAndPublicFallbacksStayDeterministicAndBusinessAgnostic() {
        val extractor = projectFile("core/agent/src/main/kotlin/com/droidance/core/agent/EvidenceExtractorAgent.kt").readText()
        val rules = File("src/main/java/com/droidance/config/RuleConfigManager.kt").readText()
        val policy = File("src/main/assets/rules/harness_policy.yaml").readText()
        val prompts = File("src/main/assets/rules/harness_prompts.yaml").readText()

        assertTrue(extractor.contains("class EvidenceFactExtractor"))
        assertFalse(extractor.contains("EvidenceRelevancePolicy"))
        assertTrue(rules.contains("public_fallbacks.md"))
        assertFalse(policy.contains("evidence_detection:"))
        assertFalse(policy.contains("stop_words:"))
        assertFalse(prompts.contains("public_fallbacks:"))
        assertFalse(prompts.contains("claim_gate:"))
        assertFalse(prompts.contains("ordinary business messages"))
        listOf("金额", "订单", "验证码", "联系人", "短信").forEach {
            assertFalse(extractor.contains(it))
            assertFalse(rules.contains(it))
        }
    }

    @Test
    fun runtimeHasNoIndependentBusinessPlannerOrKeywordRouter() {
        val retiredTypes = setOf("UnifiedIntentAgent", "TaskPlanner", "TaskDecomposer", "TaskReflector", "RuntimeIntentRouter")
        val typeDeclaration = Regex("\\b(?:class|object|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val offenders = productionKotlinFiles().filter { file ->
            val source = file.readText()
            typeDeclaration.findAll(source).any { it.groupValues[1] in retiredTypes } ||
                source.contains("taskKeywords")
        }.map { it.relativeTo(projectRoot).path }.toList()
        assertTrue("Independent business planners or keyword routers: $offenders", offenders.isEmpty())
    }

    @Test
    fun nativeSettingsKnowledgeIsOnDemandAndHasNoSilentFallback() {
        val core = File("src/main/assets/rules/agent_core.md").readText()
        val retiredRegistrar = File("src/main/java/com/droidance/tools/DeviceControlToolRegistrar.kt")

        assertTrue(core.contains("documented Android capabilities"))
        assertTrue(core.contains("Detailed tool knowledge is available through workspace help"))
        assertFalse(retiredRegistrar.exists())
    }

    @Test
    fun ocrOutputDoesNotPretendToBeAnAccessibilityTreeOrTeachStaleTools() {
        val formatter = projectFile("app/src/main/java/com/droidance/tools/observe/ObserveScreenTool.kt").readText()

        assertFalse(formatter.contains("[TEXT_OCR_SCREEN]"))
        assertFalse(formatter.contains("Screen UI tree"))
        assertFalse(formatter.contains("Tap syntax:"))
        assertFalse(formatter.contains("Type syntax:"))
    }

    private val projectRoot: File
        get() = checkNotNull(projectFile("settings.gradle.kts").parentFile)

    private fun productionKotlinFiles(): Sequence<File> {
        val roots = listOf("app", "core/agent", "core/model", "core/knowledge", "core/device", "core/persistence")
            .map { File(projectRoot, "$it/src/main") }
        roots.forEach { check(it.isDirectory) { "Missing production source root: $it" } }
        return roots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
    }

    private fun projectFile(path: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $path")
}
