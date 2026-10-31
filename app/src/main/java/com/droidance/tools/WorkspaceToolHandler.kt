// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.core.model.llm.models.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/** Owns workspace and automation tool registration and their scoped storage. */
@Singleton
class WorkspaceToolHandler @Inject constructor(
    private val automationManager: com.droidance.automation.AutomationManager,
    private val automationStore: com.droidance.automation.AutomationStore,
    val taskWorkspace: com.droidance.core.agent.workspace.TaskWorkspace,
    private val programEvents: com.droidance.core.agent.event.AgentEventStore,
    private val ruleConfigManager: com.droidance.config.RuleConfigManager,
    private val toolTurnServices: ToolTurnServices
) {
    fun registerAll(register: (Tool) -> Unit, toolHelp: (String) -> ToolDefinition?, sourceExists: (String) -> Boolean) {
        register(com.droidance.automation.AutomationTool(
            automationManager, automationStore,
            scope = { toolTurnServices.requireCurrent().sessionContext.let {
                com.droidance.core.agent.event.TurnEventScope(it.threadId, it.turnId)
            } },
            description = { ruleConfigManager.readMarkdownSection("automation_tools.md", "description") },
            requestTime = { owner ->
                programEvents.eventsForTurn(owner.turnId).lastOrNull {
                    it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_MESSAGE ||
                        it.eventKind == com.droidance.core.agent.event.AgentEventKind.USER_STEER
                }?.occurredAt ?: error("SCHEDULE_REQUEST_TIME_MISSING")
            }
        ))
        register(WorkspaceTool(
            workspace = taskWorkspace,
            scope = { toolTurnServices.requireCurrent().sessionContext.let {
                com.droidance.core.agent.event.TurnEventScope(it.threadId, it.turnId)
            } },
            rules = { section -> ruleConfigManager.readMarkdownSection("workspace.md", section) },
            readArtifact = { arguments -> toolTurnServices.requireCurrent().history.read(arguments) },
            toolHelp = { name -> toolHelp(name) },
            sourceExists = sourceExists,
            toolGuidance = { name ->
                if (name in setOf("update_memory", "session_search", "session_read", "experience"))
                    ruleConfigManager.readMarkdownSection("memory_policy.md", "automatic_conversation_memory")
                else ""
            }
        ))

        register(TaskMemoTool(taskWorkspace, scope = {
            toolTurnServices.requireCurrent().sessionContext.let {
                com.droidance.core.agent.event.TurnEventScope(it.threadId, it.turnId)
            }
        }, rules = { section -> ruleConfigManager.readMarkdownSection("workspace.md", section) }))

    }
}
