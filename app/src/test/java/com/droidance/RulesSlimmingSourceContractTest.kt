// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesSlimmingSourceContractTest {
    @Test
    fun ruleArtifactsAreSeparatedByConsumerAndDataType() {
        val rulesDir = projectFile("app/src/main/assets/rules")

        listOf(
            "agent_core.md",
            "memory_policy.md",
            "utility_prompts.md",
            "automation_prompts.md",
            "harness_policy.yaml",
            "harness_prompts.yaml",
            "tool_contracts.yaml"
        ).forEach { name ->
            assertTrue("$name must exist", File(rulesDir, name).isFile)
        }
    }

    @Test
    fun kotlinConsumesTypedYamlInsteadOfTheLegacyStringListParser() {
        val manager = projectFile(
            "app/src/main/java/com/droidance/config/RuleConfigManager.kt"
        ).readText()
        val configuration = projectFile(
            "core/agent/src/main/kotlin/com/droidance/core/agent/config/RuleConfiguration.kt"
        ).readText()

        assertTrue(manager.contains("HarnessPolicy.serializer()"))
        assertTrue(manager.contains("HarnessPromptPolicy.serializer()"))
        assertTrue(manager.contains("ToolContractsDocument.serializer()"))
        assertTrue(configuration.contains("@Serializable"))
        assertFalse(manager.contains("Map<String, List<String>>"))
        assertFalse(manager.contains("getList("))
        assertFalse(manager.contains("getSection("))
    }

    @Test
    fun markdownDocumentsAreCachedAndInvalidatedByFile() {
        val manager = projectFile(
            "app/src/main/java/com/droidance/config/RuleConfigManager.kt"
        ).readText()
        val readDocument = manager.substringAfter("fun readDocument(fileName: String)")
            .substringBefore("fun readMarkdownSection")
        val invalidate = manager.substringAfter("fun invalidate(filePath: String)")
            .substringBefore("fun readDocument")

        assertTrue(manager.contains("ConcurrentHashMap<String, String>()"))
        assertTrue(readDocument.contains("documentCache[fileName]"))
        assertTrue(invalidate.contains("documentCache.remove(fileName)"))
    }


    @Test
    fun bundledPromptAndSkillArtifactsRemainEnglish() {
        val artifacts = listOf(projectFile("app/src/main/assets/rules")).flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "md" && it.name != "public_fallbacks.md" }
                .toList()
        }
        val han = Regex("[\\u4E00-\\u9FFF]")

        artifacts.forEach { artifact ->
            assertFalse(
                "${artifact.relativeTo(projectRoot())} must remain English",
                han.containsMatchIn(artifact.readText())
            )
        }
    }

    private fun projectFile(path: String): File = File(projectRoot(), path)

    private fun projectRoot(): File {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        return if (root.name == "app") requireNotNull(root.parentFile) else root
    }
}
