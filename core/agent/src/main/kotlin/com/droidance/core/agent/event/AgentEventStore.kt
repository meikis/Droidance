// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.context.ContextCheckpointRecord
import com.droidance.core.agent.context.ContextCheckpointPayload
import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.llm.session.FrozenTurnModelSession
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.llm.session.ModelSessionEpochTransition
import com.droidance.core.agent.llm.session.ProviderHandleInvalidated
import kotlinx.coroutines.flow.Flow

class AgentEventConflictException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class ContextCheckpointCommit(
    val checkpointEvent: AgentEventEnvelope,
    val epochEvent: AgentEventEnvelope,
    val epochTransition: ModelSessionEpochTransition
)

data class ModelSessionEpochCommit(
    val epochEvent: AgentEventEnvelope,
    val epochTransition: ModelSessionEpochTransition
)

data class ToolSettlementCommit(
    val finishEvent: AgentEventEnvelope,
    val attachmentEvents: List<AgentEventEnvelope>
)

/** Durable event operations. Implementations own transaction and idempotency boundaries. */
interface AgentEventStore {
    suspend fun append(event: AgentEventDraft): AgentEventEnvelope

    suspend fun appendAtomically(events: List<AgentEventDraft>): List<AgentEventEnvelope>

    suspend fun recordLearningSessionStarted(
        sessionId: String,
        captureMode: String,
        skillName: String? = null,
        goal: String? = null,
        packageName: String? = null,
        appLabel: String? = null
    ): AgentEventEnvelope

    suspend fun recordLearningAction(
        payload: LearningActionRecordedEventPayload
    ): AgentEventEnvelope

    suspend fun recordLearningSessionCompleted(
        payload: LearningSessionCompletedEventPayload
    ): AgentEventEnvelope

    suspend fun recordLearningSkillCompiled(
        payload: LearningSkillCompiledEventPayload
    ): AgentEventEnvelope

    suspend fun recordCompiledSkillRunStarted(
        payload: CompiledSkillRunStartedEventPayload
    ): AgentEventEnvelope

    suspend fun recordCompiledSkillRunCompleted(
        payload: CompiledSkillRunCompletedEventPayload
    ): AgentEventEnvelope

    suspend fun eventsByKind(eventKind: AgentEventKind): List<AgentEventEnvelope>

    suspend fun createThread(
        threadId: String,
        initialTitle: String
    ): AgentEventEnvelope

    suspend fun changeThreadMetadata(
        threadId: String,
        changeId: String,
        title: String,
        summary: String?
    ): AgentEventEnvelope

    suspend fun changeThreadPin(
        threadId: String,
        changeId: String,
        isPinned: Boolean
    ): AgentEventEnvelope

    suspend fun tombstoneThread(threadId: String): AgentEventEnvelope

    suspend fun tombstoneAllThreads(): List<AgentEventEnvelope>

    /** Atomically rejects open Turns and physically removes the selected threads' events. */
    suspend fun purgeThreads(threadIds: Set<String>): Int

    suspend fun allReferencedBlobRefs(): Set<String>

    suspend fun startTurn(
        scope: TurnEventScope,
        modelSessionIdentity: ModelSessionIdentity,
        providerConfigSnapshotRef: String
    ): AgentEventEnvelope

    suspend fun recordUserMessage(
        scope: TurnEventScope,
        messageId: String,
        text: String,
        attachments: List<UserAttachmentPayload> = emptyList()
    ): AgentEventEnvelope

    suspend fun activateModelSession(
        scope: TurnEventScope,
        modelSessionIdentity: ModelSessionIdentity,
        providerConfigSnapshotRef: String,
        previousEpoch: Long,
        currentEpoch: Long,
        activationKind: TurnActivationKind = TurnActivationKind.ANDROID
    ): AgentEventEnvelope

    suspend fun recordUserSteer(
        scope: TurnEventScope,
        messageId: String,
        text: String,
        attachments: List<UserAttachmentPayload> = emptyList(),
        activeDurationMillis: Long? = null
    ): AgentEventEnvelope

    suspend fun recordMemoryContextLoaded(
        scope: TurnEventScope,
        payload: MemoryContextLoadedEventPayload,
        inputSequence: Long? = null
    ): AgentEventEnvelope

