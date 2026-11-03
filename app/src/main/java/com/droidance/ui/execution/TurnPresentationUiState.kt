// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import com.droidance.core.agent.PublicAssistantText
import com.droidance.core.agent.TaskExecutionClock
import com.droidance.core.agent.event.PublicProgressReadModel
import com.droidance.core.agent.event.ConversationEntryReadModel
import com.droidance.core.agent.event.AgentThreadReadModel
import com.droidance.core.agent.event.TurnActivationKind
import com.droidance.core.agent.harness.ZERO_TOOL_PHASE_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_PHASE_VALUE
import com.droidance.core.agent.harness.ZERO_TOOL_DECISION_KEY
import com.droidance.core.agent.harness.ZeroToolRoundDecision
import com.droidance.core.agent.event.ResultCardReadModel
import com.droidance.core.agent.event.ToolDetailReadModel
import com.droidance.core.agent.event.ModelRoundDetailReadModel
import com.droidance.core.agent.event.TurnActivityReadModel
import com.droidance.core.agent.event.TurnReadModelState
import com.droidance.core.agent.event.LifecycleActivityKind
import com.droidance.core.agent.event.LifecycleActivityReadModel
import com.droidance.core.agent.event.ToolActivityReadModel
import com.droidance.core.agent.event.ToolActivityState
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.reportedToolResults

/**
 * EventStore-backed projection with the active Turn's live monotonic clock.
 *
 * This is presentation state only: it does not infer task semantics or translate a model result.
 */
