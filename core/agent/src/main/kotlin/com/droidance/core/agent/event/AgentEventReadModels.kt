// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import kotlinx.serialization.Serializable

data class AgentThreadReadModel(
    val metadata: ThreadMetadataReadModel,
    val turns: List<TurnReadModel>,
    val conversation: ConversationReadModel,
    val activityByTurn: Map<String, TurnActivityReadModel>,
    val toolDetails: Map<String, ToolDetailReadModel>,
    val resultCards: List<ResultCardReadModel>,
    val roundDetails: Map<String, ModelRoundDetailReadModel>,
    val completeRecord: CompleteRecordReadModel
)

data class AgentEventProjectionFailureSnapshot(
    val code: String,
    val eventId: String?,
    val detail: String
)

sealed interface AgentThreadProjectionResult {
    data class Projected(val thread: AgentThreadReadModel?) : AgentThreadProjectionResult

    data class Quarantined(
        val failure: AgentEventProjectionFailureSnapshot
    ) : AgentThreadProjectionResult

    companion object {
        fun fromEvents(events: List<AgentEventEnvelope>): AgentThreadProjectionResult {
            if (events.isEmpty()) return Projected(null)
            return try {
                Projected(AgentEventReadModelProjector.project(events))
            } catch (error: AgentEventProjectionException) {
                Quarantined(
                    AgentEventProjectionFailureSnapshot(
                        code = error.code,
                        eventId = error.eventId,
                        detail = error.message.orEmpty()
                    )
                )
            }
        }
    }
}

data class ThreadMetadataReadModel(
    val threadId: String,
    val title: String,
    val summary: String?,
    val pinned: Boolean,
    val tombstoned: Boolean,
    val createdSequence: Long,
    val createdAt: Long,
    val lastActivitySequence: Long,
    val lastActivityAt: Long
)

enum class TurnReadModelState {
    RUNNING,
    WAITING_FOR_USER,
    PAUSED_USER,
    PAUSED_RECOVERY_REQUIRED,
    TERMINAL
}

data class TurnReadModel(
    val threadId: String,
    val turnId: String,
    val modelSessionIdentity: ModelSessionIdentity,
    val providerConfigSnapshotRef: String,
    val modelSessionEpoch: Long,
    val state: TurnReadModelState,
    val activeAttemptId: String?,
    val terminalStatus: String?,
    val startedAt: Long,
    val completedAt: Long?,
    val activationKind: TurnActivationKind = TurnActivationKind.DIRECT
)

enum class ConversationEntryKind {
    USER_MESSAGE,
    USER_STEER,
    ASSISTANT_START,
    ASSISTANT_STAGE_RESULT,
    ASSISTANT_USER_INTERVENTION,
    SYSTEM_NOTICE,
    TURN_ASSISTANT_REPLY,
    TURN_SYSTEM_NOTICE
}

data class ConversationEntryReadModel(
    val itemId: String,
    val sourceEventId: String,
    val threadId: String,
    val turnId: String,
    val sequence: Long,
    val occurredAt: Long,
    val kind: ConversationEntryKind,
    val text: String,
    val roundId: String? = null,
    val diagnosticCode: String? = null,
    val attachments: List<UserAttachmentPayload> = emptyList(),
    val activeDurationMillis: Long? = null
)

data class ConversationReadModel(
    val threadId: String,
    val entries: List<ConversationEntryReadModel>
) {
    fun publicReplies(turnId: String): List<ConversationEntryReadModel> = entries.filter {
        it.turnId == turnId && it.kind in setOf(
            ConversationEntryKind.ASSISTANT_START,
            ConversationEntryKind.ASSISTANT_USER_INTERVENTION,
            ConversationEntryKind.TURN_ASSISTANT_REPLY,
            ConversationEntryKind.TURN_SYSTEM_NOTICE
        )
    }
}

enum class ToolActivityState {
    PENDING,
    VERIFIED,
    FAILED,
    UNVERIFIED,
    REJECTED
}

sealed interface ActivityEntryReadModel {
    val itemId: String
    val turnId: String
    val sequence: Long
    val visible: Boolean
}

data class ToolActivityReadModel(
    override val itemId: String,
    override val turnId: String,
    override val sequence: Long,
    override val visible: Boolean,
    val roundId: String,
    val callId: String,
    val attemptId: String?,
    val executionId: String?,
    val source: ToolCallSource,
    val presentation: ToolPresentationSnapshot,
    val state: ToolActivityState,
    val diagnosticCode: String?,
    val publicProgress: String?,
    val operationCounted: Boolean,
    val startedAt: Long?,
    val finishedAt: Long?
) : ActivityEntryReadModel

data class ResultCardReadModel(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val sequence: Long,
    val title: String,
    val items: List<ResultCardItemReadModel>
)

data class ResultCardItemReadModel(
    val text: String,
    val deeplink: String
)

