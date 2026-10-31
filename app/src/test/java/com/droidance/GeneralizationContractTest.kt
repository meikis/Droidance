// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralizationContractTest {
    @Test
    fun mutable_product_rules_live_outside_kotlin_core_files() {
        val ruleManager = projectFile("app/src/main/java/com/droidance/config/RuleConfigManager.kt").readText()
        val onboarding = projectFile("app/src/main/java/com/droidance/ui/viewmodel/OnboardingViewModel.kt").readText()
        val manifest = projectFile("app/src/main/AndroidManifest.xml")

        assertFalse(ruleManager.contains("拼多多"))
        assertFalse(ruleManager.contains("问小团"))
        assertFalse(File(manifest.parentFile, "java/com/droidance/automation/DroidanceAccessibilityService.kt").exists())
        assertFalse(projectFileOrNull("core/device/src/main/java/com/droidance/tools/observe/ObserveScreenAnalyzer.kt")?.exists() == true)
        assertFalse(File(projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").parentFile, "TaskControllerAgent.kt").exists())
        assertFalse(onboarding.contains("\"京东\" to"))
    }

    @Test
    fun business_intent_rules_are_not_reintroduced_in_the_operator_harness() {
        val harness = projectFile("app/src/main/assets/rules/harness_policy.yaml").readText()
        val operator = com.droidance.agent.coordinationSources()

        assertTrue(harness.contains("schema_version:"))
        assertFalse(harness.contains("system_mutation:"))
        assertFalse(harness.contains("action_semantics:"))
        assertFalse(harness.contains("information_collection:"))
        assertFalse(harness.contains("generic_target_candidates:"))
        assertFalse(harness.contains("dangerous_confirm:"))
        assertFalse(operator.contains("tool_policy.md"))
        assertFalse(operator.contains("search_workflow_guidance"))
    }

    private fun projectFileOrNull(path: String): File? {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        return null
    }

    private fun projectFile(path: String): File =
        projectFileOrNull(path) ?: error("Cannot locate $path from ${System.getProperty("user.dir")}")
}