    suspend fun recordSystemNotice(
        scope: TurnEventScope,
        noticeId: String,
        text: String,
        diagnosticCode: String? = null
    ): AgentEventEnvelope

    suspend fun recordContextCheckpoint(
        threadId: String,
        turnId: String,
        checkpointPayload: ContextCheckpointPayload,
        frozenSession: FrozenTurnModelSession
    ): ContextCheckpointCommit

    suspend fun latestContextCheckpointForTurn(turnId: String): ContextCheckpointRecord?

    suspend fun recordProviderHandleInvalidation(
        threadId: String,
        turnId: String,
        frozenSession: FrozenTurnModelSession,
        trigger: ProviderHandleInvalidated
    ): ModelSessionEpochCommit

    suspend fun recordModelRoundStarted(
        start: PreparedModelRoundStart
    ): AgentEventEnvelope

    suspend fun recordModelRoundPlan(
        plan: PreparedModelRoundCommit
    ): ModelRoundPlanRecordResult

    suspend fun recordExecutionAttemptState(
        scope: ExecutionAttemptEventScope,
        transitionId: String,
        state: ExecutionAttemptState,
        diagnosticCode: String? = null
    ): AgentEventEnvelope

    suspend fun requireExecutionAttemptSnapshot(
        scope: ExecutionAttemptEventScope
    ): DurableExecutionAttemptSnapshot

    suspend fun latestExecutionAttemptForTurn(
        scope: TurnEventScope
    ): DurableExecutionAttemptSnapshot?

    suspend fun canReplaceRecoveredAttempt(previous: DurableExecutionAttemptSnapshot): Boolean

    suspend fun unfinishedTurnScopesForRecovery(): List<TurnEventScope>

    suspend fun requireTurnRecoverySnapshot(
        scope: TurnEventScope
    ): TurnRecoverySnapshot

    suspend fun terminateInterruptedTurn(scope: TurnEventScope, recoveryId: String, notice: String): AgentEventEnvelope

    suspend fun recoverInterruptedTurn(
        scope: TurnEventScope,
        recoveryId: String
    ): TurnRecoveryCommit

    suspend fun ensureExecutionAttemptRunning(
        scope: ExecutionAttemptEventScope,
        transitionId: String,
        expectedLifecycleSequence: Long
    ): DurableExecutionAttemptSnapshot

    suspend fun finishTool(
        scope: ToolLifecycleEventScope,
        result: ToolExecutionResult,
        roundDetailRef: String,
        exclusiveDurationMillis: Long? = null
    ): AgentEventEnvelope

    suspend fun finishToolWithAttachments(
        scope: ToolLifecycleEventScope,
        result: ToolExecutionResult,
        roundDetailRef: String,
        exclusiveDurationMillis: Long? = null,
        attachments: List<DetailAttachmentAddedEventPayload>
    ): ToolSettlementCommit

    suspend fun addDetailAttachment(
        scope: ToolLifecycleEventScope,
        attachment: DetailAttachmentAddedEventPayload
    ): AgentEventEnvelope

    suspend fun completeTurn(
        threadId: String,
        turnId: String,
        result: TurnResult
    ): AgentEventEnvelope

    suspend fun eventsForThread(threadId: String): List<AgentEventEnvelope>

    suspend fun eventsForLearningSession(sessionId: String): List<AgentEventEnvelope>

    suspend fun eventsForTurn(turnId: String): List<AgentEventEnvelope>

    /** One immutable snapshot of all retained, non-deleted history, including open Turns. */
    suspend fun exportSnapshot(): List<AgentEventEnvelope>

    suspend fun submittedItemRangeForTurn(turnId: String): SubmittedItemSequenceRange

    suspend fun threadReadModel(threadId: String): AgentThreadReadModel?

    suspend fun activeThreadReadModels(): List<AgentThreadReadModel>

    /** Latest non-deleted, valid conversation by its last committed conversation entry. */
    suspend fun latestConversationThreadId(): String?

    fun observeThreadReadModel(threadId: String): Flow<AgentThreadReadModel?>

    fun observeThreadProjectionResult(threadId: String): Flow<AgentThreadProjectionResult>

    fun observeActiveThreadReadModels(): Flow<List<AgentThreadReadModel>>

    suspend fun count(): Int
}
