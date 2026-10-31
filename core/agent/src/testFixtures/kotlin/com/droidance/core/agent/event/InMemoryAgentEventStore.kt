// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionAttemptTransitions
import com.droidance.core.agent.attempt.PreparedAttemptBinding
import com.droidance.core.agent.context.ContextCheckpointPayload
import com.droidance.core.agent.context.ContextCheckpointRecord
import com.droidance.core.agent.context.ContextCheckpointValidator
import com.droidance.core.agent.llm.session.ContextCheckpointApplied
import com.droidance.core.agent.llm.session.FrozenTurnModelSession
import com.droidance.core.agent.llm.session.ModelSessionEpochPolicy
import com.droidance.core.agent.llm.session.ModelSessionEpochTransition
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.llm.session.ProviderHandleInvalidated
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

/**
 * Pure JVM in-memory implementation of AgentEventStore.
 * Enables complete task coordination integration testing without Android or Room dependencies.
 */
class InMemoryAgentEventStore(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() }
) : AgentEventStore {
    private val mutex = Mutex()
    private val events = mutableListOf<AgentEventEnvelope>()
    private val mutationNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private val coordinatedEventKinds = setOf(
        AgentEventKind.THREAD_CREATED,
        AgentEventKind.THREAD_METADATA_CHANGED,
        AgentEventKind.THREAD_PIN_CHANGED,
        AgentEventKind.THREAD_TOMBSTONED,
        AgentEventKind.TURN_STARTED,
        AgentEventKind.USER_MESSAGE,
        AgentEventKind.USER_STEER,
        AgentEventKind.MODEL_SESSION_ACTIVATED,
        AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED,
        AgentEventKind.CONTEXT_CHECKPOINT,
        AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED,
        AgentEventKind.MODEL_ROUND_STARTED,
        AgentEventKind.TOOL_CALL,
        AgentEventKind.TOOL_STARTED,
        AgentEventKind.TOOL_FINISHED,
        AgentEventKind.EXECUTION_ATTEMPT_CREATED,
        AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
        AgentEventKind.TURN_RECOVERY_REQUIRED,
        AgentEventKind.TURN_COMPLETED
    )

    private fun notifyMutation() {
        mutationNotifier.tryEmit(Unit)
    }

    override suspend fun append(event: AgentEventDraft): AgentEventEnvelope =
        appendAtomically(listOf(event)).single()

    override suspend fun appendAtomically(eventsToAppend: List<AgentEventDraft>): List<AgentEventEnvelope> {
        require(eventsToAppend.isNotEmpty()) { "An event batch cannot be empty" }
        require(eventsToAppend.none { it.eventKind in coordinatedEventKinds }) {
            "Coordinated lifecycle events require their typed store API"
        }
        eventsToAppend.forEach(AgentEventSchema::validate)
        mutex.withLock {
            val previousSize = events.size
            val committed = try {
                eventsToAppend.map { appendDraftInternal(it) }
            } catch (failure: Throwable) {
                // Readers and notifications are mutex-bound: a failed batch must leave no prefix.
                events.subList(previousSize, events.size).clear()
                throw failure
            }
            notifyMutation()
            return committed
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun appendDraftInternal(draft: AgentEventDraft): AgentEventEnvelope {
        AgentEventSchema.validate(draft)
        draft.idempotencyKey.takeIf { it.isNotBlank() }?.let { key ->
            val existing = events.firstOrNull { it.idempotencyKey == key }
            if (existing != null) {
                verifyIdempotentRetry(existing, draft)
                return existing
            }
        }
        val sequence = (events.lastOrNull()?.sequence ?: 0L) + 1L
        val canonicalPayload = canonicalizeJson(draft.payloadJson)
        val payloadHash = sha256(canonicalPayload)
        val envelope = AgentEventEnvelope(
            schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
            eventId = eventIdFactory(),
            eventKind = draft.eventKind,
            sequence = sequence,
            occurredAt = nowMs(),
            threadId = draft.threadId,
            turnId = draft.turnId,
            roundId = draft.roundId,
            attemptId = draft.attemptId,
            executionId = draft.executionId,
            callId = draft.callId,
            payloadHash = payloadHash,
            payloadJson = canonicalPayload,
            idempotencyKey = draft.idempotencyKey
        )
        events.add(envelope)
        return envelope
    }

    private fun verifyIdempotentRetry(existing: AgentEventEnvelope, draft: AgentEventDraft) {
        val matches = existing.eventKind == draft.eventKind &&
            existing.threadId == draft.threadId &&
            existing.turnId == draft.turnId &&
            existing.roundId == draft.roundId &&
            existing.attemptId == draft.attemptId &&
            existing.executionId == draft.executionId &&
            existing.callId == draft.callId &&
            existing.payloadJson == canonicalizeJson(draft.payloadJson)
        if (!matches) {
            throw AgentEventConflictException(
                "Idempotency key ${draft.idempotencyKey} was reused with different event content"
            )
        }
    }

    private fun requireActiveThread(threadId: String) {
        val threadEvents = events.filter { it.threadId == threadId }
        if (threadEvents.isEmpty()) {
            throw AgentEventConflictException("Thread $threadId does not exist")
        }
        if (threadEvents.any { it.eventKind == AgentEventKind.THREAD_TOMBSTONED }) {
            throw AgentEventConflictException("Thread $threadId is tombstoned")
        }
    }

    private fun requireOpenTurn(threadId: String, turnId: String) {
        requireActiveThread(threadId)
        val turnEvents = events.filter { it.turnId == turnId }
        if (turnEvents.none { it.eventKind == AgentEventKind.TURN_STARTED }) {
            throw AgentEventConflictException("Turn $turnId does not exist")
        }
        if (turnEvents.any { it.eventKind == AgentEventKind.TURN_COMPLETED }) {
            throw AgentEventConflictException("Turn $turnId is already completed")
        }
    }

    private fun requireOpenTurn(scope: TurnEventScope) =
        requireOpenTurn(scope.threadId, scope.turnId)

    private fun learningThreadId(sessionId: String): String = "skill-learning:$sessionId"
    private fun skillRunThreadId(runId: String): String = "compiled-skill-run:$runId"

    override suspend fun recordLearningSessionStarted(
        sessionId: String,
        captureMode: String,
        skillName: String?,
        goal: String?,
        packageName: String?,
        appLabel: String?
    ): AgentEventEnvelope = append(
        AgentEventDraft(
            eventKind = AgentEventKind.LEARNING_SESSION_STARTED,
            threadId = learningThreadId(sessionId),
            payloadJson = Json.encodeToString(
                LearningSessionStartedEventPayload(
                    contractVersion = SKILL_LEARNING_EVENT_CONTRACT_VERSION,
                    sessionId = sessionId,
                    captureMode = captureMode,
                    skillName = skillName,
                    goal = goal,
                    packageName = packageName,
                    appLabel = appLabel
                )
            ),
            idempotencyKey = "learning:$sessionId:started"
        )
    )

    override suspend fun recordLearningAction(
        payload: LearningActionRecordedEventPayload
    ): AgentEventEnvelope = append(
        AgentEventDraft(
            eventKind = AgentEventKind.LEARNING_ACTION_RECORDED,
            threadId = learningThreadId(payload.sessionId),
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "learning:${payload.sessionId}:action:${payload.actionIndex}"
        )
    )

    override suspend fun recordLearningSessionCompleted(
        payload: LearningSessionCompletedEventPayload
    ): AgentEventEnvelope = append(
        AgentEventDraft(
            eventKind = AgentEventKind.LEARNING_SESSION_COMPLETED,
            threadId = learningThreadId(payload.sessionId),
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "learning:${payload.sessionId}:completed"
        )
    )

    override suspend fun recordLearningSkillCompiled(
        payload: LearningSkillCompiledEventPayload
    ): AgentEventEnvelope = append(
        AgentEventDraft(
            eventKind = AgentEventKind.LEARNING_SKILL_COMPILED,
            threadId = learningThreadId(payload.sessionId),
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "learning:${payload.sessionId}:skill:${payload.skillId}"
        )
    )

    override suspend fun recordCompiledSkillRunStarted(
        payload: CompiledSkillRunStartedEventPayload
    ): AgentEventEnvelope = append(
        AgentEventDraft(
            eventKind = AgentEventKind.COMPILED_SKILL_RUN_STARTED,
            threadId = skillRunThreadId(payload.runId),
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "skill-run:${payload.runId}:started"
        )
    )

    override suspend fun recordCompiledSkillRunCompleted(
        payload: CompiledSkillRunCompletedEventPayload
    ): AgentEventEnvelope = append(
        AgentEventDraft(
            eventKind = AgentEventKind.COMPILED_SKILL_RUN_COMPLETED,
            threadId = skillRunThreadId(payload.runId),
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "skill-run:${payload.runId}:completed"
        )
    )

    override suspend fun eventsByKind(eventKind: AgentEventKind): List<AgentEventEnvelope> =
        mutex.withLock { events.filter { it.eventKind == eventKind } }

    override suspend fun createThread(
        threadId: String,
        initialTitle: String
    ): AgentEventEnvelope = mutex.withLock {
        if (events.any { it.threadId == threadId }) {
            throw AgentEventConflictException("Thread $threadId already owns durable facts")
        }
        val payload = ThreadCreatedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            initialTitle = initialTitle
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.THREAD_CREATED,
            threadId = threadId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "thread:$threadId:created"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun changeThreadMetadata(
        threadId: String,
        changeId: String,
        title: String,
        summary: String?
    ): AgentEventEnvelope = mutex.withLock {
        require(changeId.isNotBlank()) { "Thread metadata changeId cannot be blank" }
        requireActiveThread(threadId)
        val payload = ThreadMetadataChangedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            title = title,
            summary = summary
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.THREAD_METADATA_CHANGED,
            threadId = threadId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "thread:$threadId:metadata:$changeId"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun changeThreadPin(
        threadId: String,
        changeId: String,
        isPinned: Boolean
    ): AgentEventEnvelope = mutex.withLock {
        require(changeId.isNotBlank()) { "Thread pin changeId cannot be blank" }
        requireActiveThread(threadId)
        val payload = ThreadPinChangedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            isPinned = isPinned
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.THREAD_PIN_CHANGED,
            threadId = threadId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "thread:$threadId:pin:$changeId"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun tombstoneThread(threadId: String): AgentEventEnvelope = mutex.withLock {
        requireActiveThread(threadId)
        val openTurn = events.filter { it.threadId == threadId && it.eventKind == AgentEventKind.TURN_STARTED }
            .firstOrNull { start ->
                events.none { it.turnId == start.turnId && it.eventKind == AgentEventKind.TURN_COMPLETED }
            }
        if (openTurn != null) {
            throw AgentEventConflictException("Thread $threadId cannot be tombstoned with an open Turn")
        }
        val payload = ThreadTombstonedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION)
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.THREAD_TOMBSTONED,
            threadId = threadId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "thread:$threadId:tombstoned"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun tombstoneAllThreads(): List<AgentEventEnvelope> = mutex.withLock {
        val activeThreadIds = events.map { it.threadId }.distinct().filter { threadId ->
            events.none { it.threadId == threadId && it.eventKind == AgentEventKind.THREAD_TOMBSTONED }
        }
        activeThreadIds.forEach { threadId ->
            val openTurn = events.filter { it.threadId == threadId && it.eventKind == AgentEventKind.TURN_STARTED }
                .firstOrNull { start ->
                    events.none { it.turnId == start.turnId && it.eventKind == AgentEventKind.TURN_COMPLETED }
                }
            if (openTurn != null) {
                throw AgentEventConflictException("Thread $threadId cannot be tombstoned with an open Turn")
            }
        }
        val result = activeThreadIds.map { threadId ->
            val payload = ThreadTombstonedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION)
            appendDraftInternal(
                AgentEventDraft(
                    eventKind = AgentEventKind.THREAD_TOMBSTONED,
                    threadId = threadId,
                    payloadJson = Json.encodeToString(payload),
                    idempotencyKey = "thread:$threadId:tombstoned"
                )
            )
        }
        notifyMutation()
        result
    }

    override suspend fun purgeThreads(threadIds: Set<String>): Int = mutex.withLock {
        threadIds.forEach { threadId ->
            val threadEvents = events.filter { it.threadId == threadId }
            val completedTurns = threadEvents.filter { it.eventKind == AgentEventKind.TURN_COMPLETED }
                .mapTo(mutableSetOf()) { it.turnId }
            if (threadEvents.any { it.eventKind == AgentEventKind.TURN_STARTED && it.turnId !in completedTurns }) {
                throw AgentEventConflictException("Thread $threadId cannot be purged with an open Turn")
            }
        }
        val initialSize = events.size
        events.removeAll { it.threadId in threadIds }
        val removed = initialSize - events.size
        if (removed > 0) notifyMutation()
        removed
    }

    override suspend fun allReferencedBlobRefs(): Set<String> = mutex.withLock {
        val regex = Regex("sha256:[0-9a-f]{64}")
        val refs = mutableSetOf<String>()
        for (event in events) {
            regex.findAll(event.payloadJson).forEach { match ->
                refs.add(match.value)
            }
        }
        refs
    }

    override suspend fun startTurn(
        scope: TurnEventScope,
        modelSessionIdentity: ModelSessionIdentity,
        providerConfigSnapshotRef: String
    ): AgentEventEnvelope = mutex.withLock {
        require(modelSessionIdentity.threadId == scope.threadId) {
            "Turn ModelSessionIdentity must belong to its Thread"
        }
        requireActiveThread(scope.threadId)
        if (events.any { it.turnId == scope.turnId }) {
            throw AgentEventConflictException("Turn ${scope.turnId} already owns durable facts")
        }
        val payload = TurnStartedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            modelSessionIdentity = modelSessionIdentity,
            providerConfigSnapshotRef = providerConfigSnapshotRef
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.TURN_STARTED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "turn:${scope.turnId}:started"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun recordUserMessage(
        scope: TurnEventScope,
        messageId: String,
        text: String,
        attachments: List<UserAttachmentPayload>
    ): AgentEventEnvelope = mutex.withLock {
        requireOpenTurn(scope)
        val payload = UserTextEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            messageId = messageId,
            text = text,
            attachments = attachments
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.USER_MESSAGE,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "message:$messageId"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun activateModelSession(
        scope: TurnEventScope,
        modelSessionIdentity: ModelSessionIdentity,
        providerConfigSnapshotRef: String,
        previousEpoch: Long,
        currentEpoch: Long,
        activationKind: TurnActivationKind
    ): AgentEventEnvelope = mutex.withLock {
        require(activationKind != TurnActivationKind.DIRECT)
        require(modelSessionIdentity.threadId == scope.threadId) {
            "Activated ModelSessionIdentity must belong to its Thread"
        }
        requireOpenTurn(scope)
        val turnEvents = events.filter { it.turnId == scope.turnId }
        require(turnEvents.none {
            it.eventKind in setOf(AgentEventKind.MODEL_SESSION_ACTIVATED, AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED)
        }) { "A Turn can activate its Android model session only once" }
        require(turnEvents.none { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED }) {
            "Model session activation must precede device execution"
        }
        val payload = ModelSessionActivatedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            modelSessionIdentity = modelSessionIdentity,
            providerConfigSnapshotRef = providerConfigSnapshotRef,
            previousEpoch = previousEpoch,
            currentEpoch = currentEpoch
        )
        val draft = AgentEventDraft(
            eventKind = if (activationKind == TurnActivationKind.LOCAL)
                AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED else AgentEventKind.MODEL_SESSION_ACTIVATED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "turn:${scope.turnId}:model-session-activated"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun recordUserSteer(
        scope: TurnEventScope,
        messageId: String,
        text: String,
        attachments: List<UserAttachmentPayload>,
        activeDurationMillis: Long?
    ): AgentEventEnvelope = mutex.withLock {
        requireOpenTurn(scope)
        val payload = UserTextEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            messageId = messageId,
            text = text,
            attachments = attachments,
            activeDurationMillis = activeDurationMillis
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.USER_STEER,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "steer:$messageId"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun recordMemoryContextLoaded(
        scope: TurnEventScope,
        payload: MemoryContextLoadedEventPayload,
        inputSequence: Long?
    ): AgentEventEnvelope = mutex.withLock {
        requireOpenTurn(scope)
        val inputs = events.filter { it.turnId == scope.turnId &&
            it.eventKind in setOf(AgentEventKind.USER_MESSAGE, AgentEventKind.USER_STEER) }
        val source = inputSequence ?: inputs.firstOrNull()?.sequence
        require(source != null && inputs.lastOrNull()?.sequence == source) { "Memory context requires the current user input" }
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.MEMORY_CONTEXT_LOADED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "turn:${scope.turnId}:input:$source:memory-context" + payload.contextKey?.let { ":$it" }.orEmpty()
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun recordSystemNotice(
        scope: TurnEventScope,
        noticeId: String,
        text: String,
        diagnosticCode: String?
    ): AgentEventEnvelope = mutex.withLock {
        requireOpenTurn(scope)
        val payload = SystemNoticeEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            noticeId = noticeId,
            text = text,
            diagnosticCode = diagnosticCode
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.SYSTEM_NOTICE,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "notice:$noticeId"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    private fun modelSessionIdentityHash(identity: ModelSessionIdentity): String =
        sha256(canonicalizeJson(Json.encodeToString(identity)))

    override suspend fun recordContextCheckpoint(
        threadId: String,
        turnId: String,
        checkpointPayload: ContextCheckpointPayload,
        frozenSession: FrozenTurnModelSession
    ): ContextCheckpointCommit = mutex.withLock {
        require(frozenSession.turnId == turnId) { "Context checkpoint turn must match the frozen ModelSession" }
        val currentSession = frozenSession.key
        require(currentSession.identity.threadId == threadId) { "Context checkpoint thread must match frozen identity" }
        ContextCheckpointValidator.validate(checkpointPayload)
        val nextEpoch = Math.addExact(currentSession.epoch, 1L)
        val identityHash = modelSessionIdentityHash(currentSession.identity)
        val checkpointDraft = AgentEventDraft(
            eventKind = AgentEventKind.CONTEXT_CHECKPOINT,
            threadId = threadId,
            turnId = turnId,
            payloadJson = Json.encodeToString(checkpointPayload),
            idempotencyKey = "model-session:$turnId:$identityHash:checkpoint:$nextEpoch"
        )
        val checkpoint = appendDraftInternal(checkpointDraft)
        val durableTransition = ModelSessionEpochPolicy.advance(
            current = currentSession,
            trigger = ContextCheckpointApplied(checkpoint.eventId)
        )
        val epochDraft = AgentEventDraft(
            eventKind = AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED,
            threadId = threadId,
            turnId = turnId,
            payloadJson = Json.encodeToString(
                ModelSessionEpochAdvancedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    previousEpoch = durableTransition.previous.epoch,
                    currentEpoch = durableTransition.current.epoch,
                    reason = durableTransition.reason,
                    evidenceRef = durableTransition.evidenceRef,
                    identity = durableTransition.current.identity
                )
            ),
            idempotencyKey = "model-session:$turnId:$identityHash:epoch:$nextEpoch"
        )
        val epoch = appendDraftInternal(epochDraft)
        notifyMutation()
        ContextCheckpointCommit(checkpoint, epoch, durableTransition)
    }

    override suspend fun latestContextCheckpointForTurn(turnId: String): ContextCheckpointRecord? =
        mutex.withLock {
            events.asReversed()
                .firstOrNull { it.turnId == turnId && it.eventKind == AgentEventKind.CONTEXT_CHECKPOINT }
                ?.let { event ->
                    ContextCheckpointRecord(
                        eventId = event.eventId,
                        payload = Json.decodeFromString(ContextCheckpointPayload.serializer(), event.payloadJson)
                    )
                }
        }

    override suspend fun recordProviderHandleInvalidation(
        threadId: String,
        turnId: String,
        frozenSession: FrozenTurnModelSession,
        trigger: ProviderHandleInvalidated
    ): ModelSessionEpochCommit = mutex.withLock {
        val currentSession = frozenSession.key
        val transition = ModelSessionEpochPolicy.advance(currentSession, trigger)
        val identityHash = modelSessionIdentityHash(currentSession.identity)
        val epochDraft = AgentEventDraft(
            eventKind = AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED,
            threadId = threadId,
            turnId = turnId,
            payloadJson = Json.encodeToString(
                ModelSessionEpochAdvancedEventPayload(
                    contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                    previousEpoch = transition.previous.epoch,
                    currentEpoch = transition.current.epoch,
                    reason = transition.reason,
                    evidenceRef = transition.evidenceRef,
                    identity = transition.current.identity
                )
            ),
            idempotencyKey = "model-session:$turnId:$identityHash:epoch:${transition.current.epoch}"
        )
        val epochEvent = appendDraftInternal(epochDraft)
        notifyMutation()
        ModelSessionEpochCommit(epochEvent, transition)
    }

    override suspend fun recordModelRoundStarted(
        start: PreparedModelRoundStart
    ): AgentEventEnvelope = mutex.withLock {
        AgentEventSchema.validate(start.draft)
        start.draft.idempotencyKey.takeIf { it.isNotBlank() }?.let { key ->
            val existing = events.firstOrNull { it.idempotencyKey == key }
            if (existing != null) {
                verifyIdempotentRetry(existing, start.draft)
                return existing
            }
        }
        requireOpenTurn(start.scope.threadId, start.scope.turnId)
        if (events.any { it.roundId == start.scope.roundId && it.eventKind == AgentEventKind.MODEL_ROUND_STARTED }) {
            throw AgentEventConflictException("ModelRound ${start.scope.roundId} already has a request boundary")
        }
        val envelope = appendDraftInternal(start.draft)
        notifyMutation()
        envelope
    }

    override suspend fun recordModelRoundPlan(
        plan: PreparedModelRoundCommit
    ): ModelRoundPlanRecordResult = mutex.withLock {
        plan.drafts.forEach(AgentEventSchema::validate)
        val existingByDraft = plan.drafts.map { draft ->
            draft.idempotencyKey.takeIf { it.isNotBlank() }?.let { key ->
                events.firstOrNull { it.idempotencyKey == key }
            }
        }
        if (existingByDraft.all { it != null }) {
            val existing = existingByDraft.filterNotNull()
            return AlreadyCommittedModelRoundPlan(RecordedModelRoundPlan.from(plan, existing))
        }
        if (existingByDraft.any { it != null }) {
            throw AgentEventConflictException("ModelRound ${plan.scope.roundId} has a partial or conflicting plan batch")
        }
        requireOpenTurn(plan.scope.threadId, plan.scope.turnId)
        val committed = plan.drafts.map { appendDraftInternal(it) }
        notifyMutation()
        NewlyCommittedModelRoundPlan(RecordedModelRoundPlan.from(plan, committed))
    }

    override suspend fun recordExecutionAttemptState(
        scope: ExecutionAttemptEventScope,
        transitionId: String,
        state: ExecutionAttemptState,
        diagnosticCode: String?
    ): AgentEventEnvelope = mutex.withLock {
        require(transitionId.isNotBlank()) { "ExecutionAttempt transitionId cannot be blank" }
        val payload = ExecutionAttemptStateChangedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            state = state,
            diagnosticCode = diagnosticCode
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            attemptId = scope.attemptId,
            executionId = scope.executionId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "attempt:${scope.attemptId}:state:$transitionId"
        )
        requireOpenTurn(scope.threadId, scope.turnId)
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    private fun attemptsForTurnInternal(threadId: String, turnId: String): List<DurableExecutionAttemptSnapshot> {
        val turnEvents = events.filter { it.turnId == turnId }
        val createdEvents = turnEvents.filter { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED }
        return createdEvents.map { created ->
            val scope = ExecutionAttemptEventScope(
                threadId = created.threadId,
                turnId = turnId,
                attemptId = checkNotNull(created.attemptId),
                executionId = checkNotNull(created.executionId)
            )
            val creationPayload = Json.decodeFromString(
                ExecutionAttemptCreatedEventPayload.serializer(),
                created.payloadJson
            )
            val stateEvents = turnEvents.filter {
                it.attemptId == scope.attemptId && it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED
            }
            val currentState = stateEvents.lastOrNull()?.let {
                Json.decodeFromString(ExecutionAttemptStateChangedEventPayload.serializer(), it.payloadJson).state
            } ?: ExecutionAttemptState.CREATED
            val lifecycleSeq = stateEvents.lastOrNull()?.sequence ?: created.sequence
            DurableExecutionAttemptSnapshot(
                scope = scope,
                state = currentState,
                creationSequence = created.sequence,
                lifecycleSequence = lifecycleSeq,
                context = creationPayload.context,
                capabilitySnapshot = creationPayload.capabilitySnapshot
            )
        }
    }

    override suspend fun requireExecutionAttemptSnapshot(
        scope: ExecutionAttemptEventScope
    ): DurableExecutionAttemptSnapshot = mutex.withLock {
        attemptsForTurnInternal(scope.threadId, scope.turnId).firstOrNull { it.scope.attemptId == scope.attemptId }
            ?: throw AgentEventConflictException("ExecutionAttempt ${scope.attemptId} does not exist")
    }

    override suspend fun latestExecutionAttemptForTurn(
        scope: TurnEventScope
    ): DurableExecutionAttemptSnapshot? = mutex.withLock {
        attemptsForTurnInternal(scope.threadId, scope.turnId).lastOrNull()
    }

    override suspend fun canReplaceRecoveredAttempt(previous: DurableExecutionAttemptSnapshot): Boolean = mutex.withLock {
        if (previous.state != ExecutionAttemptState.INTERRUPTED) return false
        val latest = attemptsForTurnInternal(previous.scope.threadId, previous.scope.turnId).lastOrNull()
        if (latest?.scope != previous.scope || latest.lifecycleSequence != previous.lifecycleSequence) return false
        val threadEvents = events.filter { it.threadId == previous.scope.threadId }
        if (threadEvents.isEmpty()) return false
        val turn = AgentEventReadModelProjector.project(threadEvents).turns.singleOrNull {
            it.turnId == previous.scope.turnId
        } ?: return false
        if (turn.state != TurnReadModelState.RUNNING) return false
        return events.filter { it.turnId == previous.scope.turnId }.any { event ->
            event.sequence > previous.lifecycleSequence &&
                event.eventKind == AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED &&
                Json.decodeFromString(ModelSessionEpochAdvancedEventPayload.serializer(), event.payloadJson)
                    .reason == com.droidance.core.agent.llm.session.EpochAdvanceReason.PROCESS_RECOVERY
        }
    }

    override suspend fun unfinishedTurnScopesForRecovery(): List<TurnEventScope> = mutex.withLock {
        val starts = events.filter { it.eventKind == AgentEventKind.TURN_STARTED }
        starts.mapNotNull { start ->
            val turnId = start.turnId ?: return@mapNotNull null
            val completed = events.any { it.turnId == turnId && it.eventKind == AgentEventKind.TURN_COMPLETED }
            if (!completed) TurnEventScope(start.threadId, turnId) else null
        }
    }

    override suspend fun requireTurnRecoverySnapshot(
        scope: TurnEventScope
    ): TurnRecoverySnapshot = mutex.withLock {
        requireOpenTurn(scope)
        currentTurnRecoverySnapshotInternal(scope)
    }

    private fun currentTurnRecoverySnapshotInternal(scope: TurnEventScope): TurnRecoverySnapshot {
        val activeAttempts = attemptsForTurnInternal(scope.threadId, scope.turnId).filter {
            it.state !in ExecutionAttemptTransitions.terminalStates
        }
        val turnEvents = events.filter { it.turnId == scope.turnId }
        val startedTools = turnEvents.filter { it.eventKind == AgentEventKind.TOOL_STARTED }
        val pendingTools = startedTools.mapNotNull { started ->
            val callId = started.callId ?: return@mapNotNull null
            val finished = turnEvents.any { it.callId == callId && it.eventKind == AgentEventKind.TOOL_FINISHED }
            if (!finished) {
                StartedToolRecoverySnapshot(
                    scope = ToolLifecycleEventScope(
                        threadId = scope.threadId,
                        turnId = scope.turnId,
                        roundId = checkNotNull(started.roundId),
                        callId = callId,
                        attemptId = started.attemptId,
                        executionId = started.executionId
                    ),
                    roundDetailRef = "round:${started.roundId}",
                    startedSequence = started.sequence
                )
            } else null
        }
        return TurnRecoverySnapshot(scope, activeAttempts, pendingTools)
    }

    override suspend fun terminateInterruptedTurn(
        scope: TurnEventScope,
        recoveryId: String,
        notice: String
    ): AgentEventEnvelope = mutex.withLock {
        require(notice.isNotBlank())
        events.firstOrNull { it.turnId == scope.turnId && it.eventKind == AgentEventKind.TURN_COMPLETED }?.let {
            return it
        }
        requireOpenTurn(scope)
        recoverInterruptedTurnInternal(scope, recoveryId)
        currentTurnRecoverySnapshotInternal(scope).activeAttempts.forEach { attempt ->
            val payload = ExecutionAttemptStateChangedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                state = ExecutionAttemptState.INTERRUPTED,
                diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
            )
            appendDraftInternal(
                AgentEventDraft(
                    eventKind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
                    threadId = attempt.scope.threadId,
                    turnId = attempt.scope.turnId,
                    attemptId = attempt.scope.attemptId,
                    executionId = attempt.scope.executionId,
                    payloadJson = Json.encodeToString(payload),
                    idempotencyKey = "attempt:${attempt.scope.attemptId}:state:process:${scope.turnId}:terminal"
                )
            )
        }
        val turnResult = TurnResult(
            status = "interrupted",
            systemNotice = notice,
            diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
        )
        val turnCompletedPayload = TurnCompletedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            status = turnResult.status,
            assistantReply = turnResult.assistantReply,
            systemNotice = turnResult.systemNotice,
            diagnosticCode = turnResult.diagnosticCode,
            totalDurationMillis = turnResult.totalDurationMillis,
            activeDurationMillis = turnResult.activeDurationMillis,
            cancellationOrigin = turnResult.cancellationOrigin
        )
        val envelope = appendDraftInternal(
            AgentEventDraft(
                eventKind = AgentEventKind.TURN_COMPLETED,
                threadId = scope.threadId,
                turnId = scope.turnId,
                payloadJson = Json.encodeToString(turnCompletedPayload),
                idempotencyKey = "turn:${scope.turnId}:completed"
            )
        )
        notifyMutation()
        envelope
    }

    override suspend fun recoverInterruptedTurn(
        scope: TurnEventScope,
        recoveryId: String
    ): TurnRecoveryCommit = mutex.withLock {
        recoverInterruptedTurnInternal(scope, recoveryId)
    }

    private fun recoverInterruptedTurnInternal(
        scope: TurnEventScope,
        recoveryId: String
    ): TurnRecoveryCommit {
        require(recoveryId.isNotBlank()) { "Turn recoveryId cannot be blank" }
        requireOpenTurn(scope)
        val recovery = currentTurnRecoverySnapshotInternal(scope)
        val turnRecoveryPayload = TurnRecoveryRequiredEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            recoveryId = recoveryId,
            diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
        )
        val turnRecoveryDraft = AgentEventDraft(
            eventKind = AgentEventKind.TURN_RECOVERY_REQUIRED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(turnRecoveryPayload),
            idempotencyKey = "turn:${scope.turnId}:recovery:$recoveryId"
        )
        val turnRecoveryEvent = appendDraftInternal(turnRecoveryDraft)
        val attemptStateEvents = recovery.activeAttempts.mapNotNull { attempt ->
            when (attempt.state) {
                ExecutionAttemptState.CREATED,
                ExecutionAttemptState.RUNNING,
                ExecutionAttemptState.PAUSED_USER -> {
                    val payload = ExecutionAttemptStateChangedEventPayload(
                        contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                        state = ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED,
                        diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
                    )
                    appendDraftInternal(
                        AgentEventDraft(
                            eventKind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
                            threadId = attempt.scope.threadId,
                            turnId = attempt.scope.turnId,
                            attemptId = attempt.scope.attemptId,
                            executionId = attempt.scope.executionId,
                            payloadJson = Json.encodeToString(payload),
                            idempotencyKey = "attempt:${attempt.scope.attemptId}:state:recovery:$recoveryId"
                        )
                    )
                }
                else -> null
            }
        }
        val toolFinishEvents = recovery.pendingTools.map { pending ->
            val payload = ToolFinishedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                result = ToolExecutionResult(
                    outputText = "",
                    executionStatus = ToolExecutionStatus.INTERRUPTED,
                    evidenceStatus = ToolEvidenceStatus.UNKNOWN,
                    diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
                ),
                roundDetailRef = pending.roundDetailRef,
                exclusiveDurationMillis = null
            )
            appendDraftInternal(
                AgentEventDraft(
                    eventKind = AgentEventKind.TOOL_FINISHED,
                    threadId = pending.scope.threadId,
                    turnId = pending.scope.turnId,
                    roundId = pending.scope.roundId,
                    attemptId = pending.scope.attemptId,
                    executionId = pending.scope.executionId,
                    callId = pending.scope.callId,
                    payloadJson = Json.encodeToString(payload),
                    idempotencyKey = "call:${pending.scope.callId}:finished"
                )
            )
        }
        notifyMutation()
        return TurnRecoveryCommit(turnRecoveryEvent, attemptStateEvents, toolFinishEvents)
    }

    override suspend fun ensureExecutionAttemptRunning(
        scope: ExecutionAttemptEventScope,
        transitionId: String,
        expectedLifecycleSequence: Long
    ): DurableExecutionAttemptSnapshot = mutex.withLock {
        requireOpenTurn(scope.threadId, scope.turnId)
        val current = attemptsForTurnInternal(scope.threadId, scope.turnId).firstOrNull {
            it.scope.attemptId == scope.attemptId
        } ?: throw AgentEventConflictException("ExecutionAttempt ${scope.attemptId} does not exist")
        if (current.state == ExecutionAttemptState.RUNNING) {
            return current
        }
        if (current.state == ExecutionAttemptState.CREATED) {
            val payload = ExecutionAttemptStateChangedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                state = ExecutionAttemptState.RUNNING,
                diagnosticCode = null
            )
            appendDraftInternal(
                AgentEventDraft(
                    eventKind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
                    threadId = scope.threadId,
                    turnId = scope.turnId,
                    attemptId = scope.attemptId,
                    executionId = scope.executionId,
                    payloadJson = Json.encodeToString(payload),
                    idempotencyKey = "attempt:${scope.attemptId}:state:$transitionId"
                )
            )
            notifyMutation()
            return attemptsForTurnInternal(scope.threadId, scope.turnId).first { it.scope.attemptId == scope.attemptId }
        }
        throw AgentEventConflictException("ExecutionAttempt ${scope.attemptId} is not executable from ${current.state}")
    }

    override suspend fun finishTool(
        scope: ToolLifecycleEventScope,
        result: ToolExecutionResult,
        roundDetailRef: String,
        exclusiveDurationMillis: Long?
    ): AgentEventEnvelope = finishToolWithAttachments(
        scope = scope,
        result = result,
        roundDetailRef = roundDetailRef,
        exclusiveDurationMillis = exclusiveDurationMillis,
        attachments = emptyList()
    ).finishEvent

    override suspend fun finishToolWithAttachments(
        scope: ToolLifecycleEventScope,
        result: ToolExecutionResult,
        roundDetailRef: String,
        exclusiveDurationMillis: Long?,
        attachments: List<DetailAttachmentAddedEventPayload>
    ): ToolSettlementCommit = mutex.withLock {
        requireOpenTurn(scope.threadId, scope.turnId)
        val payload = ToolFinishedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            result = result,
            roundDetailRef = roundDetailRef,
            exclusiveDurationMillis = exclusiveDurationMillis
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.TOOL_FINISHED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            roundId = scope.roundId,
            attemptId = scope.attemptId,
            executionId = scope.executionId,
            callId = scope.callId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "call:${scope.callId}:finished"
        )
        val finishEvent = appendDraftInternal(draft)
        val attachmentEvents = attachments.map { attachment ->
            appendDraftInternal(
                AgentEventDraft(
                    eventKind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
                    threadId = scope.threadId,
                    turnId = scope.turnId,
                    roundId = scope.roundId,
                    attemptId = scope.attemptId,
                    executionId = scope.executionId,
                    callId = scope.callId,
                    payloadJson = Json.encodeToString(attachment),
                    idempotencyKey = "attachment:${attachment.attachmentId}"
                )
            )
        }
        notifyMutation()
        ToolSettlementCommit(finishEvent, attachmentEvents)
    }

    override suspend fun addDetailAttachment(
        scope: ToolLifecycleEventScope,
        attachment: DetailAttachmentAddedEventPayload
    ): AgentEventEnvelope = mutex.withLock {
        requireActiveThread(scope.threadId)
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            roundId = scope.roundId,
            attemptId = scope.attemptId,
            executionId = scope.executionId,
            callId = scope.callId,
            payloadJson = Json.encodeToString(attachment),
            idempotencyKey = "attachment:${attachment.attachmentId}"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun completeTurn(
        threadId: String,
        turnId: String,
        result: TurnResult
    ): AgentEventEnvelope = mutex.withLock {
        requireOpenTurn(threadId, turnId)
        TurnSettlementContract.requireSettledTools(turnId, events)
        attemptsForTurnInternal(threadId, turnId).firstOrNull {
            it.state !in ExecutionAttemptTransitions.terminalStates
        }?.let {
            throw AgentEventConflictException("Turn $turnId cannot complete with active ExecutionAttempt ${it.scope.attemptId}")
        }
        val payload = TurnCompletedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            status = result.status,
            assistantReply = result.assistantReply,
            systemNotice = result.systemNotice,
            diagnosticCode = result.diagnosticCode,
            totalDurationMillis = result.totalDurationMillis,
            activeDurationMillis = result.activeDurationMillis,
            cancellationOrigin = result.cancellationOrigin
        )
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.TURN_COMPLETED,
            threadId = threadId,
            turnId = turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "turn:$turnId:completed"
        )
        val envelope = appendDraftInternal(draft)
        notifyMutation()
        envelope
    }

    override suspend fun eventsForThread(threadId: String): List<AgentEventEnvelope> =
        mutex.withLock { events.filter { it.threadId == threadId } }

    override suspend fun eventsForLearningSession(sessionId: String): List<AgentEventEnvelope> =
        eventsForThread(learningThreadId(sessionId))

    override suspend fun eventsForTurn(turnId: String): List<AgentEventEnvelope> =
        mutex.withLock { events.filter { it.turnId == turnId } }

    override suspend fun exportSnapshot(): List<AgentEventEnvelope> = mutex.withLock {
        val deleted = events.filter { it.eventKind == AgentEventKind.THREAD_TOMBSTONED }.map { it.threadId }.toSet()
        events.filter { it.threadId !in deleted }
    }

    override suspend fun submittedItemRangeForTurn(turnId: String): SubmittedItemSequenceRange = mutex.withLock {
        val turnEvents = events.filter { it.turnId == turnId }
        require(turnEvents.isNotEmpty()) { "Turn $turnId has no durable submitted items" }
        SubmittedItemSequenceRange(turnEvents.first().sequence, turnEvents.last().sequence)
    }

    override suspend fun threadReadModel(threadId: String): AgentThreadReadModel? = mutex.withLock {
        val threadEvents = events.filter { it.threadId == threadId }
        if (threadEvents.isEmpty()) null
        else AgentEventReadModelProjector.project(threadEvents)
    }

    override suspend fun latestConversationThreadId(): String? = mutex.withLock {
        val byThread = events.groupBy { it.threadId }
        val conversationKinds = setOf(AgentEventKind.USER_MESSAGE, AgentEventKind.USER_STEER,
            AgentEventKind.ASSISTANT_MESSAGE, AgentEventKind.SYSTEM_NOTICE, AgentEventKind.TURN_COMPLETED)
        byThread.values.filter { threadEvents ->
            threadEvents.any { it.eventKind == AgentEventKind.THREAD_CREATED } &&
                threadEvents.none { it.eventKind == AgentEventKind.THREAD_TOMBSTONED }
        }.sortedByDescending { threadEvents ->
            threadEvents.lastOrNull { it.eventKind in conversationKinds }?.sequence ?: -1L
        }.firstNotNullOfOrNull { threadEvents ->
            val result = AgentThreadProjectionResult.fromEvents(threadEvents)
            (result as? AgentThreadProjectionResult.Projected)?.thread
                ?.takeIf { it.conversation.entries.isNotEmpty() }?.metadata?.threadId
        }
    }

    override suspend fun activeThreadReadModels(): List<AgentThreadReadModel> = mutex.withLock {
        val threadIds = events.map { it.threadId }.distinct()
        threadIds.mapNotNull { threadId ->
            val threadEvents = events.filter { it.threadId == threadId }
            when (val result = AgentThreadProjectionResult.fromEvents(threadEvents)) {
                is AgentThreadProjectionResult.Projected -> result.thread
                else -> null
            }
        }.filterNot { it.metadata.tombstoned }
            .sortedWith(
                compareByDescending<AgentThreadReadModel> { it.metadata.pinned }
                    .thenByDescending { it.turns.lastOrNull()?.startedAt ?: it.metadata.createdAt }
            )
    }

    override fun observeThreadReadModel(threadId: String): Flow<AgentThreadReadModel?> = flow {
        emit(threadReadModel(threadId))
        mutationNotifier.collect {
            emit(threadReadModel(threadId))
        }
    }.distinctUntilChanged()

    override fun observeThreadProjectionResult(threadId: String): Flow<AgentThreadProjectionResult> = flow {
        emit(AgentThreadProjectionResult.fromEvents(eventsForThread(threadId)))
        mutationNotifier.collect {
            emit(AgentThreadProjectionResult.fromEvents(eventsForThread(threadId)))
        }
    }.distinctUntilChanged()

    override fun observeActiveThreadReadModels(): Flow<List<AgentThreadReadModel>> = flow {
        emit(activeThreadReadModels())
        mutationNotifier.collect {
            emit(activeThreadReadModels())
        }
    }.distinctUntilChanged()

    override suspend fun count(): Int = mutex.withLock { events.size }
}
