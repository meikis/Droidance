// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.context.ContextCheckpointDecision
import com.droidance.core.agent.context.DurableContextMessageProjector
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.Role
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.coroutines.withContext

internal data class ContextRoundInput(
    val workingMemory: String,
    val screenState: String?,
    val hasAttachedImage: Boolean,
    val requiredImageCount: Int,
    val additionalContext: String,
    val systemPrompt: String,
    val tools: List<ToolDefinition>
)

/** Computes and commits the durable context checkpoint before model dispatch. */
internal suspend fun TaskTurnSession.checkpointContext(
    input: ContextRoundInput,
    onCapacity: (com.droidance.core.agent.context.ContextCapacity) -> Unit
): ContextCheckpointDecision? =
    durableContextCheckpointCoordinator.evaluateAndCommit(
            activeTurn = durableTurn,
            taskAnchorPrompt = stepPrompt,
            taskGoal = taskGoal,
            taskWorkingMemory = input.workingMemory,
            freshScreenState = input.screenState,
            hasAttachedImage = input.hasAttachedImage,
            providerContextWindowTokens = llmRouter.currentContextWindowTokens(),
            compressionTriggerPercent = thresholds.contextBudgetPercent,
            // Explicit source images belong to this request's dynamic budget;
            // they must not change the checkpoint chain's frozen retention policy.
            recentScreenshotLimit = thresholds.contextRecentImageLimit,
            maxOutputTokens = modelOutputBudget,
            requiredImageCount = input.requiredImageCount,
            retainedRequestContext = requestHistory.retainedRequestContext(),
            additionalRequestContext = input.additionalContext,
            compactionRecovery = compactionRecovery,
            onCapacity = onCapacity,
            beforeCompact = { capacity ->
                if (!memoryCheckpointOffered && capacity.estimatedInputTokens.toLong() +
                    compactionPolicy.deferralHeadroomTokens < capacity.usableInputTokens) {
                    memoryCheckpointOffered = true
                    messages.add(ChatMessage(role = Role.SYSTEM, content =
                        ruleConfigManager.readMarkdownSection("utility_prompts.md", "memory_checkpoint")))
                    false
                } else true
            },
            compact = { projection, policy ->
                val budgetMs = RoundTimeoutBudgetPolicy.bound(
                    requestedTimeoutMs = compactionPolicy.totalTimeoutMs,
                    taskWallMs = maxDeviceTaskWallMs,
                    activeElapsedMs = activeElapsedMs(),
                    finalReply = false
                )
                if (maxDeviceTaskWallMs > 0L && budgetMs <= 0L) {
                    throw com.droidance.core.agent.context.ContextCompactionUnavailable("COMPACTION_TASK_BUDGET_EXHAUSTED")
                }
                val summaryHistory = DurableContextMessageProjector.build(
                    projection.copy(memoryContext = null), stepPrompt)
                    .filter { it.role != Role.SYSTEM && !it.historicalImage }
                // Keep the parent's provider cache session while isolating transport continuation.
                withContext(com.droidance.core.model.llm.ModelDiagnosticScope(durableTurn.scope.threadId,
                    durableTurn.scope.turnId, "compaction:${java.util.UUID.randomUUID()}")) {
                    taskContextCompactor.compact(projection, policy, stepPrompt, compactionPolicy.copy(
                        requestTimeoutMs = RoundTimeoutBudgetPolicy.bound(compactionPolicy.requestTimeoutMs, budgetMs, 0, false),
                        totalTimeoutMs = budgetMs
                    ), input.systemPrompt, input.tools, requestHistory.fork(summaryHistory))
                }
            }
        )
