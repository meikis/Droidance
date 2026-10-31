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

class ToolRegistrarProtocolContractTest {
    @Test
    fun deepLinkFailuresAreOwnedByTheUnifiedOpenService() {
        val toolsDir = projectFile("app/src/main/java/com/droidance/tools")
        val source = toolsDir.resolve("AppLaunchService.kt").readText()

        assertFalse(toolsDir.resolve("DeepLinkToolRegistrar.kt").exists())
        assertTrue(source.contains("ToolProtocolResult.notExecuted("))
        assertTrue(source.contains("ToolProtocolResult.failed("))
        assertTrue(source.contains("DEEPLINK_ROUTE_NOT_FOUND"))
        assertTrue(source.contains("ARGUMENT_SCHEMA_MISMATCH"))
        assertFalse(source.contains("Read the current page with android_read"))
        assertFalse(source.contains("using android_act(op=\"open\") as fallback"))
        assertFalse(source.contains("- next:"))
    }

    @Test
    fun elementFailuresExposeFactsWithoutEmittingFollowUpCalls() {
        val source = projectFile("core/device/src/main/java/com/droidance/core/device/tools/ElementInteractionService.kt").readText()

        assertTrue(source.contains("ToolProtocolResult.notExecuted("))
        assertTrue(source.contains("ELEMENT_NOT_FOUND"))
        assertTrue(source.contains("ELEMENT_INDEX_OUT_OF_RANGE"))
        assertFalse(source.contains("use screenshot/vision"))
        assertFalse(source.contains("Call read_screen first"))
        assertFalse(source.contains("→ click {"))
    }

    @Test
    fun progressiveKnowledgeFailuresUseTheSharedProtocol() {
        val registrar = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/tools/KnowledgeToolRegistrar.kt").readText()
        val sessionIndex = projectFile("app/src/main/java/com/droidance/memory/SessionIndex.kt").readText()
        val skillEngine = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/skill/SkillEngine.kt").readText()

        assertTrue(registrar.contains("SESSION_SEARCH_FAILED"))
        assertTrue(registrar.contains("HISTORICAL_CANDIDATE_NOT_FOUND"))
        assertTrue(registrar.contains("SKILL_NOT_FOUND"))
        assertTrue(registrar.contains("SKILL_RECALL_FAILED"))
        assertFalse(registrar.contains("failed: \${it.message}"))
        assertFalse(sessionIndex.contains("Historical candidate not found:"))
        assertFalse(skillEngine.contains("Skill not found: \$reference"))
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
