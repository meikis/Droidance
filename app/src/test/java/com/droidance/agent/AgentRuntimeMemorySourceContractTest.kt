// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.config.markdownSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentRuntimeMemorySourceContractTest {
    @Test
    fun mainTurnsPersistDeterministicMemoryWithoutAnotherModelRound() {
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())

        assertFalse(runtime.contains("memoryManager.appendTodayLog("))
        assertFalse(runtime.contains("toolExecutor.completionMemoryUpdates()"))
        assertTrue(runtime.contains("suspend fun applyMemoryUpdates("))
        assertTrue(runtime.contains("durableMemoryStore.applyUpdates("))
        assertFalse(runtime.contains("MemoryCuratorAgent"))
        assertFalse(runtime.contains("triggerBackgroundReflect("))
        assertFalse(runtime.contains("AgentReflectionEngine"))
    }

    @Test
    fun taskRunStateIsNotDuplicatedIntoMarkdownContext() {
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val memoryDir = projectFile("app/src/main/java/com/droidance/memory")

        assertFalse(memoryDir.resolve("MemoryManager.kt").exists())
        assertFalse(runtime.contains("memoryManager.appendTaskContext("))
        assertFalse(runtime.contains("memoryManager.clearTaskContext("))
    }

    @Test
    fun completedTurnProjectsTypedMemoryCandidateBeforeRoomConsolidation() {
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val index = projectFile("app/src/main/java/com/droidance/memory/SessionIndex.kt").readText()

        assertTrue(runtime.contains("TurnMemoryCandidateProjector.project("))
        assertTrue(runtime.contains("eventStore.eventsForThread(activeTurn.scope.threadId)"))
        assertTrue(runtime.contains("sessionIndex.indexCandidate(candidate)"))
        assertFalse(index.contains("extractSteps("))
        assertFalse(index.contains("extractResult("))
        assertTrue(index.contains("dao.consolidate("))
    }

    @Test
    fun publicLogsNeverPersistReportProtocolOrParallelTaskTrace() {
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val terminalBlock = runtime.substringAfter("val uiResult = runUiTask(")
            .substringBefore("var effectiveTerminal")
        val finalBlock = runtime.substringAfter("val terminalProjection = RuntimeTerminalProjection.from(effectiveTerminal)")
            .substringBefore("finishedTaskStatus = status")

        assertFalse(terminalBlock.contains("publicOperatorSummary"))
        assertFalse(finalBlock.contains("publicFinalResponse"))
        assertFalse(finalBlock.contains("appendTodayLog"))
        assertFalse(finalBlock.contains("summary = finalResponse.take(800)"))
        assertFalse(finalBlock.contains("**Assistant**: \$finalResponse"))
        assertFalse(runtime.contains("persistTrajectoryMemory("))
        assertFalse(runtime.contains("TaskExperienceRecorder"))
        assertFalse(runtime.contains("TaskRunTrace"))
    }

    @Test
    fun memoryPolicyLoadsRevisionAndForgottenReferenceRulesInTheAutomaticMemorySection() {
        val policy = markdownSection(
            projectFile("app/src/main/assets/rules/memory_policy.md").readText(),
            "automatic_conversation_memory"
        )

        assertTrue(policy.contains("expected_revision=\"absent\""))
        assertTrue(policy.contains("forgotten_refs"))
        assertTrue(policy.contains("Do not present older matching historical facts"))
    }

    @Test
    fun turnSkillMetadataUsesTheFrozenToolSetAndConfiguredBudgetWithoutLoadingBodies() {
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val turnContext = runtime.substringAfter("private suspend fun operatorTurnContext(")
            .substringBefore("private fun ensureLocalUiAutomationResidency()")
        val stableContext = runtime.substringAfter("private fun operatorStableSystemContext()")
            .substringBefore("private fun skillAuthoringContext()")

        assertTrue(runtime.contains("availableCapabilities = taskSession.durableTurn.stablePrefix.tools.map { it.name }.toSet()"))
        assertTrue(runtime.contains("query = request.rawText"))
        assertTrue(turnContext.contains("skillEngine.catalogContext(availableCapabilities, memoryPolicy.skillCatalogMaxChars, query)"))
        assertFalse(turnContext.contains("loadAllSkills("))
        assertFalse(turnContext.contains("loadSkill("))
        assertFalse(turnContext.contains("MemoryContextPromptRenderer"))
        assertFalse(turnContext.contains("memoryContext:"))
        assertFalse(stableContext.contains("catalogContext("))
    }

    @Test
    fun runtimeCommitsMemoryBeforeCoordinationWithoutCombiningItWithTurnDirectives() {
        val runtime = (projectFile("app/src/main/java/com/droidance/agent/AgentRuntime.kt").readText() + "\n" + projectFile("app/src/main/java/com/droidance/agent/RuntimeKnowledgeHandler.kt").readText())
        val memoryCommit = runtime.indexOf("eventStore.recordMemoryContextLoaded(")
        val coordination = runtime.indexOf("val uiResult = runUiTask(")

        assertTrue(memoryCommit >= 0 && coordination > memoryCommit)
        assertFalse(runtime.contains("MemoryContextPromptRenderer.render("))
        assertFalse(runtime.contains("memoryContext = recordedMemoryContext"))
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
