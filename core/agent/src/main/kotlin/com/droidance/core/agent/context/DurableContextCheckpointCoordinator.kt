// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.AgentEventStore
import com.droidance.core.model.llm.models.ChatMessage
import com.droidance.core.model.llm.models.ToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the typed context contract and the live Operator loop.
 *
 * The coordinator is deliberately the only production entry point that can turn a projected
 * EventStore history into a checkpoint. It freezes the policy for this model session, validates
 * the parent chain, commits the checkpoint and advances the session epoch atomically.
 */
@Singleton
class DurableContextCheckpointCoordinator @Inject constructor(
    private val eventStore: AgentEventStore
) {
    /** Isolated source requests do not send the durable execution history or its summary.
     * Reuse the checkpoint policy and estimator without mutating the checkpoint chain. */
    fun requestCapacity(activeTurn: ContextCheckpointSession, systemPrompt: String,
        tools: List<ToolDefinition>, messages: List<ChatMessage>, providerContextWindowTokens: Int,
        compressionTriggerPercent: Int, recentScreenshotLimit: Int, maxOutputTokens: Int): ContextCapacity {
        val policy = policy(activeTurn, providerContextWindowTokens, compressionTriggerPercent,
            recentScreenshotLimit, maxOutputTokens)
        val tokens = StructuralContextTokenEstimator(policy.tokenEstimatorIdentity)
            .estimateRequest(systemPrompt, tools, messages)
        return ContextCapacity(tokens, policy.providerContextWindowTokens, policy.hardInputTokens, policy.triggerTokens)
    }

    suspend fun evaluateAndCommit(
        activeTurn: ContextCheckpointSession,
        taskAnchorPrompt: String,
        taskGoal: String,
        taskWorkingMemory: String,
        freshScreenState: String?,
        hasAttachedImage: Boolean,
        providerContextWindowTokens: Int,
        compressionTriggerPercent: Int,
        recentScreenshotLimit: Int,
        maxOutputTokens: Int,
        additionalRequestContext: String,
        compactionRecovery: ContextCompactionRecovery,
        onCapacity: (ContextCapacity) -> Unit,
        requiredImageCount: Int = 0,
        retainedRequestContext: List<ChatMessage> = emptyList(),
        beforeCompact: (ContextCapacity) -> Boolean = { true },
        compact: suspend (DurableContextProjection, ContextPolicySnapshot) -> String
    ): ContextCheckpointDecision? {
        val parent = eventStore.latestContextCheckpointForTurn(activeTurn.scope.turnId)
        val projection = DurableContextProjector.project(
            events = eventStore.eventsForTurn(activeTurn.scope.turnId),
            parentCheckpoint = parent
        ) ?: return null

        val dynamicSuffix = ContextDynamicRequestSuffix(
            listOf(
                ContextDynamicRequestPart(
                    "task_anchor_prompt",
                    taskAnchorPrompt.trim().ifBlank { "-" }
                ),
                ContextDynamicRequestPart("task_goal", taskGoal.trim().ifBlank { "-" }),
                ContextDynamicRequestPart(
                    "task_working_memory",
                    taskWorkingMemory.trim().ifBlank { "-" }
                ),
                ContextDynamicRequestPart(
                    "current_screen_text",
                    freshScreenState?.trim()?.takeIf(String::isNotBlank) ?: "-"
                ),
                ContextDynamicRequestPart("additional_request_context", additionalRequestContext.ifBlank { "-" }),
                ContextDynamicRequestPart(
                    "current_screen_image",
                    if (hasAttachedImage) "attached" else "none"
                )
            ) + List(requiredImageCount) { index ->
                ContextDynamicRequestPart("required_source_${index}_image", "attached")
            }
        )
        val policy = policy(activeTurn, providerContextWindowTokens, compressionTriggerPercent,
            recentScreenshotLimit, maxOutputTokens)
        val window = policy.providerContextWindowTokens
        val estimator = StructuralContextTokenEstimator(policy.tokenEstimatorIdentity)
        val planner = ContextCheckpointPlanner(estimator)
        val input = ContextCheckpointPlanInput(
                parentCheckpoint = parent,
                stablePrefix = activeTurn.stablePrefix,
                dynamicRequestSuffix = dynamicSuffix,
                sortedUnits = projection.units,
                currentObservationProjections = projection.observations,
                contextPolicy = policy,
                // These request snapshots disappear when the checkpoint resets the prefix.
                retainedRequestTokens = if (retainedRequestContext.isEmpty()) 0 else
                    estimator.estimateRequest("", emptyList(), retainedRequestContext)
            )
        var decision = planner.plan(input)
        val before = when (val current = decision) {
            is ContextCheckpointDecision.Unchanged -> current.tokensBefore
            is ContextCheckpointDecision.CompactionRequired -> current.tokensBefore
            is ContextCheckpointDecision.CheckpointReady -> current.payload.tokenEvidence.tokensBefore
            is ContextCheckpointDecision.ContextLimitExceeded -> current.evidence.tokensBefore
        }
        onCapacity(ContextCapacity(before, window, policy.hardInputTokens, policy.triggerTokens))
        if (decision is ContextCheckpointDecision.CompactionRequired) {
            if (!beforeCompact(ContextCapacity(before, window, policy.hardInputTokens, policy.triggerTokens))) {
                return ContextCheckpointDecision.Unchanged(ContextUnchangedReason.COMPACTION_DEFERRED, before, policy.triggerTokens)
            }
            val summary = try {
                compact(projection, policy).trim()
            } catch (error: ContextCompactionUnavailable) {
                if (!compactionRecovery.defer(before, policy.hardInputTokens)) throw error
                return ContextCheckpointDecision.Unchanged(
                    ContextUnchangedReason.COMPACTION_DEFERRED, before, policy.triggerTokens
                )
            }
            require(summary.isNotBlank()) { "Context compaction returned no summary; history retained" }
            decision = planner.plan(input.copy(compactionSummary = summary))
        }
        if (decision is ContextCheckpointDecision.CheckpointReady) {
            val commit = eventStore.recordContextCheckpoint(
                threadId = activeTurn.scope.threadId,
                turnId = activeTurn.scope.turnId,
                checkpointPayload = decision.payload,
                frozenSession = activeTurn.session
            )
            activeTurn.session = activeTurn.session.copy(
                key = commit.epochTransition.current
            )
            compactionRecovery.checkpointCommitted()
        }
        if (decision is ContextCheckpointDecision.CheckpointReady) {
            onCapacity(ContextCapacity(decision.payload.tokenEvidence.tokensAfter, window, policy.hardInputTokens, policy.triggerTokens))
        }
        return decision
    }

    private fun policy(activeTurn: ContextCheckpointSession, providerContextWindowTokens: Int,
        compressionTriggerPercent: Int, recentScreenshotLimit: Int, maxOutputTokens: Int): ContextPolicySnapshot {
        val window = providerContextWindowTokens.coerceAtLeast(1)
        return ContextPolicySnapshot(window, maxOutputTokens, (window / 16).coerceAtLeast(1),
            compressionTriggerPercent, 100, recentScreenshotLimit,
            StructuralContextTokenEstimator.forSession(activeTurn.session.key.identity.transportKind.name,
                "llm-router-v1", activeTurn.session.key.identity.modelId).identity)
    }
}

/** Estimated occupancy, with the physical model window distinguished from usable input. */
data class ContextCapacity(
    val estimatedInputTokens: Int,
    val modelWindowTokens: Int,
    val usableInputTokens: Int,
    val autoCompactTokens: Int
) {
    val usedPercent: Int get() = (estimatedInputTokens.toLong() * 100 / usableInputTokens).toInt()
    fun render(): String = "<context_capacity estimation=\"structural\" scope=\"current_task\">" +
        "estimated_input_tokens=$estimatedInputTokens model_window_tokens=$modelWindowTokens " +
        "usable_input_tokens=$usableInputTokens used_percent=$usedPercent " +
        "auto_compact_tokens=$autoCompactTokens" + "</context_capacity>"
}
