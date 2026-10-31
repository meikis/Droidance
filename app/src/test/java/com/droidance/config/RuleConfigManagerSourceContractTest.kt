// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.config

import com.droidance.core.agent.config.*
import com.droidance.core.device.config.*

import com.droidance.config.*

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleConfigManagerSourceContractTest {
    private val source = File("src/main/java/com/droidance/config/RuleConfigManager.kt").readText()

    @Test
    fun markdownIsOnlyUsedForPromptDocumentsAndNamedSections() {
        assertTrue(source.contains("fun readDocument(fileName: String)"))
        assertTrue(source.contains("fun readMarkdownSection(fileName: String, heading: String)"))
        assertFalse(source.contains("fun getList("))
        assertFalse(source.contains("fun getSection("))
    }

    @Test
    fun harnessConfigurationUsesTrueYamlAndStrictTypedDocuments() {
        val types = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/config/RuleConfiguration.kt"
        ).readText()

        assertTrue(source.contains("org.snakeyaml.engine.v2.api.Load"))
        assertTrue(source.contains(".setAllowDuplicateKeys(false)"))
        assertTrue(source.contains("ignoreUnknownKeys = false"))
        assertTrue(source.contains("coerceInputValues = false"))
        assertTrue(source.contains("HarnessPolicy.serializer()"))
        assertTrue(source.contains("ToolContractsDocument.serializer()"))
        assertTrue(types.contains("val schemaVersion: Int"))
        assertTrue(types.contains("fun validate()"))
    }

    @Test
    fun overridesReplaceCompleteDocumentsWithoutLegacyMergeOrFallback() {
        assertTrue(source.contains("private const val OVERRIDE_DIR = \"rules/overrides\""))
        assertTrue(source.contains("fileManager.readFile(\"\$OVERRIDE_DIR/\$fileName\")"))
        assertFalse(source.contains("overrideBuiltInMarkdownSections"))
        assertFalse(source.contains("replaceMarkdownSection"))
        assertFalse(source.contains("getOrDefault"))
        assertFalse(source.contains("toIntOrNull"))
    }

    @Test
    fun startupValidatesConfigurationBeforeRuntimeServicesStart() {
        val application = File("src/main/java/com/droidance/DroidanceApp.kt").readText()
        val afterValidation = application.substringAfter("ruleConfigManager.initialize()")

        assertTrue(source.contains("harnessPolicy()"))
        assertTrue(source.contains("toolContracts()"))
        assertTrue(afterValidation.contains("heartbeatManager.startHeartbeat()"))
        assertTrue(afterValidation.contains("automationCoordinator.start()"))
    }

    @Test
    fun automationDraftPromptLivesInTheSharedAutomationPromptDocument() {
        val creation = File(
            "src/main/java/com/droidance/ui/viewmodel/AutomationDraftInterpreter.kt"
        ).readText()
        val prompts = File("src/main/assets/rules/automation_prompts.md").readText()

        assertTrue(creation.contains("automation_prompts.md"))
        assertTrue(creation.contains("automation_draft_generation"))
        assertTrue(prompts.contains("## automation_draft_generation"))
        assertFalse(File("src/main/assets/rules/automation_draft_prompt.md").exists())
        assertFalse(File("src/main/java/com/droidance/agent/timeline/TaskDecomposer.kt").exists())
    }

    private fun projectFile(path: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path")
    }
}