data class TurnPresentationUiState(
    val turnId: String,
    val startedAtMs: Long,
    val opening: String,
    val intervention: String,
    val finalSummary: String,
    val activity: TurnActivityReadModel,
    val toolDetails: Map<String, ToolDetailReadModel>,
    val resultCard: ResultCardReadModel?,
    val hasDeviceExecution: Boolean,
    val androidActivated: Boolean = false,
    val roundDetails: Map<String, ModelRoundDetailReadModel> = emptyMap(),
    val terminalStatus: String? = null,
    val executionClock: TaskExecutionClock.Snapshot? = null,
    val messageId: String = "assistant:$turnId",
    val isCurrentSegment: Boolean = true,
    val applyingGuidance: Boolean = false,
    val isSegmented: Boolean = false,
    /** Replies persisted by earlier builds as a separate stage kind; shown like reported results. */
    val stageResults: List<ConversationEntryReadModel> = emptyList()
) {
    val isTerminal: Boolean
        get() = isCurrentSegment && activity.state == TurnReadModelState.TERMINAL

    fun isPaused(livePaused: Boolean = false): Boolean = isCurrentSegment && !isTerminal && (
        livePaused || activity.state == TurnReadModelState.WAITING_FOR_USER ||
            activity.state == TurnReadModelState.PAUSED_USER ||
            activity.state == TurnReadModelState.PAUSED_RECOVERY_REQUIRED
        )

    fun showCurrentProgress(active: Boolean, livePaused: Boolean = false): Boolean =
        active && isCurrentSegment && !applyingGuidance && !isTerminal && !isPaused(livePaused) && finalSummary.isBlank() && resultCard == null

    private fun publicUpdates(): List<PublicProgressReadModel> = activity.publicProgressHistory
        .map { it.copy(text = PublicAssistantText.sanitize(it.text).trim()) }
        .filter { it.text.isNotBlank() }

    /**
     * What the model handed over with report_result, plus stage replies persisted by earlier builds. Ordinary
     * model text is never kept here: it is a live status and then part of the execution log.
     */
    val reportedResults: List<PublicProgressReadModel>
        get() {
            val reported = reportedToolResults(turnId, activity, toolDetails)
            val legacy = stageResults.map { PublicProgressReadModel(it.itemId, it.sequence, it.text, listOfNotNull(it.roundId)) }
            return (reported + legacy).map { it.copy(text = PublicAssistantText.sanitize(it.text).trim()) }
                .filter { it.text.isNotBlank() }.sortedBy { it.sequence }
        }

    private fun currentProgress(active: Boolean, livePaused: Boolean): PublicProgressReadModel? =
        if (showCurrentProgress(active, livePaused)) publicUpdates().lastOrNull() else null

    /** The live line: the model's latest status text, or the running step's action_title before any text. */
    fun liveStatus(active: Boolean, livePaused: Boolean = false): String =
        currentProgress(active, livePaused)?.text ?: liveActionTitle(active, livePaused)

    /** The step running now, from the model's action_title; replaced by the next step and never kept as a reply. */
    fun liveActionTitle(active: Boolean, livePaused: Boolean = false): String =
        if (!showCurrentProgress(active, livePaused)) "" else activity.entries.filterIsInstance<ToolActivityReadModel>()
            .filter { it.turnId == turnId && it.visible }.maxByOrNull { it.sequence }
            ?.presentation?.actionTitle?.let(PublicAssistantText::sanitize)?.trim().orEmpty()

    /** Earlier status text, kept in the collapsed execution log next to the steps it accompanied. */
    fun progressHistory(active: Boolean, livePaused: Boolean = false): List<PublicProgressReadModel> =
        publicUpdates().filterNot { it.eventId == currentProgress(active, livePaused)?.eventId }

    fun handoffReason(liveReason: String = ""): String = when {
        !isCurrentSegment || isTerminal -> ""
        activity.state == TurnReadModelState.WAITING_FOR_USER -> intervention.ifBlank { liveReason }
        else -> liveReason
    }

    /** Use the current accepted handoff's model metadata, including after reattachment. */
    fun handoffTitle(): String? {
        if (!isCurrentSegment || isTerminal || activity.state != TurnReadModelState.WAITING_FOR_USER) return null
        val acceptedCalls = activity.entries.filterIsInstance<ToolActivityReadModel>()
            .filter { it.turnId == turnId && it.state == ToolActivityState.VERIFIED }
        val request = activity.entries.filterIsInstance<LifecycleActivityReadModel>()
            .filter { it.turnId == turnId && it.kind == LifecycleActivityKind.USER_INTERVENTION &&
                acceptedCalls.any { call -> call.callId == it.callId } }
            .maxByOrNull { it.sequence } ?: return null
        return acceptedCalls.firstOrNull { it.callId == request.callId }
            ?.presentation?.actionTitle?.trim()?.takeIf(String::isNotEmpty)
    }

    /** Accumulated transport text is only a fallback before a typed public reply exists. */
    fun liveDialogue(liveReply: String): String = liveReply.trim().takeIf {
        !hasExecutionPresentation && opening.isBlank() && finalSummary.isBlank() && intervention.isBlank() && stageResults.isEmpty()
    }.orEmpty()

    val hasDurablePresentation: Boolean
        get() = if (!isCurrentSegment) hasPublicResponse() ||
            EventStoreExecutionTimelinePresenter.hasHistory(activity, publicUpdates())
        else !isTerminal || showExecutionLog() ||
            opening.isNotBlank() ||
            finalSummary.isNotBlank() ||
            intervention.isNotBlank() ||
            reportedResults.isNotEmpty() ||
            resultCard != null

    val hasExecutionPresentation: Boolean
        get() = androidActivated || hasDeviceExecution
    fun hasPublicResponse(liveReply: String = ""): Boolean =
        opening.isNotBlank() || finalSummary.isNotBlank() || intervention.isNotBlank() ||
            reportedResults.isNotEmpty() || resultCard != null || liveDialogue(liveReply).isNotBlank()

    fun publicCopyText(livePaused: Boolean = false, liveIntervention: String = ""): String = buildList {
        add(opening)
        addAll(reportedResults.map { it.text })
        if (isPaused(livePaused)) add(handoffReason(liveIntervention))
        add(finalSummary)
        resultCard?.let { card -> add(card.title); addAll(card.items.map { it.text }) }
    }.map { PublicAssistantText.sanitize(it).trim() }.filter(String::isNotBlank).joinToString("\n\n")

    fun showExecutionLog(): Boolean =
        hasExecutionPresentation || activity.entries.any {
            it is ToolActivityReadModel && it.turnId == turnId && it.visible &&
                it.presentation.category != ToolPresentationCategory.COMPLETION
        }
}

object RetainedVirtualExecutionOwnerPolicy {
    fun owns(retainedThreadId: String?, conversationThreadId: String): Boolean =
        !retainedThreadId.isNullOrBlank() && retainedThreadId == conversationThreadId
}

/** The committed Phase A opening precedes device/session preparation. */
internal fun AgentThreadReadModel.hasAndroidExecutionIntent(turnId: String): Boolean =
    turns.any { it.turnId == turnId && it.activationKind == TurnActivationKind.ANDROID } ||
        roundDetails.values.any {
            it.turnId == turnId &&
                it.finishMetadata[ZERO_TOOL_PHASE_KEY] == ZERO_TOOL_PHASE_VALUE &&
                it.finishMetadata[ZERO_TOOL_DECISION_KEY] == ZeroToolRoundDecision.ACTIVATE_ANDROID.wireName
        }

internal fun AgentThreadReadModel.hasExecutionIntent(turnId: String): Boolean =
    hasAndroidExecutionIntent(turnId) ||
        turns.any { it.turnId == turnId && it.activationKind == TurnActivationKind.LOCAL } ||
        roundDetails.values.any {
            it.turnId == turnId &&
                it.finishMetadata[ZERO_TOOL_PHASE_KEY] == ZERO_TOOL_PHASE_VALUE &&
                it.finishMetadata[ZERO_TOOL_DECISION_KEY] == ZeroToolRoundDecision.ACTIVATE_LOCAL.wireName
        }
