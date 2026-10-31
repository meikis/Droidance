// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.LiveTaskTiming
import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*

/** A presentation boundary, not a new Turn or a copy of its event history. */
data class ConversationExecutionSegment(
    val input: ConversationEntryReadModel,
    val nextInput: ConversationEntryReadModel?,
    val roundIds: Set<String>,
    val replies: List<ConversationEntryReadModel>
) {
    val turnId: String get() = input.turnId
    val messageId: String get() = if (input.kind == ConversationEntryKind.USER_MESSAGE) "assistant:$turnId"
        else "assistant:$turnId:${input.itemId}"
    val isCurrent: Boolean get() = nextInput == null
}

/** Assign every round once at its start; late replies and receipts keep that ownership. */
fun conversationExecutionSegments(
    entries: List<ConversationEntryReadModel>,
    events: List<AgentEventEnvelope>
): List<ConversationExecutionSegment> {
    val starts = events.filter { it.eventKind == AgentEventKind.MODEL_ROUND_STARTED }
    return entries.filter { it.kind == ConversationEntryKind.USER_MESSAGE || it.kind == ConversationEntryKind.USER_STEER }
        .groupBy { it.turnId }.flatMap { (turnId, unsorted) ->
            val inputs = unsorted.sortedBy { it.sequence }
            val owners = starts.filter { it.turnId == turnId }.associate { event ->
                event.roundId to inputs.lastOrNull { it.sequence < event.sequence }?.itemId
            }
            val replies = entries.filter { it.turnId == turnId && it.kind != ConversationEntryKind.USER_MESSAGE &&
                it.kind != ConversationEntryKind.USER_STEER }
            inputs.mapIndexed { index, input ->
                ConversationExecutionSegment(input, inputs.getOrNull(index + 1),
                    owners.filterValues { it == input.itemId }.keys.filterNotNull().toSet(),
                    replies.filter { reply ->
                        val owner = reply.roundId?.let(owners::get)
                            ?: inputs.lastOrNull { it.sequence < reply.sequence }?.itemId
                        owner == input.itemId
                    })
            }
        }.sortedBy { it.input.sequence }
}

fun AgentThreadReadModel.segmentPresentations(timing: LiveTaskTiming?): List<TurnPresentationUiState> {
    val segments = conversationExecutionSegments(conversation.entries, completeRecord.events)
    val turnsById = turns.associateBy { it.turnId }
    val segmentCounts = segments.groupingBy { it.turnId }.eachCount()
    return segments.mapNotNull { segment ->
        val turn = turnsById[segment.turnId] ?: return@mapNotNull null
        val whole = activityByTurn[turn.turnId] ?: return@mapNotNull null
        val segmented = segmentCounts.getValue(turn.turnId) > 1
        val entries = whole.entries.filter { entry ->
            if (entry is ToolActivityReadModel) entry.roundId in segment.roundIds
            else entry.sequence >= segment.input.sequence &&
                (segment.nextInput == null || entry.sequence < segment.nextInput.sequence)
        }
        val progress = whole.publicProgressHistory.mapNotNull { update ->
            val owned = update.roundIds.filter { it in segment.roundIds }
            if (owned.isEmpty()) null else update.copy(eventId = "${update.eventId}:${segment.input.itemId}", roundIds = owned)
        }
        val details = toolDetails.filterValues { it.turnId == turn.turnId && it.roundId in segment.roundIds }
        val rounds = roundDetails.filterValues { it.turnId == turn.turnId && it.roundId in segment.roundIds }
        val result = resultCards.lastOrNull { card -> card.turnId == turn.turnId &&
            toolDetails[card.callId]?.roundId in segment.roundIds }
        val baseline = if (segment.input.kind == ConversationEntryKind.USER_MESSAGE) 0L else segment.input.activeDurationMillis
        val endpoint = segment.nextInput?.activeDurationMillis ?: whole.activeDurationMillis.takeIf { segment.isCurrent }
        val activeDuration = if (!segmented) whole.activeDurationMillis else
            if (baseline != null && endpoint != null) (endpoint - baseline).coerceAtLeast(0L) else null
        val liveClock = timing?.takeIf { segment.isCurrent && it.turnId == turn.turnId }?.clock?.let { clock ->
            if (!segmented) clock else baseline?.let { com.droidance.core.agent.TaskExecutionClock.monotonicTimeMs().let { now ->
                com.droidance.core.agent.TaskExecutionClock.Snapshot((clock.elapsedMs(now) - it).coerceAtLeast(0L),
                    now.takeIf { clock.runningSinceMs != null })
            } }
        }
        val activity = whole.copy(
            entries = entries,
            operationCount = entries.filterIsInstance<ToolActivityReadModel>().count { it.operationCounted },
            publicProgressHistory = progress,
            currentLabelResourceKey = if (segment.isCurrent) whole.currentLabelResourceKey else null,
            startedAt = if (!segmented) whole.startedAt else segment.input.occurredAt,
            completedAt = segment.nextInput?.occurredAt ?: whole.completedAt,
            activeDurationMillis = activeDuration,
            executionRoundTimings = whole.executionRoundTimings.filterKeys { it in segment.roundIds }
        )
        // No empty historical card between consecutive guidance messages.
        if (!segment.isCurrent && segment.replies.isEmpty() && segment.roundIds.isEmpty() && entries.isEmpty()) return@mapNotNull null
        val replies = segment.replies
        TurnPresentationUiState(
            turnId = turn.turnId, messageId = segment.messageId, isCurrentSegment = segment.isCurrent,
            isSegmented = segmented,
            applyingGuidance = segment.input.kind == ConversationEntryKind.USER_STEER && segment.isCurrent &&
                segment.roundIds.isEmpty() && turn.state != TurnReadModelState.TERMINAL,
            executionClock = liveClock,
            startedAtMs = activity.startedAt,
            opening = replies.filter { it.kind == ConversationEntryKind.ASSISTANT_START }.joinToString("\n\n") { it.text },
            stageResults = replies.filter { it.kind == ConversationEntryKind.ASSISTANT_STAGE_RESULT }
                .sortedBy { it.sequence }.map { it.copy(text = com.droidance.core.agent.PublicAssistantText.sanitize(it.text).trim()) }
                .filter { it.text.isNotBlank() },
            intervention = replies.lastOrNull { it.kind == ConversationEntryKind.ASSISTANT_USER_INTERVENTION }?.text.orEmpty(),
            finalSummary = (replies.lastOrNull { it.kind == ConversationEntryKind.TURN_ASSISTANT_REPLY }
                ?: replies.lastOrNull { it.kind == ConversationEntryKind.TURN_SYSTEM_NOTICE })?.text.orEmpty(),
            activity = activity, toolDetails = details, roundDetails = rounds, resultCard = result,
            hasDeviceExecution = details.values.any { it.startedAt != null &&
                it.executionStatus != ToolExecutionStatus.NOT_EXECUTED &&
                it.technicalName !in setOf("done", "present_result_card") },
            androidActivated = hasExecutionIntent(turn.turnId),
            terminalStatus = turn.terminalStatus.takeIf { segment.isCurrent }
        )
    }
}
