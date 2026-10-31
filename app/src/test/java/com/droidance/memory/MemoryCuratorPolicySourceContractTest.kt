// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.memory

import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import com.droidance.core.persistence.memory.*

import com.droidance.memory.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MemoryCuratorPolicySourceContractTest {
    @Test
    fun no_extra_memory_curator_or_reflection_agent_remains() {
        val agentDir = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").parentFile
        val runtime = File(agentDir, "AgentRuntime.kt").readText() + "\n" + File(agentDir, "RuntimeKnowledgeHandler.kt").readText()
        val knowledge = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/tools/KnowledgeToolRegistrar.kt").readText()

        assertFalse(File(agentDir, "MemoryCuratorAgent.kt").exists())
        assertFalse(File(agentDir, "AgentReflectionEngine.kt").exists())
        assertFalse(runtime.contains("MemoryCuratorAgent"))
        assertFalse(runtime.contains("AgentReflectionEngine"))
        assertTrue(runtime.contains("durableMemoryStore.applyUpdates("))
        assertFalse(knowledge.contains("memoryManager.updateUserProfile"))
    }

    @Test
    fun userFactsStayInGlobalUserProfileNotSoul() {
        val source = projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText()

        assertTrue(source.contains("progressiveMemoryContextProjector.project("))
        assertFalse(source.contains("writeSoul(userInput"))
    }

    @Test
    fun profileWritersNeverTruncateCanonicalMemoryAtAnArbitraryCharacter() {
        val knowledge = projectFile("core/knowledge/src/main/kotlin/com/droidance/core/knowledge/tools/KnowledgeToolRegistrar.kt").readText()

        assertFalse(knowledge.contains("take(8000)"))
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
