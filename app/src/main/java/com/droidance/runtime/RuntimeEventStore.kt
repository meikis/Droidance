// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.runtime

import android.os.Bundle
import com.droidance.core.agent.event.*
import com.droidance.core.agent.context.ContextCheckpointRecord
import com.droidance.core.agent.context.ContextCheckpointPayload
import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.llm.session.FrozenTurnModelSession
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.llm.session.ModelSessionEpochTransition
import com.droidance.core.agent.llm.session.ProviderHandleInvalidated
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Transactions run to completion in the owner; GUI freezes never hold database locks. */
internal class RuntimeEventStore(private val client: RuntimeProcessClient) : AgentEventStore {
    override suspend fun append(event: AgentEventDraft): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "append")
            putString("event", Json.encodeToString<AgentEventDraft>(event))
        }).getString("result")!!)
    }

    override suspend fun appendAtomically(events: List<AgentEventDraft>): List<AgentEventEnvelope> = withContext(Dispatchers.IO) {
        Json.decodeFromString<List<AgentEventEnvelope>>(client.command("events", Bundle().apply {
            putString("operation", "appendAtomically")
            putString("events", Json.encodeToString<List<AgentEventDraft>>(events))
        }).getString("result")!!)
    }

    override suspend fun recordLearningSessionStarted(sessionId: String, captureMode: String, skillName: String?, goal: String?, packageName: String?, appLabel: String?): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "recordLearningSessionStarted")
            putString("sessionId", Json.encodeToString<String>(sessionId))
            putString("captureMode", Json.encodeToString<String>(captureMode))
            putString("skillName", Json.encodeToString<String?>(skillName))
            putString("goal", Json.encodeToString<String?>(goal))
            putString("packageName", Json.encodeToString<String?>(packageName))
            putString("appLabel", Json.encodeToString<String?>(appLabel))
        }).getString("result")!!)
    }

    override suspend fun recordLearningAction(payload: LearningActionRecordedEventPayload): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "recordLearningAction")
            putString("payload", Json.encodeToString<LearningActionRecordedEventPayload>(payload))
        }).getString("result")!!)
    }

    override suspend fun recordLearningSessionCompleted(payload: LearningSessionCompletedEventPayload): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "recordLearningSessionCompleted")
            putString("payload", Json.encodeToString<LearningSessionCompletedEventPayload>(payload))
        }).getString("result")!!)
    }

    override suspend fun recordLearningSkillCompiled(payload: LearningSkillCompiledEventPayload): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "recordLearningSkillCompiled")
            putString("payload", Json.encodeToString<LearningSkillCompiledEventPayload>(payload))
        }).getString("result")!!)
    }

    override suspend fun recordCompiledSkillRunStarted(payload: CompiledSkillRunStartedEventPayload): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "recordCompiledSkillRunStarted")
            putString("payload", Json.encodeToString<CompiledSkillRunStartedEventPayload>(payload))
        }).getString("result")!!)
    }

    override suspend fun recordCompiledSkillRunCompleted(payload: CompiledSkillRunCompletedEventPayload): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "recordCompiledSkillRunCompleted")
            putString("payload", Json.encodeToString<CompiledSkillRunCompletedEventPayload>(payload))
        }).getString("result")!!)
    }

    override suspend fun eventsByKind(eventKind: AgentEventKind): List<AgentEventEnvelope> = withContext(Dispatchers.IO) {
        Json.decodeFromString<List<AgentEventEnvelope>>(client.command("events", Bundle().apply {
            putString("operation", "eventsByKind")
            putString("eventKind", Json.encodeToString<AgentEventKind>(eventKind))
        }).getString("result")!!)
    }

    override suspend fun createThread(threadId: String, initialTitle: String): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "createThread")
            putString("threadId", Json.encodeToString<String>(threadId))
            putString("initialTitle", Json.encodeToString<String>(initialTitle))
        }).getString("result")!!)
    }

    override suspend fun changeThreadMetadata(threadId: String, changeId: String, title: String, summary: String?): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "changeThreadMetadata")
            putString("threadId", Json.encodeToString<String>(threadId))
            putString("changeId", Json.encodeToString<String>(changeId))
            putString("title", Json.encodeToString<String>(title))
            putString("summary", Json.encodeToString<String?>(summary))
        }).getString("result")!!)
    }

    override suspend fun changeThreadPin(threadId: String, changeId: String, isPinned: Boolean): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "changeThreadPin")
            putString("threadId", Json.encodeToString<String>(threadId))
            putString("changeId", Json.encodeToString<String>(changeId))
            putString("isPinned", Json.encodeToString<Boolean>(isPinned))
        }).getString("result")!!)
    }

    override suspend fun tombstoneThread(threadId: String): AgentEventEnvelope = withContext(Dispatchers.IO) {
        Json.decodeFromString<AgentEventEnvelope>(client.command("events", Bundle().apply {
            putString("operation", "tombstoneThread")
            putString("threadId", Json.encodeToString<String>(threadId))
        }).getString("result")!!)
    }

    override suspend fun tombstoneAllThreads(): List<AgentEventEnvelope> = withContext(Dispatchers.IO) {
        Json.decodeFromString<List<AgentEventEnvelope>>(client.command("events", Bundle().apply {
            putString("operation", "tombstoneAllThreads")
        }).getString("result")!!)
    }

    override suspend fun purgeThreads(threadIds: Set<String>): Int = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun allReferencedBlobRefs(): Set<String> = withContext(Dispatchers.IO) {
        Json.decodeFromString<Set<String>>(client.command("events", Bundle().apply {
            putString("operation", "allReferencedBlobRefs")
        }).getString("result")!!)
    }

    override suspend fun startTurn(scope: TurnEventScope, modelSessionIdentity: ModelSessionIdentity, providerConfigSnapshotRef: String): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordUserMessage(scope: TurnEventScope, messageId: String, text: String, attachments: List<UserAttachmentPayload>): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun activateModelSession(scope: TurnEventScope, modelSessionIdentity: ModelSessionIdentity, providerConfigSnapshotRef: String, previousEpoch: Long, currentEpoch: Long, activationKind: TurnActivationKind): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordUserSteer(scope: TurnEventScope, messageId: String, text: String, attachments: List<UserAttachmentPayload>, activeDurationMillis: Long?): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordMemoryContextLoaded(scope: TurnEventScope, payload: MemoryContextLoadedEventPayload, inputSequence: Long?): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordSystemNotice(scope: TurnEventScope, noticeId: String, text: String, diagnosticCode: String?): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordContextCheckpoint(threadId: String, turnId: String, checkpointPayload: ContextCheckpointPayload, frozenSession: FrozenTurnModelSession): ContextCheckpointCommit = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun latestContextCheckpointForTurn(turnId: String): ContextCheckpointRecord? = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordProviderHandleInvalidation(threadId: String, turnId: String, frozenSession: FrozenTurnModelSession, trigger: ProviderHandleInvalidated): ModelSessionEpochCommit = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordModelRoundStarted(start: PreparedModelRoundStart): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordModelRoundPlan(plan: PreparedModelRoundCommit): ModelRoundPlanRecordResult = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recordExecutionAttemptState(scope: ExecutionAttemptEventScope, transitionId: String, state: ExecutionAttemptState, diagnosticCode: String?): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun requireExecutionAttemptSnapshot(scope: ExecutionAttemptEventScope): DurableExecutionAttemptSnapshot = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun latestExecutionAttemptForTurn(scope: TurnEventScope): DurableExecutionAttemptSnapshot? = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun canReplaceRecoveredAttempt(previous: DurableExecutionAttemptSnapshot): Boolean = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun unfinishedTurnScopesForRecovery(): List<TurnEventScope> = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun requireTurnRecoverySnapshot(scope: TurnEventScope): TurnRecoverySnapshot = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun terminateInterruptedTurn(scope: TurnEventScope, recoveryId: String, notice: String): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun recoverInterruptedTurn(scope: TurnEventScope, recoveryId: String): TurnRecoveryCommit = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun ensureExecutionAttemptRunning(scope: ExecutionAttemptEventScope, transitionId: String, expectedLifecycleSequence: Long): DurableExecutionAttemptSnapshot = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun finishTool(scope: ToolLifecycleEventScope, result: ToolExecutionResult, roundDetailRef: String, exclusiveDurationMillis: Long?): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun finishToolWithAttachments(scope: ToolLifecycleEventScope, result: ToolExecutionResult, roundDetailRef: String, exclusiveDurationMillis: Long?, attachments: List<DetailAttachmentAddedEventPayload>): ToolSettlementCommit = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun addDetailAttachment(scope: ToolLifecycleEventScope, attachment: DetailAttachmentAddedEventPayload): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun completeTurn(threadId: String, turnId: String, result: TurnResult): AgentEventEnvelope = error("Task lifecycle persistence belongs to the resident owner")

    override suspend fun eventsForThread(threadId: String): List<AgentEventEnvelope> = withContext(Dispatchers.IO) {
        Json.decodeFromString<List<AgentEventEnvelope>>(client.command("events", Bundle().apply {
            putString("operation", "eventsForThread")
            putString("threadId", Json.encodeToString<String>(threadId))
        }).getString("result")!!)
    }

    override suspend fun eventsForLearningSession(sessionId: String): List<AgentEventEnvelope> = withContext(Dispatchers.IO) {
        Json.decodeFromString<List<AgentEventEnvelope>>(client.command("events", Bundle().apply {
            putString("operation", "eventsForLearningSession")
            putString("sessionId", Json.encodeToString<String>(sessionId))
        }).getString("result")!!)
    }

    override suspend fun eventsForTurn(turnId: String): List<AgentEventEnvelope> = withContext(Dispatchers.IO) {
        Json.decodeFromString<List<AgentEventEnvelope>>(client.command("events", Bundle().apply {
            putString("operation", "eventsForTurn")
            putString("turnId", Json.encodeToString<String>(turnId))
        }).getString("result")!!)
    }

    override suspend fun exportSnapshot(): List<AgentEventEnvelope> = withContext(Dispatchers.IO) {
        Json.decodeFromString<List<AgentEventEnvelope>>(client.command("events", Bundle().apply {
            putString("operation", "exportSnapshot")
        }).getString("result")!!)
    }

    override suspend fun submittedItemRangeForTurn(turnId: String): SubmittedItemSequenceRange = withContext(Dispatchers.IO) {
        Json.decodeFromString<SubmittedItemSequenceRange>(client.command("events", Bundle().apply {
            putString("operation", "submittedItemRangeForTurn")
            putString("turnId", Json.encodeToString<String>(turnId))
        }).getString("result")!!)
    }

    override suspend fun latestConversationThreadId(): String? = withContext(Dispatchers.IO) {
        try { Json.decodeFromString<String?>(client.command("events", Bundle().apply {
            putString("operation", "latestConversationThreadId")
        }).getString("result")!!) }
        catch (_: com.droidance.core.device.agent.device.RuntimeUnavailableException) { null }
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        Json.decodeFromString<Int>(client.command("events", Bundle().apply {
            putString("operation", "count")
        }).getString("result")!!)
    }

    override suspend fun threadReadModel(threadId: String): AgentThreadReadModel? =
        eventsForThread(threadId).takeIf { it.isNotEmpty() }?.let(AgentEventReadModelProjector::project)
    override suspend fun activeThreadReadModels(): List<AgentThreadReadModel> =
        eventsByKind(AgentEventKind.THREAD_CREATED).mapNotNull { event ->
            (AgentThreadProjectionResult.fromEvents(eventsForThread(event.threadId)) as? AgentThreadProjectionResult.Projected)?.thread
        }.filterNot { it.metadata.tombstoned }.sortedWith(compareByDescending<AgentThreadReadModel> { it.metadata.pinned }.thenByDescending { it.metadata.lastActivitySequence })
    override fun observeThreadReadModel(threadId: String): Flow<AgentThreadReadModel?> =
        observe { threadReadModel(threadId) }
    override fun observeThreadProjectionResult(threadId: String): Flow<AgentThreadProjectionResult> =
        observe { AgentThreadProjectionResult.fromEvents(eventsForThread(threadId)) }
    override fun observeActiveThreadReadModels(): Flow<List<AgentThreadReadModel>> = observe { activeThreadReadModels() }
    private fun <T> observe(read: suspend () -> T): Flow<T> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                client.owner()
                client.historyRevision.filterNotNull().collect { emit(read()) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: com.droidance.core.device.agent.device.RuntimeUnavailableException) { delay(1000) }
            catch (_: android.os.RemoteException) { delay(1000) }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
