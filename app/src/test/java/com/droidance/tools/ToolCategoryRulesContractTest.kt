// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolCategoryRulesContractTest {
    @Test
    fun operatorHarnessDoesNotPreloadToolsFromIntent() {
        val source = com.droidance.agent.executorSources()

        assertFalse(source.contains("ToolCategoryRules"))
        assertFalse(source.contains("activatedTools"))
        assertFalse(source.contains("loadToolCategory"))
        assertFalse(source.contains("preloadToolsByIntent"))
    }

    @Test
    fun toolPolicyDoesNotCarryIntentKeywordCategories() {
        val rulesFile = projectFile("app/src/main/assets/rules/tool_contracts.yaml")
        val rules = rulesFile.readText()

        assertFalse(File(rulesFile.parentFile, "tool_policy.md").exists())
        assertFalse(rules.contains("categories:"))
        assertFalse(rules.contains("captcha:"))
    }

    private fun projectFile(path: String): File {
        val userDir = System.getProperty("user.dir") ?: "."
        var current = File(userDir).absoluteFile
        while (true) {
            val candidate = File(current, path)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: break
        }
        error("Cannot locate $path from $userDir")
    }
}
