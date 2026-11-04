// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import javax.inject.Inject
import javax.inject.Singleton
import com.droidance.core.agent.device.ExecutionContextStore

/** Owns knowledge, memory and skill tools; execution facts still pass through ToolExecutor. */
@Singleton
class KnowledgeToolHandler @Inject constructor(
    val skillEngine: com.droidance.core.knowledge.skill.SkillEngine,
    private val sessionIndex: com.droidance.memory.SessionIndex,
    private val experiences: com.droidance.memory.ExperienceRepository,
    private val durableMemoryStore: com.droidance.core.knowledge.memory.DurableMemoryStore,
    private val toolTurnServices: ToolTurnServices,
    private val ruleConfigManager: com.droidance.config.RuleConfigManager,
    private val programEvents: com.droidance.core.agent.event.AgentEventStore,
    private val programBlobs: com.droidance.core.agent.blob.BlobStore,
    private val operationRecorder: com.droidance.agent.OperationRecorder,
    private val executionContextStore: ExecutionContextStore
) {
    fun installedVersions(packages: Set<String>) = experiences.installedVersions(packages)

    fun registerAll(register: (Tool) -> Unit, programTool: com.droidance.program.ProgramTool) {
        register(EffectCheckpointTool(programEvents, scope = {
            toolTurnServices.requireCurrent().sessionContext.let {
                com.droidance.core.agent.event.TurnEventScope(it.threadId, it.turnId)
            }
        }, description = ruleConfigManager.readMarkdownSection("completion_recovery.md", "effect_checkpoint")))
        KnowledgeToolRegistrar(
            readSession = { args -> toolTurnServices.requireCurrent().history.read(args) },
            registerTool = register,
            searchMemories = sessionIndex::formatSearch,
            expandEpisode = sessionIndex::formatCandidate,
            expandExperience = experiences::expand,
            expandMemory = durableMemoryStore::expand,
            recallSkill = skillEngine::recallSkill,
            searchSkills = skillEngine::searchSkills
        ).registerAll()
        register(ExperienceTool(experiences, programEvents, scope = {
            toolTurnServices.requireCurrent().sessionContext.let {
                com.droidance.core.agent.event.TurnEventScope(it.threadId, it.turnId)
            }
        }, invocationId = { requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ToolInvocation]).callId },
            maxChars = { ruleConfigManager.harnessPolicy().memoryContext.experienceMaxChars },
            installedVersions = experiences::installedVersions,
            description = ruleConfigManager.readMarkdownSection("memory_policy.md", "experience_tool")))
        SkillToolRegistrar(
            experienceSnapshot = experiences::snapshotForSkill,
            register = register, engine = skillEngine,
            rules = { ruleConfigManager.readMarkdownSection("skills.md", it) },
            availableCapabilities = {
                executionContextStore.current()?.let { ToolCatalog.operatorToolNamesFor(it.mode, it.space) }
                    ?: ToolCatalog.localKnowledgeToolNames
            },
            session = { toolTurnServices.requireCurrent().sessionContext },
            events = programEvents, blobs = programBlobs,
            runProgram = programTool::executeSkill,
            readLearning = operationRecorder::readLearningSource
        ).registerAll()

        MemoryToolRegistrar(register) { updates ->
            toolTurnServices.requireCurrent().memory.applyMemoryUpdates(updates)
        }.registerAll()

    }
}
