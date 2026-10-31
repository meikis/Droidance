// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.persistence.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.persistence.agent.event.*

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.droidance.core.agent.attempt.CapabilitySnapshot
import com.droidance.core.agent.attempt.ExecutionAttempt
import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionAttemptTransitions
import com.droidance.core.agent.attempt.PreparedAttemptBinding
import com.droidance.core.agent.context.ContextCheckpointRecord
import com.droidance.core.agent.context.ContextCheckpointPayload
import com.droidance.core.agent.context.ContextCheckpointValidator
import com.droidance.core.agent.llm.session.ContextCheckpointApplied
import com.droidance.core.agent.llm.session.FrozenTurnModelSession
import com.droidance.core.agent.llm.session.ModelSessionEpochPolicy
import com.droidance.core.agent.llm.session.ModelSessionEpochTransition
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import com.droidance.core.agent.llm.session.ModelSessionKey
import com.droidance.core.agent.llm.session.ProviderHandleInvalidated
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RoomAgentEventStore(
    private val database: AgentEventDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val beforeInsert: (index: Int, draft: AgentEventDraft) -> Unit = { _, _ -> }
) : AgentEventStore {
    private val dao = database.agentEventDao()

    override suspend fun append(event: AgentEventDraft): AgentEventEnvelope =
        appendAtomically(listOf(event)).single()

    override suspend fun appendAtomically(events: List<AgentEventDraft>): List<AgentEventEnvelope> {
        require(events.isNotEmpty()) { "An event batch cannot be empty" }
        require(events.none { it.eventKind in coordinatedEventKinds }) {
            "Coordinated lifecycle events require their typed store API"
        }
        events.forEach(AgentEventSchema::validate)
        return database.withTransaction {
            val sequences = SequenceCursor(dao.maxSequence() + 1L)
            events.mapIndexed { index, draft -> appendDraft(draft, index, sequences) }
        }
    }

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
        dao.eventsByKind(eventKind.name).map(AgentEventEntity::toEnvelope)

    override suspend fun createThread(
        threadId: String,
        initialTitle: String
    ): AgentEventEnvelope {
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
        return appendChecked(draft) {
            if (dao.eventsForThread(threadId).isNotEmpty()) {
                throw AgentEventConflictException("Thread $threadId already owns durable facts")
            }
        }
    }

    override suspend fun changeThreadMetadata(
        threadId: String,
        changeId: String,
        title: String,
        summary: String?
    ): AgentEventEnvelope {
        require(changeId.isNotBlank()) { "Thread metadata changeId cannot be blank" }
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
        return appendChecked(draft) { requireActiveThread(threadId) }
    }

    override suspend fun changeThreadPin(
        threadId: String,
        changeId: String,
        isPinned: Boolean
    ): AgentEventEnvelope {
        require(changeId.isNotBlank()) { "Thread pin changeId cannot be blank" }
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
        return appendChecked(draft) { requireActiveThread(threadId) }
    }

    override suspend fun tombstoneThread(threadId: String): AgentEventEnvelope {
        val payload = ThreadTombstonedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION)
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.THREAD_TOMBSTONED,
            threadId = threadId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "thread:$threadId:tombstoned"
        )
        return appendChecked(draft) {
            requireActiveThread(threadId)
            val openTurn = dao.eventsForThread(threadId)
                .filter { it.eventKind == AgentEventKind.TURN_STARTED.name }
                .firstOrNull { start -> dao.findTurnCompletion(checkNotNull(start.turnId)) == null }
            if (openTurn != null) {
                throw AgentEventConflictException(
                    "Thread $threadId cannot be tombstoned with an open Turn"
                )
            }
        }
    }

    override suspend fun tombstoneAllThreads(): List<AgentEventEnvelope> = database.withTransaction {
        val threadIds = dao.createdThreadIds().filter { threadId ->
            dao.eventsForThread(threadId)
                .none { it.eventKind == AgentEventKind.THREAD_TOMBSTONED.name }
        }
        threadIds.forEach { threadId ->
            requireActiveThread(threadId)
            val openTurn = dao.eventsForThread(threadId)
                .filter { it.eventKind == AgentEventKind.TURN_STARTED.name }
                .firstOrNull { start -> dao.findTurnCompletion(checkNotNull(start.turnId)) == null }
            if (openTurn != null) {
                throw AgentEventConflictException(
                    "Thread $threadId cannot be tombstoned with an open Turn"
                )
            }
        }
        val sequences = SequenceCursor(dao.maxSequence() + 1L)
        threadIds.mapIndexed { index, threadId ->
            val payload = ThreadTombstonedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION)
            appendDraft(
                AgentEventDraft(
                    eventKind = AgentEventKind.THREAD_TOMBSTONED,
                    threadId = threadId,
                    payloadJson = Json.encodeToString(payload),
                    idempotencyKey = "thread:$threadId:tombstoned"
                ),
                index,
                sequences
            )
        }
    }

    override suspend fun purgeThreads(threadIds: Set<String>): Int = database.withTransaction {
        threadIds.forEach { threadId ->
            val threadEvents = dao.eventsForThread(threadId)
            val completedTurns = threadEvents.filter { it.eventKind == AgentEventKind.TURN_COMPLETED.name }
                .mapTo(mutableSetOf()) { it.turnId }
            if (threadEvents.any { it.eventKind == AgentEventKind.TURN_STARTED.name && it.turnId !in completedTurns }) {
                throw AgentEventConflictException("Thread $threadId cannot be purged with an open Turn")
            }
        }
        threadIds.sumOf { dao.deleteEventsForThread(it) }
    }

    override suspend fun allReferencedBlobRefs(): Set<String> {
        val payloads = dao.allPayloads()
        val regex = Regex("sha256:[0-9a-f]{64}")
        val refs = mutableSetOf<String>()
        for (payload in payloads) {
            regex.findAll(payload).forEach { match ->
                refs.add(match.value)
            }
        }
        return refs
    }

    override suspend fun startTurn(
        scope: TurnEventScope,
        modelSessionIdentity: ModelSessionIdentity,
        providerConfigSnapshotRef: String
    ): AgentEventEnvelope {
        require(modelSessionIdentity.threadId == scope.threadId) {
            "Turn ModelSessionIdentity must belong to its Thread"
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
        return appendChecked(draft) {
            requireActiveThread(scope.threadId)
            if (dao.eventsForTurn(scope.turnId).isNotEmpty()) {
                throw AgentEventConflictException("Turn ${scope.turnId} already owns durable facts")
            }
        }
    }

    override suspend fun recordUserMessage(
        scope: TurnEventScope,
        messageId: String,
        text: String,
        attachments: List<UserAttachmentPayload>
    ): AgentEventEnvelope = recordUserText(
        scope = scope,
        messageId = messageId,
        text = text,
        steer = false,
        attachments = attachments
    )

    override suspend fun activateModelSession(
        scope: TurnEventScope,
        modelSessionIdentity: ModelSessionIdentity,
        providerConfigSnapshotRef: String,
        previousEpoch: Long,
        currentEpoch: Long,
        activationKind: TurnActivationKind
    ): AgentEventEnvelope {
        require(activationKind != TurnActivationKind.DIRECT)
        require(modelSessionIdentity.threadId == scope.threadId) {
            "Activated ModelSessionIdentity must belong to its Thread"
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
        return appendChecked(draft) {
            requireOpenTurn(scope.threadId, scope.turnId)
            require(dao.eventsForTurn(scope.turnId).none {
                it.eventKind in setOf(AgentEventKind.MODEL_SESSION_ACTIVATED.name, AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED.name)
            }) { "A Turn can activate its Android model session only once" }
            require(dao.eventsForTurn(scope.turnId).none {
                it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED.name
            }) { "Model session activation must precede device execution" }
        }
    }

    override suspend fun recordUserSteer(
        scope: TurnEventScope,
        messageId: String,
        text: String,
        attachments: List<UserAttachmentPayload>,
        activeDurationMillis: Long?
    ): AgentEventEnvelope = recordUserText(
        scope = scope,
        messageId = messageId,
        text = text,
        steer = true,
        attachments = attachments,
        activeDurationMillis = activeDurationMillis
    )

    override suspend fun recordMemoryContextLoaded(
        scope: TurnEventScope,
        payload: MemoryContextLoadedEventPayload,
        inputSequence: Long?
    ): AgentEventEnvelope {
        val inputs = dao.eventsForTurn(scope.turnId).filter {
            it.eventKind in setOf(AgentEventKind.USER_MESSAGE.name, AgentEventKind.USER_STEER.name) }
        val source = inputSequence ?: inputs.firstOrNull()?.sequence
        require(source != null && inputs.lastOrNull()?.sequence == source) { "Memory context requires the current user input" }
        val draft = AgentEventDraft(
            eventKind = AgentEventKind.MEMORY_CONTEXT_LOADED,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = "turn:${scope.turnId}:input:$source:memory-context" + payload.contextKey?.let { ":$it" }.orEmpty()
        )
        return appendChecked(draft) {
            requireOpenTurn(scope.threadId, scope.turnId)
            require(dao.eventsForTurn(scope.turnId).any {
                it.eventKind == AgentEventKind.USER_MESSAGE.name
            }) { "Memory context requires the starting UserMessage" }
            require(dao.eventsForTurn(scope.turnId).lastOrNull {
                it.eventKind in setOf(AgentEventKind.USER_MESSAGE.name, AgentEventKind.USER_STEER.name)
            }?.sequence == source) { "Memory context input changed during retrieval" }
        }
    }

    override suspend fun recordSystemNotice(
        scope: TurnEventScope,
        noticeId: String,
        text: String,
        diagnosticCode: String?
    ): AgentEventEnvelope {
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
        return appendChecked(draft) { requireOpenTurn(scope.threadId, scope.turnId) }
    }

    /**
     * Persists the effective checkpoint and its mandatory epoch boundary as one fact.
     * The generated epoch payload references the durable checkpoint event id, so a
     * failed second insert cannot leave local compaction active on the old Provider chain.
     */
    override suspend fun recordContextCheckpoint(
        threadId: String,
        turnId: String,
        checkpointPayload: ContextCheckpointPayload,
        frozenSession: FrozenTurnModelSession
    ): ContextCheckpointCommit {
        require(frozenSession.turnId == turnId) {
            "Context checkpoint turn must match the frozen ModelSession"
        }
        val currentSession = frozenSession.key
        require(currentSession.identity.threadId == threadId) {
            "Context checkpoint thread must match the frozen ModelSession identity"
        }
        ContextCheckpointValidator.validate(checkpointPayload)
        require(checkpointPayload.stablePrefixHash == currentSession.identity.stablePrefixHash) {
            "Context checkpoint stable prefix must match the frozen ModelSession identity"
        }
        val nextEpoch = Math.addExact(currentSession.epoch, 1L)
        val identityHash = modelSessionIdentityHash(currentSession.identity)
        val checkpointDraft = AgentEventDraft(
            eventKind = AgentEventKind.CONTEXT_CHECKPOINT,
            threadId = threadId,
            turnId = turnId,
            payloadJson = Json.encodeToString(checkpointPayload),
            idempotencyKey = "model-session:$turnId:$identityHash:checkpoint:$nextEpoch"
        )
        AgentEventSchema.validate(checkpointDraft)

        return database.withTransaction {
            requireCheckpointTurnSession(threadId, turnId, frozenSession)
            val sequences = SequenceCursor(dao.maxSequence() + 1L)
            val epochIdempotencyKey = "model-session:$turnId:$identityHash:epoch:$nextEpoch"
            val existingCheckpoint = dao.findByIdempotencyKey(checkpointDraft.idempotencyKey)
            if (existingCheckpoint != null) {
                verifyIdempotentRetry(
                    existing = existingCheckpoint,
                    draft = checkpointDraft,
                    payloadHash = sha256(canonicalizeJson(checkpointDraft.payloadJson))
                )
                val existingEpoch = dao.findByIdempotencyKey(epochIdempotencyKey)
                    ?: throw AgentEventConflictException(
                        "Existing context checkpoint has no matching model session epoch"
                    )
                val transition = ModelSessionEpochPolicy.advance(
                    current = currentSession,
                    trigger = ContextCheckpointApplied(existingCheckpoint.eventId)
                )
                verifyDurableCheckpointEpoch(existingEpoch, transition)
                return@withTransaction ContextCheckpointCommit(
                    checkpointEvent = existingCheckpoint.toEnvelope(),
                    epochEvent = existingEpoch.toEnvelope(),
                    epochTransition = transition
                )
            }

            val previousCheckpoint = latestContextCheckpoint(turnId)
            requireCheckpointParent(checkpointPayload, previousCheckpoint)
            val existingEpoch = dao.findByIdempotencyKey(epochIdempotencyKey)
            if (existingEpoch == null) {
                requireCurrentDurableEpoch(turnId, currentSession)
            } else {
                throw AgentEventConflictException(
                    "Existing model session epoch has no matching context checkpoint"
                )
            }
            val checkpoint = appendDraft(checkpointDraft, index = 0, sequences)
            val durableTransition = ModelSessionEpochPolicy.advance(
                current = currentSession,
                trigger = ContextCheckpointApplied(checkpoint.eventId)
            )
            val epochDraft = modelSessionEpochDraft(
                threadId = threadId,
                turnId = turnId,
                transition = durableTransition,
                idempotencyKey = epochIdempotencyKey
            )
            AgentEventSchema.validate(epochDraft)
            val epoch = appendDraft(epochDraft, index = 1, sequences)
            ContextCheckpointCommit(checkpoint, epoch, durableTransition)
        }
    }

    /** Returns the latest durable checkpoint for a Turn without exposing raw event decoding. */
    override suspend fun latestContextCheckpointForTurn(turnId: String): ContextCheckpointRecord? =
        latestContextCheckpoint(turnId)

    override suspend fun recordProviderHandleInvalidation(
        threadId: String,
        turnId: String,
        frozenSession: FrozenTurnModelSession,
        trigger: ProviderHandleInvalidated
    ): ModelSessionEpochCommit {
        require(frozenSession.turnId == turnId) {
            "Model session epoch turn must match the frozen ModelSession"
        }
        val currentSession = frozenSession.key
        require(currentSession.identity.threadId == threadId) {
            "Model session epoch thread must match the frozen identity"
        }
        val transition = ModelSessionEpochPolicy.advance(currentSession, trigger)
        val identityHash = modelSessionIdentityHash(currentSession.identity)
        val epochIdempotencyKey =
            "model-session:$turnId:$identityHash:epoch:${transition.current.epoch}"
        val epochDraft = modelSessionEpochDraft(
            threadId = threadId,
            turnId = turnId,
            transition = transition,
            diagnosticCode = trigger.diagnosticCode,
            idempotencyKey = epochIdempotencyKey
        )
        AgentEventSchema.validate(epochDraft)
        return database.withTransaction {
            val sequences = SequenceCursor(dao.maxSequence() + 1L)
            requireCheckpointTurnSession(threadId, turnId, frozenSession)
            if (dao.findByIdempotencyKey(epochIdempotencyKey) == null) {
                requireCurrentDurableEpoch(turnId, currentSession)
            }
            ModelSessionEpochCommit(
                epochEvent = appendDraft(epochDraft, index = 0, sequences),
                epochTransition = transition
            )
        }
    }

    /**
     * Persists the request boundary before a Provider call is allowed to start.
     * The durable START fact is replayed from EventStore instead of trusting a
     * process-local Boolean supplied by the caller.
     */
    override suspend fun recordModelRoundStarted(
        start: PreparedModelRoundStart
    ): AgentEventEnvelope {
        AgentEventSchema.validate(start.draft)
        return database.withTransaction {
            dao.findByIdempotencyKey(start.draft.idempotencyKey)?.let { existing ->
                verifyIdempotentRetry(
                    existing = existing,
                    draft = start.draft,
                    payloadHash = sha256(canonicalizeJson(start.draft.payloadJson))
                )
                return@withTransaction existing.toEnvelope()
            }
            requireOpenTurn(start.scope)
            if (dao.findRoundStart(start.scope.roundId) != null) {
                throw AgentEventConflictException(
                    "ModelRound ${start.scope.roundId} already has a request boundary"
                )
            }
            val nextSequence = dao.maxSequence() + 1L
            requireSubmittedItemRange(
                scope = start.scope,
                range = start.submittedItems,
                beforeSequence = nextSequence
            )
            val durableStartCommitted = durableStartCommitted(start.scope.turnId)
            if (durableStartCommitted != start.startCommitted) {
                throw AgentEventConflictException(
                    "ModelRound startCommitted is stale for Turn ${start.scope.turnId}"
                )
            }
            appendDraft(start.draft, index = 0, SequenceCursor(nextSequence))
        }
    }

    /**
     * Commits the complete response plan before any handler, backend or repair
     * request may run. A retry is accepted only when the whole original batch
     * is already present byte-for-byte; partial historical batches are never
     * completed one event at a time.
     */
    override suspend fun recordModelRoundPlan(
        plan: PreparedModelRoundCommit
    ): ModelRoundPlanRecordResult {
        plan.drafts.forEach(AgentEventSchema::validate)
        return database.withTransaction {
            val existingByDraft = plan.drafts.map { draft ->
                dao.findByIdempotencyKey(draft.idempotencyKey)
            }
            if (existingByDraft.all { it != null }) {
                val existing = existingByDraft.mapIndexed { index, entity ->
                    checkNotNull(entity).also {
                        val draft = plan.drafts[index]
                        verifyIdempotentRetry(
                            existing = it,
                            draft = draft,
                            payloadHash = sha256(canonicalizeJson(draft.payloadJson))
                        )
                    }
                }
                requireContiguousPlanBatch(existing)
                return@withTransaction AlreadyCommittedModelRoundPlan(
                    RecordedModelRoundPlan.from(
                        plan = plan,
                        events = existing.map(AgentEventEntity::toEnvelope)
                    )
                )
            }
            if (existingByDraft.any { it != null }) {
                throw AgentEventConflictException(
                    "ModelRound ${plan.scope.roundId} has a partial or conflicting plan batch"
                )
            }

            requireOpenTurn(plan.scope)
            val startEntity = dao.findRoundStart(plan.scope.roundId)
                ?: throw AgentEventConflictException(
                    "ModelRound ${plan.scope.roundId} has no durable request boundary"
                )
            if (startEntity.threadId != plan.scope.threadId ||
                startEntity.turnId != plan.scope.turnId ||
                startEntity.roundId != plan.scope.roundId
            ) {
                throw AgentEventConflictException(
                    "ModelRound request boundary belongs to a different scope"
                )
            }
            val startPayload = decodeRoundStart(startEntity)
            if (startPayload.contractVersion != MODEL_ROUND_EVENT_CONTRACT_VERSION ||
                startPayload.repairPurpose != plan.repairPurpose ||
                startPayload.startCommitted != plan.expectedStartCommitted ||
                startPayload.submittedItems != plan.submittedItems ||
                startPayload.roundDetailRef != plan.roundDetailRef
            ) {
                throw AgentEventConflictException(
                    "ModelRound plan does not match its durable request boundary"
                )
            }
            requireSubmittedItemRange(
                scope = plan.scope,
                range = plan.submittedItems,
                beforeSequence = startEntity.sequence
            )
            if (durableStartCommitted(plan.scope.turnId) != plan.expectedStartCommitted) {
                throw AgentEventConflictException(
                    "ModelRound plan classification is stale for Turn ${plan.scope.turnId}"
                )
            }
            if (dao.findRoundCompletion(plan.scope.roundId) != null ||
                dao.eventsForRound(plan.scope.roundId).any {
                    AgentEventKind.valueOf(it.eventKind) in modelRoundPlanOwnedKinds
                }
            ) {
                throw AgentEventConflictException(
                    "ModelRound ${plan.scope.roundId} already has non-idempotent plan facts"
                )
            }
            plan.preparedExecution.roundPlan.allCallIds.forEach { callId ->
                if (dao.eventsForCall(callId).isNotEmpty()) {
                    throw AgentEventConflictException(
                        "Harness callId $callId is already owned by another lifecycle"
                    )
                }
            }
            when (val preparedAttempt = plan.preparedExecution.attemptBinding) {
                is PreparedAttemptBinding.New -> {
                    val attempt = checkNotNull(plan.preparedExecution.attempt)
                    if (activeAttemptsForTurn(plan.scope.threadId, plan.scope.turnId).isNotEmpty()) {
                        throw AgentEventConflictException(
                            "Turn ${plan.scope.turnId} already has an active ExecutionAttempt"
                        )
                    }
                    if (dao.findAttemptCreation(attempt.attemptId) != null ||
                        dao.findExecutionCreation(attempt.context.executionId) != null
                    ) {
                        throw AgentEventConflictException(
                            "ExecutionAttempt ${attempt.attemptId} reuses durable identity"
                        )
                    }
                }
                is PreparedAttemptBinding.Existing -> {
                    val attempt = checkNotNull(plan.preparedExecution.attempt)
                    val durable = requireDurableAttempt(
                        ExecutionAttemptEventScope(
                            threadId = plan.scope.threadId,
                            turnId = plan.scope.turnId,
                            attemptId = attempt.attemptId,
                            executionId = attempt.context.executionId
                        )
                    )
                    if (durable.state != ExecutionAttemptState.RUNNING) {
                        throw AgentEventConflictException(
                            "ExecutionAttempt ${attempt.attemptId} is not RUNNING"
                        )
                    }
                    val activeAttempts = activeAttemptsForTurn(
                        plan.scope.threadId,
                        plan.scope.turnId
                    )
                    val latestAttempt = attemptsForTurn(
                        plan.scope.threadId,
                        plan.scope.turnId
                    ).lastOrNull()
                    if (activeAttempts.size != 1 ||
                        activeAttempts.single().first.attemptId != attempt.attemptId ||
                        latestAttempt?.first?.attemptId != attempt.attemptId
                    ) {
                        throw AgentEventConflictException(
                            "ExecutionAttempt ${attempt.attemptId} is not the unique active attempt"
                        )
                    }
                    requirePreparedAttemptMatchesDurable(
                        attempt = attempt,
                        capabilitySnapshot = checkNotNull(
                            plan.preparedExecution.capabilitySnapshot
                        ),
                        durable = durable
                    )
                    if (durable.lifecycleSequence != preparedAttempt.expectedLifecycleSequence) {
                        throw AgentEventConflictException(
                            "ExecutionAttempt ${attempt.attemptId} lifecycle changed after planning"
                        )
                    }
                }
                null -> check(plan.preparedExecution.attempt == null)
            }

            val sequences = SequenceCursor(dao.maxSequence() + 1L)
            val committed = plan.drafts.mapIndexed { index, draft ->
                appendDraft(draft, index, sequences)
            }
            NewlyCommittedModelRoundPlan(
                RecordedModelRoundPlan.from(plan, committed)
            )
        }
    }

    override suspend fun recordExecutionAttemptState(
        scope: ExecutionAttemptEventScope,
        transitionId: String,
        state: ExecutionAttemptState,
        diagnosticCode: String?
    ): AgentEventEnvelope {
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
        return appendChecked(draft) {
            requireOpenTurn(scope.threadId, scope.turnId)
            val previous = requireDurableAttempt(scope).state
            if (!ExecutionAttemptTransitions.allows(previous, state)) {
                throw AgentEventConflictException(
                    "ExecutionAttempt ${scope.attemptId} cannot transition $previous -> $state"
                )
            }
        }
    }

    override suspend fun requireExecutionAttemptSnapshot(
        scope: ExecutionAttemptEventScope
    ): DurableExecutionAttemptSnapshot = database.withTransaction {
        requireDurableAttempt(scope).toSnapshot(scope)
    }

    override suspend fun latestExecutionAttemptForTurn(
        scope: TurnEventScope
    ): DurableExecutionAttemptSnapshot? = database.withTransaction {
        requireTurn(scope.threadId, scope.turnId)
        val attempts = attemptsForTurn(scope.threadId, scope.turnId)
        val active = attempts.filter { (_, attempt) ->
            attempt.state !in ExecutionAttemptTransitions.terminalStates
        }
        if (active.size > 1) {
            throw AgentEventConflictException(
                "Turn ${scope.turnId} has more than one active ExecutionAttempt"
            )
        }
        val latest = attempts.lastOrNull() ?: return@withTransaction null
        if (active.singleOrNull()?.first?.sequence?.let { it != latest.first.sequence } == true) {
            throw AgentEventConflictException(
                "Turn ${scope.turnId} active ExecutionAttempt is not its latest attempt"
            )
        }
        latest.second.toSnapshot(latest.first.toAttemptScope())
    }

    override suspend fun canReplaceRecoveredAttempt(previous: DurableExecutionAttemptSnapshot): Boolean {
        if (previous.state != ExecutionAttemptState.INTERRUPTED) return false
        val latest = latestExecutionAttemptForTurn(TurnEventScope(previous.scope.threadId, previous.scope.turnId))
        if (latest?.scope != previous.scope || latest.lifecycleSequence != previous.lifecycleSequence) return false
        val turn = threadReadModel(previous.scope.threadId)?.turns?.singleOrNull {
            it.turnId == previous.scope.turnId
        } ?: return false
        if (turn.state != TurnReadModelState.RUNNING) return false
        return eventsForTurn(previous.scope.turnId).any { event ->
            event.sequence > previous.lifecycleSequence &&
                event.eventKind == AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED &&
                Json.decodeFromString(ModelSessionEpochAdvancedEventPayload.serializer(), event.payloadJson)
                    .reason == com.droidance.core.agent.llm.session.EpochAdvanceReason.PROCESS_RECOVERY
        }
    }

    override suspend fun unfinishedTurnScopesForRecovery(): List<TurnEventScope> =
        database.withTransaction {
            dao.unfinishedTurnStarts().map { start ->
                val turnId = start.turnId ?: throw AgentEventConflictException(
                    "Unfinished Turn start ${start.eventId} has no turnId"
                )
                requireOpenTurn(start.threadId, turnId)
                TurnEventScope(start.threadId, turnId)
            }
        }

    override suspend fun requireTurnRecoverySnapshot(
        scope: TurnEventScope
    ): TurnRecoverySnapshot = database.withTransaction {
        requireOpenTurn(scope.threadId, scope.turnId)
        currentTurnRecoverySnapshot(scope)
    }

    /** Settle interrupted work and publish its terminal result in one transaction. */
    override suspend fun terminateInterruptedTurn(
        scope: TurnEventScope, recoveryId: String, notice: String
    ): AgentEventEnvelope = database.withTransaction {
        require(notice.isNotBlank())
        dao.eventsForTurn(scope.turnId).firstOrNull {
            it.eventKind == AgentEventKind.TURN_COMPLETED.name
        }?.let { existing ->
            require(existing.threadId == scope.threadId)
            return@withTransaction existing.toEnvelope()
        }
        requireOpenTurn(scope.threadId, scope.turnId)
        recoverInterruptedTurn(scope, recoveryId)
        currentTurnRecoverySnapshot(scope).activeAttempts.forEach { attempt ->
            recordExecutionAttemptState(
                scope = attempt.scope,
                transitionId = "process:${scope.turnId}:terminal:${attempt.scope.attemptId}",
                state = ExecutionAttemptState.INTERRUPTED,
                diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
            )
        }
        completeTurn(scope.threadId, scope.turnId, TurnResult(
            status = "interrupted", systemNotice = notice,
            diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
        ))
    }

    override suspend fun recoverInterruptedTurn(
        scope: TurnEventScope,
        recoveryId: String
    ): TurnRecoveryCommit {
        require(recoveryId.isNotBlank()) { "Turn recoveryId cannot be blank" }
        return database.withTransaction {
            requireOpenTurn(scope.threadId, scope.turnId)
            val recovery = currentTurnRecoverySnapshot(scope)
            val sequences = SequenceCursor(dao.maxSequence() + 1L)
            var insertIndex = 0
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
            AgentEventSchema.validate(turnRecoveryDraft)
            val turnRecoveryEvent = appendDraft(turnRecoveryDraft, insertIndex++, sequences)
            val attemptStateEvents = buildList {
                recovery.activeAttempts.forEach { attempt ->
                    when (attempt.state) {
                        ExecutionAttemptState.CREATED,
                        ExecutionAttemptState.RUNNING,
                        ExecutionAttemptState.PAUSED_USER -> {
                            val payload = ExecutionAttemptStateChangedEventPayload(
                                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                                state = ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED,
                                diagnosticCode = AgentRecoveryDiagnosticCode.PROCESS_RECOVERY
                            )
                            val draft = AgentEventDraft(
                                eventKind = AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
                                threadId = attempt.scope.threadId,
                                turnId = attempt.scope.turnId,
                                attemptId = attempt.scope.attemptId,
                                executionId = attempt.scope.executionId,
                                payloadJson = Json.encodeToString(payload),
                                idempotencyKey =
                                    "attempt:${attempt.scope.attemptId}:state:recovery:$recoveryId"
                            )
                            AgentEventSchema.validate(draft)
                            val stateEvent = appendDraft(draft, insertIndex++, sequences)
                            val durable = requireDurableAttempt(attempt.scope)
                            if (durable.state != ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED ||
                                durable.lifecycleSequence != stateEvent.sequence
                            ) {
                                throw AgentEventConflictException(
                                    "Recovery $recoveryId does not own the current state of " +
                                        "ExecutionAttempt ${attempt.scope.attemptId}"
                                )
                            }
                            add(stateEvent)
                        }
                        ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED -> Unit
                        ExecutionAttemptState.COMPLETED,
                        ExecutionAttemptState.FAILED,
                        ExecutionAttemptState.CANCELED,
                        ExecutionAttemptState.INTERRUPTED -> throw AgentEventConflictException(
                            "Recovery snapshot contains terminal ExecutionAttempt " +
                                attempt.scope.attemptId
                        )
                    }
                }
            }
            val toolFinishEvents = buildList {
                recovery.pendingTools.forEach { pending ->
                    val call = requireDurableCall(pending.scope)
                    if (call.started == null || call.rejected != null || call.finished != null ||
                        call.payload.roundDetailRef != pending.roundDetailRef
                    ) {
                        throw AgentEventConflictException(
                            "Tool ${pending.scope.callId} changed before recovery settlement"
                        )
                    }
                    requireDurableToolAttempt(pending.scope, call.payload.binding)
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
                    val draft = AgentEventDraft(
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
                    AgentEventSchema.validate(draft)
                    add(appendDraft(draft, insertIndex++, sequences))
                }
            }
            TurnRecoveryCommit(turnRecoveryEvent, attemptStateEvents, toolFinishEvents)
        }
    }

    override suspend fun ensureExecutionAttemptRunning(
        scope: ExecutionAttemptEventScope,
        transitionId: String,
        expectedLifecycleSequence: Long
    ): DurableExecutionAttemptSnapshot {
        require(transitionId.isNotBlank()) { "ExecutionAttempt transitionId cannot be blank" }
        require(expectedLifecycleSequence > 0L) {
            "Expected ExecutionAttempt lifecycle sequence must be positive"
        }
        val payload = ExecutionAttemptStateChangedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            state = ExecutionAttemptState.RUNNING,
            diagnosticCode = null
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
        AgentEventSchema.validate(draft)
        val payloadHash = sha256(canonicalizeJson(draft.payloadJson))
        return database.withTransaction {
            requireOpenTurn(scope.threadId, scope.turnId)
            dao.findByIdempotencyKey(draft.idempotencyKey)?.let { existing ->
                verifyIdempotentRetry(existing, draft, payloadHash)
                val existingPayload = decodeEventPayload(
                    existing,
                    ExecutionAttemptStateChangedEventPayload.serializer()
                )
                val current = requireDurableAttempt(scope)
                if (existingPayload.state != ExecutionAttemptState.RUNNING ||
                    current.state != ExecutionAttemptState.RUNNING ||
                    current.lifecycleSequence != existing.sequence
                ) {
                    throw AgentEventConflictException(
                        "ExecutionAttempt ${scope.attemptId} changed after its RUNNING transition"
                    )
                }
                return@withTransaction current.toSnapshot(scope)
            }
            val durable = requireDurableAttempt(scope)
            if (durable.lifecycleSequence != expectedLifecycleSequence) {
                throw AgentEventConflictException(
                    "ExecutionAttempt ${scope.attemptId} lifecycle changed before execution"
                )
            }
            when (durable.state) {
                ExecutionAttemptState.CREATED -> {
                    val transition = appendDraft(
                        draft = draft,
                        index = 0,
                        sequences = SequenceCursor(dao.maxSequence() + 1L)
                    )
                    durable.copy(
                        state = ExecutionAttemptState.RUNNING,
                        lifecycleSequence = transition.sequence
                    ).toSnapshot(scope)
                }
                ExecutionAttemptState.RUNNING -> durable.toSnapshot(scope)
                else -> throw AgentEventConflictException(
                    "ExecutionAttempt ${scope.attemptId} is not executable from ${durable.state}"
                )
            }
        }
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
    ): ToolSettlementCommit {
        val attachmentSnapshot = attachments.toList()
        require(
            attachmentSnapshot.map(DetailAttachmentAddedEventPayload::attachmentId)
                .toSet().size == attachmentSnapshot.size
        ) {
            "A Tool settlement cannot contain duplicate attachmentIds"
        }
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
        val attachmentDrafts = attachmentSnapshot.map { attachment ->
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
        }
        (listOf(draft) + attachmentDrafts).forEach(AgentEventSchema::validate)

        return database.withTransaction {
            if (dao.findByIdempotencyKey(draft.idempotencyKey) == null) {
                requireOpenTurn(scope.threadId, scope.turnId)
                val call = requireDurableCall(scope)
                if (call.payload.roundDetailRef != roundDetailRef) {
                    throw AgentEventConflictException(
                        "Tool ${scope.callId} changed its frozen ModelRound detail reference"
                    )
                }
                if (call.started == null || call.rejected != null) {
                    throw AgentEventConflictException(
                        "Tool ${scope.callId} requires one durable ToolStarted before ToolFinished"
                    )
                }
                if (call.finished != null) {
                    throw AgentEventConflictException("Tool ${scope.callId} is already settled")
                }
                requireDurableToolAttempt(scope, call.payload.binding)
            }

            val sequences = SequenceCursor(dao.maxSequence() + 1L)
            val finishEvent = appendDraft(draft, index = 0, sequences)
            val attachmentEvents = attachmentDrafts.mapIndexed { index, attachmentDraft ->
                if (dao.findByIdempotencyKey(attachmentDraft.idempotencyKey) == null) {
                    requireTurn(scope.threadId, scope.turnId)
                    val call = requireDurableCall(scope)
                    if (call.finished == null && call.rejected == null) {
                        throw AgentEventConflictException(
                            "Detail attachment ${attachmentSnapshot[index].attachmentId} " +
                                "requires a settled ToolCall"
                        )
                    }
                    requireDurableToolAttempt(
                        scope,
                        call.payload.binding,
                        requireForStarted = call.started != null
                    )
                }
                appendDraft(attachmentDraft, index = index + 1, sequences)
            }
            ToolSettlementCommit(finishEvent, attachmentEvents)
        }
    }

    override suspend fun addDetailAttachment(
        scope: ToolLifecycleEventScope,
        attachment: DetailAttachmentAddedEventPayload
    ): AgentEventEnvelope {
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
        return appendChecked(draft) {
            requireTurn(scope.threadId, scope.turnId)
            val call = requireDurableCall(scope)
            if (call.finished == null && call.rejected == null) {
                throw AgentEventConflictException(
                    "Detail attachment ${attachment.attachmentId} requires a settled ToolCall"
                )
            }
            requireDurableToolAttempt(scope, call.payload.binding, requireForStarted = call.started != null)
        }
    }

    override suspend fun completeTurn(
        threadId: String,
        turnId: String,
        result: TurnResult
    ): AgentEventEnvelope {
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
        return appendChecked(draft) {
            requireOpenTurn(threadId, turnId)
            requireTurnLifecycleSettled(turnId)
        }
    }

    override suspend fun eventsForThread(threadId: String): List<AgentEventEnvelope> =
        dao.eventsForThread(threadId).map(AgentEventEntity::toEnvelope)

    override suspend fun eventsForLearningSession(sessionId: String): List<AgentEventEnvelope> {
        require(sessionId.isNotBlank()) { "Learning sessionId cannot be blank" }
        return eventsForThread(learningThreadId(sessionId))
    }

    override suspend fun eventsForTurn(turnId: String): List<AgentEventEnvelope> =
        dao.eventsForTurn(turnId).map(AgentEventEntity::toEnvelope)

    override suspend fun exportSnapshot(): List<AgentEventEnvelope> = database.withTransaction {
        dao.exportSnapshot().map(AgentEventEntity::toEnvelope)
    }

    /**
     * Returns the exact durable prefix that a ModelRound is allowed to acknowledge.
     *
     * The range is derived from EventStore facts rather than from the in-memory message list.
     * A turn always starts with TURN_STARTED and USER_MESSAGE/USER_STEER, so an empty or
     * discontinuous prefix is a durable corruption and must stop planning.
     */
    override suspend fun submittedItemRangeForTurn(turnId: String): SubmittedItemSequenceRange {
        val events = dao.eventsForTurn(turnId)
        require(events.isNotEmpty()) { "Turn $turnId has no durable submitted items" }
        val first = events.first().sequence
        val last = events.last().sequence
        require(first > 0L && last >= first) {
            "Turn $turnId has an invalid durable sequence range"
        }
        return SubmittedItemSequenceRange(firstSequence = first, lastSequence = last)
    }

    /**
     * The only durable thread projection. Callers never read Conversation,
     * Journal or Detail caches for thread metadata, messages or activity.
     */
    override suspend fun threadReadModel(threadId: String): AgentThreadReadModel? = withContext(Dispatchers.Default) {
        val events = dao.eventsForThread(threadId).map(AgentEventEntity::toEnvelope)
        events.takeIf(List<AgentEventEnvelope>::isNotEmpty)
            ?.let(AgentEventReadModelProjector::project)
    }

    override suspend fun latestConversationThreadId(): String? = withContext(Dispatchers.Default) {
        // Read only IDs first. Normally a single replay is enough, even with years of history.
        // Keep the same quarantine boundary as the conversation list when a candidate is corrupt.
        dao.conversationThreadIdsByRecency().firstOrNull { threadId ->
            val result = AgentThreadProjectionResult.fromEvents(
                dao.eventsForThread(threadId).map(AgentEventEntity::toEnvelope)
            )
            (result as? AgentThreadProjectionResult.Projected)?.thread?.let {
                !it.metadata.tombstoned && it.conversation.entries.isNotEmpty()
            } == true
        }
    }

    override suspend fun activeThreadReadModels(): List<AgentThreadReadModel> = withContext(Dispatchers.Default) {
        val projections = buildList {
            dao.createdThreadIds().forEach { threadId ->
                val events = dao.eventsForThread(threadId).map(AgentEventEntity::toEnvelope)
                when (val result = AgentThreadProjectionResult.fromEvents(events)) {
                    is AgentThreadProjectionResult.Projected -> result.thread?.let(::add)
                    is AgentThreadProjectionResult.Quarantined -> Unit
                }
            }
        }
        projections
            .filterNot { it.metadata.tombstoned }
            .sortedForThreadList()
    }

    override fun observeThreadReadModel(threadId: String): Flow<AgentThreadReadModel?> =
        dao.observeThreadRevision(threadId)
            .projectThreadRevisions {
                dao.eventsForThread(threadId).takeIf(List<AgentEventEntity>::isNotEmpty)
                    ?.map(AgentEventEntity::toEnvelope)
                    ?.let(AgentEventReadModelProjector::project)
            }

    /**
     * UI observation boundary for a single thread. Strict replay remains unchanged; a corrupt
     * durable stream is surfaced as a typed quarantine result instead of cancelling its collector.
     */
    override fun observeThreadProjectionResult(threadId: String): Flow<AgentThreadProjectionResult> =
        dao.observeThreadRevision(threadId)
            .projectThreadRevisions {
                AgentThreadProjectionResult.fromEvents(
                    dao.eventsForThread(threadId).map(AgentEventEntity::toEnvelope)
                )
            }

    override fun observeActiveThreadReadModels(): Flow<List<AgentThreadReadModel>> =
        dao.observeCreatedThreadIds()
            .map { threadIds -> threadIds.distinct() }
            .distinctUntilChanged()
            .flatMapLatest { threadIds ->
                if (threadIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(threadIds.map(::observeThreadProjectionResult)) { results ->
                        results.mapNotNull { result ->
                            (result as? AgentThreadProjectionResult.Projected)?.thread
                        }
                            .filterNot { it.metadata.tombstoned }
                            .sortedForThreadList()
                    }
                }
            }
            .flowOn(Dispatchers.Default)

    override suspend fun count(): Int = dao.count()

    private suspend fun recordUserText(
        scope: TurnEventScope,
        messageId: String,
        text: String,
        steer: Boolean,
        attachments: List<UserAttachmentPayload>,
        activeDurationMillis: Long? = null
    ): AgentEventEnvelope {
        val payload = UserTextEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            messageId = messageId,
            text = text,
            attachments = attachments,
            activeDurationMillis = activeDurationMillis
        )
        val kind = if (steer) AgentEventKind.USER_STEER else AgentEventKind.USER_MESSAGE
        val draft = AgentEventDraft(
            eventKind = kind,
            threadId = scope.threadId,
            turnId = scope.turnId,
            payloadJson = Json.encodeToString(payload),
            idempotencyKey = if (steer) "message:$messageId:steer" else "turn:${scope.turnId}:user-message"
        )
        return appendChecked(draft) {
            requireOpenTurn(scope.threadId, scope.turnId)
            if (!steer && dao.eventsForTurn(scope.turnId).any {
                    it.eventKind == AgentEventKind.USER_MESSAGE.name
                }
            ) {
                throw AgentEventConflictException(
                    "Turn ${scope.turnId} already has its starting UserMessage"
                )
            }
        }
    }

    private suspend fun appendChecked(
        draft: AgentEventDraft,
        validateCurrentState: suspend () -> Unit
    ): AgentEventEnvelope {
        AgentEventSchema.validate(draft)
        val canonicalPayload = canonicalizeJson(draft.payloadJson)
        val payloadHash = sha256(canonicalPayload)
        return database.withTransaction {
            dao.findByIdempotencyKey(draft.idempotencyKey)?.let { existing ->
                verifyIdempotentRetry(existing, draft, payloadHash)
                return@withTransaction existing.toEnvelope()
            }
            validateCurrentState()
            appendDraft(draft, index = 0, SequenceCursor(dao.maxSequence() + 1L))
        }
    }

    private suspend fun appendDraft(
        draft: AgentEventDraft,
        index: Int,
        sequences: SequenceCursor
    ): AgentEventEnvelope {
        val canonicalPayload = canonicalizeJson(draft.payloadJson)
        val payloadHash = sha256(canonicalPayload)
        val existing = dao.findByIdempotencyKey(draft.idempotencyKey)
        if (existing != null) {
            verifyIdempotentRetry(existing, draft, payloadHash)
            return existing.toEnvelope()
        }

        beforeInsert(index, draft)
        val entity = draft.toEntity(
            eventId = eventIdFactory(),
            sequence = sequences.next++,
            occurredAt = nowMs(),
            payloadHash = payloadHash,
            canonicalPayload = canonicalPayload
        )
        try {
            dao.insert(entity)
        } catch (error: SQLiteConstraintException) {
            throw AgentEventConflictException(
                "Event constraint rejected ${draft.eventKind} (${draft.idempotencyKey})",
                error
            )
        }
        return entity.toEnvelope()
    }

    private suspend fun requireTurn(threadId: String, turnId: String) {
        requireActiveThread(threadId)
        val events = dao.eventsForTurn(turnId)
        val turnStarts = events.filter { it.eventKind == AgentEventKind.TURN_STARTED.name }
        if (turnStarts.size != 1 || turnStarts.single().threadId != threadId) {
            throw AgentEventConflictException(
                "Turn $turnId has no unique durable start in Thread $threadId"
            )
        }
    }

    private suspend fun requireActiveThread(threadId: String) {
        val events = dao.eventsForThread(threadId)
        val creations = events.filter { it.eventKind == AgentEventKind.THREAD_CREATED.name }
        if (creations.size != 1 || events.firstOrNull() != creations.single()) {
            throw AgentEventConflictException("Thread $threadId has no unique durable creation")
        }
        if (events.any { it.eventKind == AgentEventKind.THREAD_TOMBSTONED.name }) {
            throw AgentEventConflictException("Thread $threadId is tombstoned")
        }
    }

    private suspend fun requireOpenTurn(threadId: String, turnId: String) {
        requireTurn(threadId, turnId)
        if (dao.findTurnCompletion(turnId) != null) {
            throw AgentEventConflictException("Turn $turnId is already terminal")
        }
    }

    private suspend fun requireOpenTurn(scope: ModelRoundScope) =
        requireOpenTurn(scope.threadId, scope.turnId)

    private suspend fun requireDurableAttempt(
        scope: ExecutionAttemptEventScope
    ): DurableAttempt {
        val creation = dao.findAttemptCreation(scope.attemptId)
            ?: throw AgentEventConflictException(
                "ExecutionAttempt ${scope.attemptId} has no durable creation"
            )
        if (creation.threadId != scope.threadId || creation.turnId != scope.turnId ||
            creation.executionId != scope.executionId
        ) {
            throw AgentEventConflictException(
                "ExecutionAttempt ${scope.attemptId} belongs to a different frozen scope"
            )
        }
        val created = decodeEventPayload(
            creation,
            ExecutionAttemptCreatedEventPayload.serializer()
        )
        if (created.contractVersion != MODEL_ROUND_EVENT_CONTRACT_VERSION ||
            created.context.executionId != scope.executionId
        ) {
            throw AgentEventConflictException(
                "ExecutionAttempt ${scope.attemptId} has an invalid creation payload"
            )
        }
        var current = created.state
        var lifecycleSequence = creation.sequence
        dao.eventsForAttempt(scope.attemptId)
            .filter { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED.name }
            .forEach { event ->
                if (event.threadId != scope.threadId || event.turnId != scope.turnId ||
                    event.executionId != scope.executionId
                ) {
                    throw AgentEventConflictException(
                        "ExecutionAttempt ${scope.attemptId} contains a cross-scope state fact"
                    )
                }
                val payload = decodeEventPayload(
                    event,
                    ExecutionAttemptStateChangedEventPayload.serializer()
                )
                if (payload.contractVersion != AGENT_READ_EVENT_CONTRACT_VERSION) {
                    throw AgentEventConflictException(
                        "ExecutionAttempt ${scope.attemptId} has an unsupported state payload"
                    )
                }
                if (!ExecutionAttemptTransitions.allows(current, payload.state)) {
                    throw AgentEventConflictException(
                        "ExecutionAttempt ${scope.attemptId} contains an invalid durable transition"
                    )
                }
                current = payload.state
                lifecycleSequence = event.sequence
            }
        return DurableAttempt(
            state = current,
            creation = created,
            creationSequence = creation.sequence,
            lifecycleSequence = lifecycleSequence
        )
    }

    private suspend fun attemptsForTurn(
        threadId: String,
        turnId: String
    ): List<Pair<AgentEventEntity, DurableAttempt>> = dao.eventsForTurn(turnId)
        .filter { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED.name }
        .map { creation ->
            if (creation.threadId != threadId || creation.attemptId == null ||
                creation.executionId == null
            ) {
                throw AgentEventConflictException(
                    "Turn $turnId contains an invalid ExecutionAttempt creation"
                )
            }
            val scope = creation.toAttemptScope()
            creation to requireDurableAttempt(scope)
        }

    private suspend fun activeAttemptsForTurn(
        threadId: String,
        turnId: String
    ): List<Pair<AgentEventEntity, DurableAttempt>> = attemptsForTurn(threadId, turnId)
        .filter { (_, attempt) ->
            attempt.state !in ExecutionAttemptTransitions.terminalStates
        }

    private suspend fun currentTurnRecoverySnapshot(
        scope: TurnEventScope
    ): TurnRecoverySnapshot {
        val activeAttempts = activeAttemptsForTurn(scope.threadId, scope.turnId)
        if (activeAttempts.size > 1) {
            throw AgentEventConflictException(
                "Turn ${scope.turnId} has more than one active ExecutionAttempt"
            )
        }
        val pendingTools = dao.eventsForTurn(scope.turnId)
            .filter { it.eventKind == AgentEventKind.TOOL_CALL.name }
            .mapNotNull { declaration ->
                val toolScope = declaration.toToolScope()
                val call = requireDurableCall(toolScope)
                val started = call.started ?: return@mapNotNull null
                if (call.rejected != null || call.finished != null) {
                    return@mapNotNull null
                }
                val startedPayload = decodeEventPayload(
                    started,
                    ToolStartedEventPayload.serializer()
                )
                if (startedPayload.contractVersion != MODEL_ROUND_EVENT_CONTRACT_VERSION ||
                    startedPayload.roundDetailRef != call.payload.roundDetailRef
                ) {
                    throw AgentEventConflictException(
                        "Tool ${toolScope.callId} has an invalid durable start"
                    )
                }
                StartedToolRecoverySnapshot(
                    scope = toolScope,
                    roundDetailRef = call.payload.roundDetailRef,
                    startedSequence = started.sequence
                )
            }
        return TurnRecoverySnapshot(
            scope = scope,
            activeAttempts = activeAttempts.map { (creation, attempt) ->
                attempt.toSnapshot(creation.toAttemptScope())
            },
            pendingTools = pendingTools
        )
    }

    private fun requirePreparedAttemptMatchesDurable(
        attempt: ExecutionAttempt,
        capabilitySnapshot: CapabilitySnapshot,
        durable: DurableAttempt
    ) {
        val context = durable.creation.context
        val capability = durable.creation.capabilitySnapshot
        val preparedContext = attempt.context
        val matches = preparedContext.executionId == context.executionId &&
            preparedContext.runtimeMode == context.runtimeMode &&
            preparedContext.executionSpace == context.executionSpace &&
            preparedContext.displayId == context.displayId &&
            preparedContext.backendSessionId == context.backendSessionId &&
            preparedContext.capabilitySnapshotRef == context.capabilitySnapshotRef &&
            preparedContext.capabilitySnapshotHash == context.capabilitySnapshotHash &&
            preparedContext.cancellationScopeId == context.cancellationScopeId &&
            capabilitySnapshot.capabilitySnapshotRef == capability.capabilitySnapshotRef &&
            capabilitySnapshot.capabilitySnapshotHash == capability.capabilitySnapshotHash &&
            capabilitySnapshot.runtimeMode == capability.runtimeMode &&
            capabilitySnapshot.executionSpace == capability.executionSpace &&
            capabilitySnapshot.displayId == capability.displayId &&
            capabilitySnapshot.backendKind == capability.backendKind &&
            capabilitySnapshot.backendSessionId == capability.backendSessionId &&
            capabilitySnapshot.backendAvailable == capability.backendAvailable &&
            capabilitySnapshot.capabilities.map { it.name }.toSet() == capability.capabilities.toSet() &&
            capabilitySnapshot.scopedShellCapability?.evidenceKind?.name ==
                capability.scopedShellCapability?.evidenceKind &&
            capabilitySnapshot.scopedShellCapability?.backendKind ==
                capability.scopedShellCapability?.backendKind &&
            capabilitySnapshot.scopedShellCapability?.backendSessionId ==
                capability.scopedShellCapability?.backendSessionId &&
            capabilitySnapshot.scopedShellCapability?.displayId ==
                capability.scopedShellCapability?.displayId &&
            capabilitySnapshot.scopedShellCapability?.proofRef ==
                capability.scopedShellCapability?.proofRef &&
            capabilitySnapshot.scopedShellCapability?.proofHash ==
                capability.scopedShellCapability?.proofHash
        if (!matches) {
            throw AgentEventConflictException(
                "ExecutionAttempt ${attempt.attemptId} changed its frozen binding"
            )
        }
    }

    private suspend fun requireDurableCall(scope: ToolLifecycleEventScope): DurableCall {
        val events = dao.eventsForCall(scope.callId)
        val declarations = events.filter { it.eventKind == AgentEventKind.TOOL_CALL.name }
        if (declarations.size != 1) {
            throw AgentEventConflictException(
                "Tool ${scope.callId} has no unique durable declaration"
            )
        }
        if (events.any { event ->
                event.threadId != scope.threadId || event.turnId != scope.turnId ||
                    event.roundId != scope.roundId || event.attemptId != scope.attemptId ||
                    event.executionId != scope.executionId
            }
        ) {
            throw AgentEventConflictException(
                "Tool ${scope.callId} contains a cross-scope lifecycle fact"
            )
        }
        val payload = decodeEventPayload(
            declarations.single(),
            ToolCallEventPayload.serializer()
        )
        if (payload.contractVersion != MODEL_ROUND_EVENT_CONTRACT_VERSION) {
            throw AgentEventConflictException(
                "Tool ${scope.callId} has an unsupported declaration payload"
            )
        }
        val started = events.singleOrNull { it.eventKind == AgentEventKind.TOOL_STARTED.name }
        val rejected = events.singleOrNull {
            it.eventKind == AgentEventKind.TOOL_CALL_REJECTED.name
        }
        val finished = events.singleOrNull { it.eventKind == AgentEventKind.TOOL_FINISHED.name }
        if (events.count { it.eventKind == AgentEventKind.TOOL_STARTED.name } > 1 ||
            events.count { it.eventKind == AgentEventKind.TOOL_CALL_REJECTED.name } > 1 ||
            events.count { it.eventKind == AgentEventKind.TOOL_FINISHED.name } > 1
        ) {
            throw AgentEventConflictException("Tool ${scope.callId} has duplicate lifecycle facts")
        }
        return DurableCall(payload, started, rejected, finished)
    }

    private suspend fun requireDurableToolAttempt(
        scope: ToolLifecycleEventScope,
        binding: ToolBinding,
        requireForStarted: Boolean = true
    ) {
        when (binding) {
            ToolBinding.TURN_LOCAL -> if (scope.attemptId != null || scope.executionId != null) {
                throw AgentEventConflictException(
                    "TURN_LOCAL Tool ${scope.callId} cannot fabricate an ExecutionAttempt"
                )
            }
            ToolBinding.DEVICE_BOUND -> if (requireForStarted) {
                val attemptId = scope.attemptId ?: throw AgentEventConflictException(
                    "DEVICE_BOUND Tool ${scope.callId} has no frozen ExecutionAttempt"
                )
                val executionId = scope.executionId ?: throw AgentEventConflictException(
                    "DEVICE_BOUND Tool ${scope.callId} has no frozen executionId"
                )
                requireDurableAttempt(
                    ExecutionAttemptEventScope(
                        threadId = scope.threadId,
                        turnId = scope.turnId,
                        attemptId = attemptId,
                        executionId = executionId
                    )
                )
            }
        }
    }

    private suspend fun requireTurnLifecycleSettled(turnId: String) {
        val events = dao.eventsForTurn(turnId)
        TurnSettlementContract.requireSettledTools(turnId, events.map(AgentEventEntity::toEnvelope))
        events.filter { it.eventKind == AgentEventKind.TOOL_CALL.name }.forEach { declaration ->
            val scope = ToolLifecycleEventScope(
                threadId = declaration.threadId,
                turnId = checkNotNull(declaration.turnId),
                roundId = checkNotNull(declaration.roundId),
                callId = checkNotNull(declaration.callId),
                attemptId = declaration.attemptId,
                executionId = declaration.executionId
            )
            val call = requireDurableCall(scope)
            val hasDisposition = (call.started == null) xor (call.rejected == null)
            if (!hasDisposition || (call.started != null && call.finished == null)) {
                throw AgentEventConflictException(
                    "Turn $turnId cannot complete with unsettled Tool ${scope.callId}"
                )
            }
        }
        events.filter {
            it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED.name
        }.forEach { creation ->
            val scope = ExecutionAttemptEventScope(
                threadId = creation.threadId,
                turnId = checkNotNull(creation.turnId),
                attemptId = checkNotNull(creation.attemptId),
                executionId = checkNotNull(creation.executionId)
            )
            if (requireDurableAttempt(scope).state !in ExecutionAttemptTransitions.terminalStates) {
                throw AgentEventConflictException(
                    "Turn $turnId cannot complete with active ExecutionAttempt ${scope.attemptId}"
                )
            }
        }
    }

    private fun <T> decodeEventPayload(
        event: AgentEventEntity,
        serializer: kotlinx.serialization.KSerializer<T>
    ): T = try {
        Json.decodeFromString(serializer, event.payloadJson)
    } catch (error: IllegalArgumentException) {
        throw AgentEventConflictException(
            "${event.eventKind} ${event.eventId} has an invalid typed payload",
            error
        )
    }

    private suspend fun requireSubmittedItemRange(
        scope: ModelRoundScope,
        range: SubmittedItemSequenceRange,
        beforeSequence: Long
    ) {
        val first = dao.findBySequence(range.firstSequence)
        val last = dao.findBySequence(range.lastSequence)
        if (first == null || last == null ||
            first.threadId != scope.threadId || last.threadId != scope.threadId ||
            first.turnId != scope.turnId || last.turnId != scope.turnId ||
            first.sequence >= beforeSequence || last.sequence >= beforeSequence
        ) {
            throw AgentEventConflictException(
                "Submitted Item range is not a durable prefix of ModelRound ${scope.roundId}"
            )
        }
    }

    private suspend fun durableStartCommitted(turnId: String): Boolean =
        dao.eventsForTurn(turnId)
            .asSequence()
            .filter { it.eventKind == AgentEventKind.ASSISTANT_MESSAGE.name }
            .map { event ->
                try {
                    Json.decodeFromString<AssistantMessageEventPayload>(event.payloadJson)
                } catch (error: IllegalArgumentException) {
                    throw AgentEventConflictException(
                        "Turn $turnId contains an invalid AssistantMessage payload",
                        error
                    )
                }
            }
            .any {
                it.kind == com.droidance.core.agent.harness.PublicMessageKind.START ||
                    it.kind == com.droidance.core.agent.harness.PublicMessageKind.USER_INTERVENTION
            }

    private fun decodeRoundStart(entity: AgentEventEntity): ModelRoundStartedEventPayload =
        try {
            Json.decodeFromString(entity.payloadJson)
        } catch (error: IllegalArgumentException) {
            throw AgentEventConflictException(
                "ModelRound ${entity.roundId} has an invalid request boundary payload",
                error
            )
        }

    private fun requireContiguousPlanBatch(events: List<AgentEventEntity>) {
        if (events.zipWithNext().any { (left, right) -> right.sequence != left.sequence + 1L }) {
            throw AgentEventConflictException(
                "A durable ModelRound plan batch is not contiguous"
            )
        }
    }

    private fun modelSessionEpochDraft(
        threadId: String,
        turnId: String,
        transition: ModelSessionEpochTransition,
        diagnosticCode: String? = null,
        idempotencyKey: String
    ): AgentEventDraft {
        val epochPayload = ModelSessionEpochAdvancedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            previousEpoch = transition.previous.epoch,
            currentEpoch = transition.current.epoch,
            reason = transition.reason,
            evidenceRef = transition.evidenceRef,
            diagnosticCode = diagnosticCode,
            identity = transition.current.identity
        )
        return AgentEventDraft(
            eventKind = AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED,
            threadId = threadId,
            turnId = turnId,
            payloadJson = Json.encodeToString(epochPayload),
            idempotencyKey = idempotencyKey
        )
    }

    private suspend fun requireCurrentDurableEpoch(
        turnId: String,
        currentSession: ModelSessionKey
    ) {
        val turn = threadReadModel(currentSession.identity.threadId)?.turns?.singleOrNull {
            it.turnId == turnId
        }
        if (turn?.modelSessionEpoch != currentSession.epoch || turn.modelSessionIdentity != currentSession.identity) {
            throw AgentEventConflictException(
                "Model session does not match the current durable epoch and identity"
            )
        }
    }

    private suspend fun requireCheckpointTurnSession(
        threadId: String,
        turnId: String,
        frozenSession: FrozenTurnModelSession
    ) {
        requireOpenTurn(threadId, turnId)
        val events = dao.eventsForTurn(turnId)
        val turnStart = events.singleOrNull {
            it.eventKind == AgentEventKind.TURN_STARTED.name
        } ?: throw AgentEventConflictException("Turn $turnId has no unique durable start")
        val started = decodeEventPayload(turnStart, TurnStartedEventPayload.serializer())
        val activations = events.filter {
            it.eventKind in setOf(AgentEventKind.MODEL_SESSION_ACTIVATED.name, AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED.name)
        }
        if (activations.size > 1) {
            throw AgentEventConflictException("Turn $turnId has multiple model session activations")
        }
        val activated = activations.singleOrNull()?.let {
            decodeEventPayload(it, ModelSessionActivatedEventPayload.serializer())
        }
        val identity = activated?.modelSessionIdentity ?: started.modelSessionIdentity
        val snapshotRef = activated?.providerConfigSnapshotRef ?: started.providerConfigSnapshotRef
        if (identity != started.modelSessionIdentity.copy(stablePrefixHash = identity.stablePrefixHash) ||
            snapshotRef != started.providerConfigSnapshotRef
        ) {
            throw AgentEventConflictException("Android activation must retain its Turn Provider and model")
        }
        if (identity != frozenSession.key.identity ||
            snapshotRef != frozenSession.providerConfigSnapshotRef
        ) {
            throw AgentEventConflictException(
                "Context checkpoint must use the current durable ModelSession for its Turn"
            )
        }
    }

    private suspend fun latestContextCheckpoint(turnId: String): ContextCheckpointRecord? =
        dao.eventsForTurn(turnId)
            .asReversed()
            .firstOrNull { it.eventKind == AgentEventKind.CONTEXT_CHECKPOINT.name }
            ?.let { event ->
                ContextCheckpointRecord(
                    eventId = event.eventId,
                    payload = decodeEventPayload(event, ContextCheckpointPayload.serializer())
                )
            }

    private fun requireCheckpointParent(
        payload: ContextCheckpointPayload,
        previous: ContextCheckpointRecord?
    ) {
        if (payload.parentCheckpointEventId != previous?.eventId) {
            throw AgentEventConflictException(
                "Context checkpoint parent must be the latest durable checkpoint for its Turn"
            )
        }
        previous ?: return
        if (payload.sourceThroughSequence <= previous.payload.sourceThroughSequence ||
            payload.stablePrefixHash != previous.payload.stablePrefixHash ||
            payload.contextPolicyHash != previous.payload.contextPolicyHash
        ) {
            throw AgentEventConflictException(
                "Context checkpoint must advance the current frozen projection without changing policy"
            )
        }
    }

    private fun verifyDurableCheckpointEpoch(
        epoch: AgentEventEntity,
        expected: ModelSessionEpochTransition
    ) {
        val payload = decodeEventPayload(epoch, ModelSessionEpochAdvancedEventPayload.serializer())
        if (payload.previousEpoch != expected.previous.epoch ||
            payload.currentEpoch != expected.current.epoch ||
            payload.reason != expected.reason ||
            payload.evidenceRef != expected.evidenceRef ||
            payload.identity != expected.current.identity
        ) {
            throw AgentEventConflictException(
                "Durable model session epoch does not match its context checkpoint"
            )
        }
    }

    private fun modelSessionIdentityHash(identity: ModelSessionIdentity): String =
        sha256(canonicalizeJson(Json.encodeToString(identity)))

    private fun verifyIdempotentRetry(
        existing: AgentEventEntity,
        draft: AgentEventDraft,
        payloadHash: String
    ) {
        val matches = existing.eventKind == draft.eventKind.name &&
            existing.threadId == draft.threadId &&
            existing.turnId == draft.turnId &&
            existing.roundId == draft.roundId &&
            existing.attemptId == draft.attemptId &&
            existing.executionId == draft.executionId &&
            existing.callId == draft.callId &&
            existing.payloadHash == payloadHash
        if (!matches) {
            throw AgentEventConflictException(
                "Idempotency key ${draft.idempotencyKey} was reused with different event content"
            )
        }
    }

    private fun AgentEventDraft.toEntity(
        eventId: String,
        sequence: Long,
        occurredAt: Long,
        payloadHash: String,
        canonicalPayload: String
    ): AgentEventEntity = AgentEventEntity(
        eventId = eventId,
        schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
        eventKind = eventKind.name,
        sequence = sequence,
        occurredAt = occurredAt,
        threadId = threadId,
        turnId = turnId,
        roundId = roundId,
        attemptId = attemptId,
        executionId = executionId,
        callId = callId,
        payloadHash = payloadHash,
        payloadJson = canonicalPayload,
        idempotencyKey = idempotencyKey,
        turnTerminalKey = turnId.takeIf { eventKind == AgentEventKind.TURN_COMPLETED },
        repairKey = null,
        roundStartKey = roundId.takeIf {
            eventKind == AgentEventKind.MODEL_ROUND_STARTED
        },
        roundCompletionKey = roundId.takeIf {
            eventKind == AgentEventKind.MODEL_ROUND_COMPLETED
        },
        callDeclarationKey = callId.takeIf {
            eventKind == AgentEventKind.TOOL_CALL
        },
        callDispositionKey = callId.takeIf {
            eventKind == AgentEventKind.TOOL_STARTED ||
                eventKind == AgentEventKind.TOOL_CALL_REJECTED
        },
        attemptCreationKey = attemptId.takeIf {
            eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED
        },
        executionCreationKey = executionId.takeIf {
            eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED
        }
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class DurableAttempt(
        val state: ExecutionAttemptState,
        val creation: ExecutionAttemptCreatedEventPayload,
        val creationSequence: Long,
        val lifecycleSequence: Long
    ) {
        fun toSnapshot(scope: ExecutionAttemptEventScope): DurableExecutionAttemptSnapshot =
            DurableExecutionAttemptSnapshot(
                scope = scope,
                state = state,
                creationSequence = creationSequence,
                lifecycleSequence = lifecycleSequence,
                context = creation.context,
                capabilitySnapshot = creation.capabilitySnapshot
            )
    }

    private data class DurableCall(
        val payload: ToolCallEventPayload,
        val started: AgentEventEntity?,
        val rejected: AgentEventEntity?,
        val finished: AgentEventEntity?
    )

    private class SequenceCursor(var next: Long)

    private fun AgentEventEntity.toAttemptScope(): ExecutionAttemptEventScope =
        ExecutionAttemptEventScope(
            threadId = threadId,
            turnId = checkNotNull(turnId),
            attemptId = checkNotNull(attemptId),
            executionId = checkNotNull(executionId)
        )

    private fun AgentEventEntity.toToolScope(): ToolLifecycleEventScope =
        ToolLifecycleEventScope(
            threadId = threadId,
            turnId = checkNotNull(turnId),
            roundId = checkNotNull(roundId),
            callId = checkNotNull(callId),
            attemptId = attemptId,
            executionId = executionId
        )

    private fun learningThreadId(sessionId: String): String = "skill-learning:$sessionId"

    private fun skillRunThreadId(runId: String): String = "compiled-skill-run:$runId"

    private companion object {
        val modelRoundPlanOwnedKinds = setOf(
            AgentEventKind.MODEL_ROUND_COMPLETED,
            AgentEventKind.ASSISTANT_MESSAGE,
            AgentEventKind.PUBLIC_PROGRESS,
            AgentEventKind.EXECUTION_ATTEMPT_CREATED,
            AgentEventKind.TOOL_CALL,
            AgentEventKind.TOOL_CALL_REJECTED,
            AgentEventKind.TOOL_STARTED,
            AgentEventKind.USER_INTERVENTION_REQUESTED
        )

        val coordinatedEventKinds = setOf(
            AgentEventKind.CONTEXT_CHECKPOINT,
            AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED,
            AgentEventKind.MODEL_SESSION_ACTIVATED,
            AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED,
            AgentEventKind.MODEL_ROUND_STARTED,
            AgentEventKind.TURN_RECOVERY_REQUIRED,
            AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED,
            AgentEventKind.TOOL_FINISHED,
            AgentEventKind.DETAIL_ATTACHMENT_ADDED,
            AgentEventKind.TURN_COMPLETED
        ) + modelRoundPlanOwnedKinds
    }
}

private fun List<AgentThreadReadModel>.sortedForThreadList(): List<AgentThreadReadModel> =
    sortedWith(
        compareByDescending<AgentThreadReadModel> { it.metadata.pinned }
            .thenByDescending { it.metadata.lastActivitySequence }
    )