enum class LifecycleActivityKind {
    USER_INTERVENTION,
    TURN_PAUSED_RECOVERY_REQUIRED,
    ATTEMPT_PAUSED_USER,
    ATTEMPT_PAUSED_RECOVERY_REQUIRED,
    ATTEMPT_RESUMED
}

data class LifecycleActivityReadModel(
    override val itemId: String,
    override val turnId: String,
    override val sequence: Long,
    override val visible: Boolean = true,
    val kind: LifecycleActivityKind,
    val attemptId: String?,
    val executionId: String?,
    val callId: String?,
    val diagnosticCode: String?
) : ActivityEntryReadModel

data class NoticeActivityReadModel(
    override val itemId: String,
    override val turnId: String,
    override val sequence: Long,
    override val visible: Boolean = true,
    val text: String,
    val diagnosticCode: String?
) : ActivityEntryReadModel

data class PublicProgressReadModel(
    val eventId: String,
    val sequence: Long,
    val text: String,
    val roundIds: List<String>
)

/** One model request and all of its tool receipts share a single execution interval. */
data class ExecutionRoundTimingReadModel(
    val roundId: String,
    val startedAt: Long,
    val finishedAt: Long?
)

data class TurnActivityReadModel(
    val turnId: String,
    val state: TurnReadModelState,
    val entries: List<ActivityEntryReadModel>,
    val operationCount: Int,
    val currentLabelResourceKey: String?,
    val publicProgressHistory: List<PublicProgressReadModel>,
    val startedAt: Long,
    val completedAt: Long?,
    val totalDurationMillis: Long?,
    val activeDurationMillis: Long? = null,
    val executionRoundTimings: Map<String, ExecutionRoundTimingReadModel> = emptyMap(),
    val programShellCalls: List<ProgramShellCallReadModel> = emptyList()
) {
    val publicProgress: String?
        get() = publicProgressHistory.lastOrNull()?.text

    val currentActionTitle: String?
        get() = entries.filterIsInstance<ToolActivityReadModel>()
            .filter { it.visible && it.state == ToolActivityState.PENDING }
            .maxByOrNull { it.sequence }?.presentation?.actionTitle
}

data class ProgramShellCallReadModel(
    val childId: String, val parentCallId: String, val sequence: Long,
    val command: String, val startedAt: Long, val finishedAt: Long?,
    val result: ToolExecutionResult?
)


data class DetailAttachmentReadModel(
    val attachmentId: String,
    val eventId: String,
    val sequence: Long,
    val role: DetailAttachmentRole,
    val contentRef: String,
    val contentHash: String,
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val observationId: String?,
    /** Position of a retained screenshot in this call's batch, independent of event write order. */
    val batchActionIndex: Int? = null,
    val modelContentRef: String? = null,
    val modelSizeBytes: Long? = null,
    /** Derived from the retained tool receipt, including when replaying an earlier task. */
    val historical: Boolean = false,
    val sourceOccurredAt: Long? = null
)

data class ToolDetailReadModel(
    val threadId: String,
    val turnId: String,
    val roundId: String,
    val callId: String,
    val providerCallId: String?,
    val providerOrder: Int,
    val technicalName: String,
    val argumentsJson: String,
    val source: ToolCallSource,
    val binding: ToolBinding,
    val effect: ToolEffect,
    val presentation: ToolPresentationSnapshot,
    val roundDetailRef: String,
    val attemptId: String?,
    val executionId: String?,
    val backendSessionId: String?,
    val displayId: Int?,
    val outputText: String?,
    val executionStatus: ToolExecutionStatus?,
    val evidenceStatus: ToolEvidenceStatus?,
    val actionEvidence: ToolActionEvidence?,
    val observationId: String?,
    val diagnosticCode: String?,
    val metrics: Map<String, Long>,
    val publicProgressBlocks: List<String>,
    val startedAt: Long?,
    val finishedAt: Long?,
    val exclusiveDurationMillis: Long?,
    val attachments: List<DetailAttachmentReadModel>,
    val triggerCallId: String? = null,
    /** Private command source; presentation must redact secrets before rendering. */
    val commandSource: String? = null
)

data class ModelRoundDetailReadModel(
    val threadId: String,
    val turnId: String,
    val roundId: String,
    val roundDetailRef: String,
    val planFingerprint: String,
    val submittedItems: SubmittedItemSequenceRange,
    val providerAckSequence: Long,
    val publicBlocks: List<String>,
    val publicMessageKind: PublicMessageKind?,
    val finishMetadata: Map<String, String>,
    val completedAt: Long,
    val sourceEventId: String
)

data class CompleteRecordReadModel(
    val threadId: String,
    val sourceEventIds: List<String>,
    val events: List<AgentEventEnvelope>
)

internal fun attemptStateToTurnState(state: ExecutionAttemptState): TurnReadModelState = when (state) {
    ExecutionAttemptState.PAUSED_USER -> TurnReadModelState.PAUSED_USER
    ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED -> TurnReadModelState.PAUSED_RECOVERY_REQUIRED
    else -> TurnReadModelState.RUNNING
}
