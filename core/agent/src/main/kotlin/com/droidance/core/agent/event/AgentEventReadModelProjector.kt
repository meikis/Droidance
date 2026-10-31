// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionAttemptTransitions
import com.droidance.core.agent.context.ContextCheckpointPayload
import com.droidance.core.agent.context.ContextCheckpointRecord
import com.droidance.core.agent.context.ContextCheckpointValidator
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.RoundExecutionMode
import com.droidance.core.agent.harness.RoundFollowUp
import com.droidance.core.agent.harness.ZERO_TOOL_DIAGNOSTIC_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_DECISION_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION
import com.droidance.core.agent.harness.ZERO_TOOL_PHASE_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_PHASE_VALUE
import com.droidance.core.agent.harness.ZeroToolRoundDecision
import com.droidance.core.agent.harness.ResultCardToolArguments
import com.droidance.core.agent.llm.session.EpochAdvanceReason
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import java.util.Collections
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AgentEventProjectionException(
    val code: String,
    val eventId: String? = null,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

/**
 * Strict, side-effect-free EventStore replay. It never sorts, repairs or skips facts.
 */
object AgentEventReadModelProjector {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    fun project(events: List<AgentEventEnvelope>): AgentThreadReadModel {
        if (events.isEmpty()) {
            fail("EMPTY_STREAM", null, "Cannot project an empty AgentEvent stream")
        }
        validateEnvelopeStream(events)
        return MutableProjection(events.first().threadId, events.toList())
            .apply { events.forEach(::accept) }
            .finish()
    }

    private class MutableProjection(
        private val threadId: String,
        private val sourceEvents: List<AgentEventEnvelope>
    ) {
        private var thread: MutableThread? = null
        private val turns = linkedMapOf<String, MutableTurn>()
        private val programEventsByTurn = sourceEvents.filter { it.eventKind == AgentEventKind.PROGRAM_EXECUTION }.groupBy { it.turnId }
        private val rounds = linkedMapOf<String, MutableRound>()
        private val attempts = linkedMapOf<String, MutableAttempt>()
        private val calls = linkedMapOf<String, MutableCall>()
        private val messages = linkedMapOf<String, MessageScope>()
        private val conversation = mutableListOf<ConversationEntryReadModel>()
        private val lifecycleByTurn = linkedMapOf<String, MutableList<ActivityEntryReadModel>>()
        private val checkpointsByTurn = linkedMapOf<String, ContextCheckpointRecord>()
        private val latestEpochByTurn = linkedMapOf<String, Long>()
        private val memoryContextInputs = mutableSetOf<Triple<String, Long, String?>>()
        private val latestUserInputs = mutableMapOf<String, Long>()
        private var pendingCheckpointEpoch: Pair<AgentEventEnvelope, ContextCheckpointRecord>? = null

        fun accept(event: AgentEventEnvelope) {
            pendingCheckpointEpoch?.let { (checkpointEvent, _) ->
                if (event.eventKind != AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED ||
                    event.threadId != checkpointEvent.threadId ||
                    event.turnId != checkpointEvent.turnId
                ) {
                    fail(
                        "UNPAIRED_CONTEXT_CHECKPOINT",
                        event,
                        "ContextCheckpoint must be followed by its ModelSession epoch boundary"
                    )
                }
            }
            val currentThread = thread
            if (currentThread?.tombstoned == true) {
                fail(
                    "EVENT_AFTER_THREAD_TOMBSTONE",
                    event,
                    "${event.eventKind} cannot follow ThreadTombstoned"
                )
            }
            event.turnId?.let { turnId ->
                val terminal = turns[turnId]?.completion
                if (terminal != null && event.eventKind != AgentEventKind.DETAIL_ATTACHMENT_ADDED) {
                    fail(
                        "EVENT_AFTER_TURN_COMPLETION",
                        event,
                        "Only a late detail attachment may follow TurnCompleted"
                    )
                }
            }

            when (event.eventKind) {
                AgentEventKind.PROGRAM_EXECUTION, AgentEventKind.WORKSPACE_FILE_WRITTEN,
                AgentEventKind.WORKSPACE_FOCUS_CHANGED -> Unit // Working state does not declare task success.
                AgentEventKind.THREAD_CREATED -> acceptThreadCreated(event)
                AgentEventKind.THREAD_METADATA_CHANGED -> acceptThreadMetadataChanged(event)
                AgentEventKind.THREAD_PIN_CHANGED -> acceptThreadPinChanged(event)
                AgentEventKind.THREAD_TOMBSTONED -> acceptThreadTombstoned(event)
                AgentEventKind.TURN_STARTED -> acceptTurnStarted(event)
                AgentEventKind.MODEL_SESSION_ACTIVATED,
                AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED -> acceptModelSessionActivated(event)
                AgentEventKind.TURN_RECOVERY_REQUIRED -> acceptTurnRecoveryRequired(event)
                AgentEventKind.USER_MESSAGE -> acceptUserText(event, steer = false)
                AgentEventKind.USER_STEER -> acceptUserText(event, steer = true)
                AgentEventKind.MEMORY_CONTEXT_LOADED -> acceptMemoryContextLoaded(event)
                AgentEventKind.MODEL_ROUND_STARTED -> acceptModelRoundStarted(event)
                AgentEventKind.MODEL_ROUND_COMPLETED -> acceptModelRoundCompleted(event)
                AgentEventKind.ASSISTANT_MESSAGE -> acceptAssistantMessage(event)
                AgentEventKind.PUBLIC_PROGRESS -> acceptPublicProgress(event)
                AgentEventKind.SYSTEM_NOTICE -> acceptSystemNotice(event)
                AgentEventKind.EXECUTION_ATTEMPT_CREATED -> acceptAttemptCreated(event)
                AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED -> acceptAttemptState(event)
                AgentEventKind.TOOL_CALL -> acceptToolCall(event)
                AgentEventKind.TOOL_CALL_REJECTED -> acceptToolRejected(event)
                AgentEventKind.TOOL_STARTED -> acceptToolStarted(event)
                AgentEventKind.TOOL_FINISHED -> acceptToolFinished(event)
                AgentEventKind.USER_INTERVENTION_REQUESTED -> acceptIntervention(event)
                AgentEventKind.DETAIL_ATTACHMENT_ADDED -> acceptAttachment(event)
                AgentEventKind.CONTEXT_CHECKPOINT -> acceptContextCheckpoint(event)
                AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED -> acceptEpochAdvanced(event)
                AgentEventKind.LEARNING_SESSION_STARTED,
                AgentEventKind.LEARNING_ACTION_RECORDED,
                AgentEventKind.LEARNING_SESSION_COMPLETED,
                AgentEventKind.LEARNING_SKILL_COMPILED,
                AgentEventKind.COMPILED_SKILL_RUN_STARTED,
                AgentEventKind.COMPILED_SKILL_RUN_COMPLETED -> Unit
                AgentEventKind.TURN_COMPLETED -> acceptTurnCompleted(event)
            }
            thread?.apply {
                lastActivitySequence = event.sequence
                lastActivityAt = event.occurredAt
            }
        }

        private fun acceptThreadCreated(event: AgentEventEnvelope) {
            if (thread != null) {
                fail("DUPLICATE_THREAD", event, "ThreadCreated must occur exactly once")
            }
            val payload = decode(event, ThreadCreatedEventPayload.serializer())
            thread = MutableThread(
                title = payload.initialTitle,
                summary = null,
                pinned = false,
                tombstoned = false,
                createdSequence = event.sequence,
                createdAt = event.occurredAt,
                lastActivitySequence = event.sequence,
                lastActivityAt = event.occurredAt
            )
        }

        private fun acceptThreadMetadataChanged(event: AgentEventEnvelope) {
            val current = requireThread(event)
            val payload = decode(event, ThreadMetadataChangedEventPayload.serializer())
            current.title = payload.title
            current.summary = payload.summary
        }

        private fun acceptMemoryContextLoaded(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            val source = latestUserInputs[turn.turnId]
                ?: fail("MEMORY_CONTEXT_WITHOUT_INPUT", event, "Memory context requires a user input")
            val payload = decode(event, MemoryContextLoadedEventPayload.serializer())
            if (!memoryContextInputs.add(Triple(turn.turnId, source, payload.contextKey))) {
                fail("DUPLICATE_MEMORY_CONTEXT", event, "A user input can load one memory context per execution context")
            }
        }

        private fun acceptThreadPinChanged(event: AgentEventEnvelope) {
            val current = requireThread(event)
            current.pinned = decode(event, ThreadPinChangedEventPayload.serializer()).isPinned
        }

        private fun acceptThreadTombstoned(event: AgentEventEnvelope) {
            val current = requireThread(event)
            decode(event, ThreadTombstonedEventPayload.serializer())
            if (turns.values.any { it.completion == null }) {
                fail("OPEN_TURN_AT_TOMBSTONE", event, "Thread cannot be tombstoned with an open Turn")
            }
            current.tombstoned = true
        }

        private fun acceptTurnStarted(event: AgentEventEnvelope) {
            requireThread(event)
            val turnId = event.requireTurnId()
            if (turns.containsKey(turnId)) {
                fail("DUPLICATE_TURN", event, "TurnStarted must occur exactly once for $turnId")
            }
            val payload = decode(event, TurnStartedEventPayload.serializer())
            if (payload.modelSessionIdentity.threadId != event.threadId) {
                fail(
                    "MODEL_SESSION_THREAD_MISMATCH",
                    event,
                    "Frozen ModelSessionIdentity belongs to another Thread"
                )
            }
            turns[turnId] = MutableTurn(event, payload)
        }

        private fun acceptTurnRecoveryRequired(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            val payload = decode(event, TurnRecoveryRequiredEventPayload.serializer())
            turn.recoveryRequired = event to payload
            lifecycle(turn.turnId) += LifecycleActivityReadModel(
                itemId = event.eventId,
                turnId = turn.turnId,
                sequence = event.sequence,
                kind = LifecycleActivityKind.TURN_PAUSED_RECOVERY_REQUIRED,
                attemptId = null,
                executionId = null,
                callId = null,
                diagnosticCode = payload.diagnosticCode
            )
        }

        private fun acceptModelSessionActivated(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            if (turn.activationKind != TurnActivationKind.DIRECT) {
                fail("DUPLICATE_MODEL_SESSION_ACTIVATION", event, "A Turn can activate once")
            }
            val payload = decode(event, ModelSessionActivatedEventPayload.serializer())
            if (payload.modelSessionIdentity.threadId != event.threadId) {
                fail("MODEL_SESSION_THREAD_MISMATCH", event, "Activated identity belongs to another Thread")
            }
            val phaseAIdentity = turn.payload.modelSessionIdentity
            val phaseBIdentity = payload.modelSessionIdentity
            if (phaseBIdentity.providerId != phaseAIdentity.providerId ||
                phaseBIdentity.transportKind != phaseAIdentity.transportKind ||
                phaseBIdentity.transportConfigHash != phaseAIdentity.transportConfigHash ||
                phaseBIdentity.authScopeHash != phaseAIdentity.authScopeHash ||
                phaseBIdentity.modelId != phaseAIdentity.modelId
            ) {
                fail(
                    "ANDROID_ACTIVATION_MODEL_SWITCH",
                    event,
                    "Android activation must retain the Phase A Provider and model"
                )
            }
            if (payload.previousEpoch != turn.modelSessionEpoch) {
                fail("MODEL_SESSION_EPOCH_GAP", event, "Activation must follow the current durable epoch")
            }
            val priorRounds = rounds.values.filter {
                it.turnId == turn.turnId && it.started.sequence > (turn.lastRecoveryResumeSequence ?: 0L)
            }
            if (priorRounds.isNotEmpty() && !priorRounds.isValidZeroToolActivationBoundary(event.eventKind)) {
                fail(
                    "INVALID_MODEL_SESSION_ACTIVATION_BOUNDARY",
                    event,
                    "Activation may follow only one completed zero-tool activation round"
                )
            }
            turn.modelSessionIdentity = payload.modelSessionIdentity
            turn.providerConfigSnapshotRef = payload.providerConfigSnapshotRef
            turn.modelSessionEpoch = payload.currentEpoch
            latestEpochByTurn[turn.turnId] = payload.currentEpoch
            turn.activationKind = if (event.eventKind == AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED)
                TurnActivationKind.LOCAL else TurnActivationKind.ANDROID
        }

        private fun List<MutableRound>.isValidZeroToolActivationBoundary(kind: AgentEventKind): Boolean {
            val ordered = sortedBy { it.started.sequence }
            val round = ordered.lastOrNull() ?: return false
            // Finite repair attempts are closed, silent zero-tool rounds, not activation.
            if (ordered.dropLast(1).any { prior ->
                val failed = prior.completedPayload
                failed == null || failed.finishMetadata[ZERO_TOOL_PHASE_KEY] != ZERO_TOOL_PHASE_VALUE ||
                    failed.finishMetadata[ZERO_TOOL_DECISION_KEY] != ZeroToolRoundDecision.FAILED.wireName ||
                    failed.publicMessageKind != null || failed.publicBlocks.isNotEmpty() ||
                    failed.executionMode != RoundExecutionMode.NONE ||
                    failed.followUp != RoundFollowUp.TERMINATE_PROTOCOL ||
                    failed.terminalDiagnosticCode.isNullOrBlank() ||
                    failed.terminalDiagnosticCode != failed.finishMetadata[ZERO_TOOL_DIAGNOSTIC_KEY]
            }) return false
            val completion = round.completedPayload ?: return false
            return completion.finishMetadata[ZERO_TOOL_PHASE_KEY] == ZERO_TOOL_PHASE_VALUE &&
                completion.finishMetadata[ZERO_TOOL_DECISION_KEY] ==
                (if (kind == AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED)
                    ZeroToolRoundDecision.ACTIVATE_LOCAL else ZeroToolRoundDecision.ACTIVATE_ANDROID).wireName &&
                completion.publicBlocks.isNotEmpty() &&
                (when (completion.finishMetadata[ZERO_TOOL_OPENING_VERSION_KEY]) {
                    ZERO_TOOL_OPENING_VERSION -> completion.publicMessageKind == PublicMessageKind.START && round.openingCommitted
                    // Immutable pre-envelope history keeps its original FINAL boundary. Live
                    // generation always stamps the opening version and cannot enter this path.
                    null -> completion.publicMessageKind == PublicMessageKind.FINAL &&
                        completion.publicBlocks == listOf(if (kind == AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED)
                            "<activate_local/>" else "<activate_android/>")
                    else -> false
                }) &&
                completion.executionMode == RoundExecutionMode.NONE &&
                completion.followUp == RoundFollowUp.COMPLETE_AFTER_RESULTS &&
                completion.terminalDiagnosticCode == null
        }

        private fun acceptUserText(event: AgentEventEnvelope, steer: Boolean) {
            val turn = requireOpenTurn(event)
            val payload = decode(event, UserTextEventPayload.serializer())
            registerMessage(payload.messageId, event, if (steer) MessageKind.USER_STEER else MessageKind.USER)
            if (!steer && turn.userMessageSeen) {
                fail("DUPLICATE_USER_MESSAGE", event, "A Turn may have only one starting UserMessage")
            }
            if (!steer) turn.userMessageSeen = true
            latestUserInputs[turn.turnId] = event.sequence
            conversation += ConversationEntryReadModel(
                itemId = payload.messageId,
                sourceEventId = event.eventId,
                threadId = event.threadId,
                turnId = event.requireTurnId(),
                sequence = event.sequence,
                occurredAt = event.occurredAt,
                kind = if (steer) {
                    ConversationEntryKind.USER_STEER
                } else {
                    ConversationEntryKind.USER_MESSAGE
                },
                text = payload.text,
                attachments = payload.attachments,
                activeDurationMillis = payload.activeDurationMillis
            )
        }

        private fun acceptModelRoundStarted(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val roundId = event.requireRoundId()
            if (rounds.containsKey(roundId)) {
                fail("DUPLICATE_MODEL_ROUND", event, "ModelRoundStarted must be unique for $roundId")
            }
            val payload = decode(event, ModelRoundStartedEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            if (payload.roundDetailRef.isBlank()) {
                fail("INVALID_ROUND_DETAIL_REF", event, "ModelRound detail ref cannot be blank")
            }
            rounds[roundId] = MutableRound(event, payload)
        }

        private fun acceptModelRoundCompleted(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val round = requireRound(event)
            if (round.completed != null) {
                fail("DUPLICATE_MODEL_ROUND_COMPLETION", event, "ModelRoundCompleted must be unique")
            }
            val payload = decode(event, ModelRoundCompletedEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            if (!payload.planFingerprint.isSha256() || payload.roundDetailRef.isBlank()) {
                fail("INVALID_MODEL_ROUND_COMPLETION", event, "ModelRound completion fingerprint/ref is invalid")
            }
            if (payload.submittedItems != round.startedPayload.submittedItems ||
                payload.roundDetailRef != round.startedPayload.roundDetailRef ||
                payload.providerAckSequence != payload.submittedItems.lastSequence
            ) {
                fail(
                    "MODEL_ROUND_BOUNDARY_MISMATCH",
                    event,
                    "ModelRound completion does not match its durable request boundary"
                )
            }
            requirePublicBlocks(event, payload.publicBlocks, allowEmpty = payload.publicMessageKind == null)
            if ((payload.publicMessageKind == null) != payload.publicBlocks.isEmpty()) {
                fail(
                    "PUBLIC_MESSAGE_CLASSIFICATION_MISMATCH",
                    event,
                    "Public message kind and blocks must either both be absent or both be present"
                )
            }
            round.completed = event
            round.completedPayload = payload
        }

        private fun acceptAssistantMessage(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            val round = requireCompletedRound(event)
            val payload = decode(event, AssistantMessageEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            requirePublicBlocks(event, payload.publicBlocks, allowEmpty = false)
            requirePublicEventMatchesRound(event, round, payload.kind, payload.publicBlocks, payload.roundDetailRef)
            if (payload.kind == PublicMessageKind.START) {
                if (turn.startMessageSeen) {
                    fail("DUPLICATE_START_MESSAGE", event, "A Turn may have only one START message")
                }
                // A turn may legitimately begin with TURN_LOCAL progress (for example a bounded
                // wait or local context lookup) before its first DEVICE_BOUND action. In that
                // case the first visible start receipt is durably committed in the later round;
                // rejecting it would quarantine an otherwise valid turn on replay.
                turn.startMessageSeen = true
                round.openingCommitted = true
            }
            registerMessage(
                payload.messageId,
                event,
                when (payload.kind) {
                    PublicMessageKind.START -> MessageKind.START
                    PublicMessageKind.STAGE_RESULT -> MessageKind.STAGE_RESULT
                    else -> MessageKind.INTERVENTION
                }
            )
            conversation += ConversationEntryReadModel(
                itemId = payload.messageId,
                sourceEventId = event.eventId,
                threadId = event.threadId,
                turnId = event.requireTurnId(),
                roundId = event.requireRoundId(),
                sequence = event.sequence,
                occurredAt = event.occurredAt,
                kind = when (payload.kind) {
                    PublicMessageKind.START -> ConversationEntryKind.ASSISTANT_START
                    PublicMessageKind.STAGE_RESULT -> ConversationEntryKind.ASSISTANT_STAGE_RESULT
                    else -> ConversationEntryKind.ASSISTANT_USER_INTERVENTION
                },
                text = joinPublicBlocks(payload.publicBlocks)
            )
        }

        private fun acceptPublicProgress(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val round = requireCompletedRound(event)
            val payload = decode(event, PublicProgressEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            requirePublicBlocks(event, payload.publicBlocks, allowEmpty = false)
            requirePublicEventMatchesRound(
                event,
                round,
                PublicMessageKind.PROGRESS,
                payload.publicBlocks,
                payload.roundDetailRef
            )
            if (round.progress != null) {
                fail("DUPLICATE_PUBLIC_PROGRESS", event, "A ModelRound may have one PublicProgress")
            }
            round.progress = event to payload
        }

        private fun acceptSystemNotice(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val payload = decode(event, SystemNoticeEventPayload.serializer())
            registerMessage(payload.noticeId, event, MessageKind.NOTICE)
            conversation += ConversationEntryReadModel(
                itemId = payload.noticeId,
                sourceEventId = event.eventId,
                threadId = event.threadId,
                turnId = event.requireTurnId(),
                sequence = event.sequence,
                occurredAt = event.occurredAt,
                kind = ConversationEntryKind.SYSTEM_NOTICE,
                text = payload.text,
                diagnosticCode = payload.diagnosticCode
            )
            lifecycle(event.requireTurnId()) += NoticeActivityReadModel(
                itemId = event.eventId,
                turnId = event.requireTurnId(),
                sequence = event.sequence,
                text = payload.text,
                diagnosticCode = payload.diagnosticCode
            )
        }

        private fun acceptAttemptCreated(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            if (turn.activationKind == TurnActivationKind.LOCAL) {
                fail("LOCAL_TURN_DEVICE_ATTEMPT", event, "Local knowledge turns cannot create a device attempt")
            }
            requireCompletedRound(event)
            val attemptId = event.requireAttemptId()
            if (attempts.containsKey(attemptId)) {
                fail("DUPLICATE_ATTEMPT", event, "ExecutionAttemptCreated must be globally unique")
            }
            if (attempts.values.any { it.executionId == event.executionId }) {
                fail("DUPLICATE_EXECUTION", event, "executionId must be globally unique")
            }
            turn.activationKind = TurnActivationKind.ANDROID
            val payload = decode(event, ExecutionAttemptCreatedEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            validateAttemptSnapshot(event, payload)
            attempts[attemptId] = MutableAttempt(event, payload)
        }

        private fun acceptAttemptState(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            val attempt = requireAttempt(event)
            val payload = decode(event, ExecutionAttemptStateChangedEventPayload.serializer())
            if (!ExecutionAttemptTransitions.allows(attempt.state, payload.state)) {
                fail(
                    "INVALID_ATTEMPT_TRANSITION",
                    event,
                    "ExecutionAttempt cannot transition ${attempt.state} -> ${payload.state}"
                )
            }
            val previous = attempt.state
            attempt.state = payload.state
            attempt.stateEvents += event to payload
            val lifecycleKind = when (payload.state) {
                ExecutionAttemptState.PAUSED_USER -> LifecycleActivityKind.ATTEMPT_PAUSED_USER
                ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED -> if (
                    turn.recoveryRequired?.first?.sequence == event.sequence - 1L
                ) {
                    null
                } else {
                    LifecycleActivityKind.ATTEMPT_PAUSED_RECOVERY_REQUIRED
                }
                ExecutionAttemptState.RUNNING -> if (previous in pausedAttemptStates) {
                    LifecycleActivityKind.ATTEMPT_RESUMED
                } else {
                    null
                }
                else -> null
            }
            lifecycleKind?.let { kind ->
                lifecycle(turn.turnId) += LifecycleActivityReadModel(
                    itemId = event.eventId,
                    turnId = turn.turnId,
                    sequence = event.sequence,
                    kind = kind,
                    attemptId = event.attemptId,
                    executionId = event.executionId,
                    callId = null,
                    diagnosticCode = payload.diagnosticCode
                )
            }
        }

        private fun acceptToolCall(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            requireCompletedRound(event)
            val callId = event.requireCallId()
            if (calls.containsKey(callId)) {
                fail("DUPLICATE_TOOL_CALL", event, "Harness callId must be globally unique")
            }
            val payload = decode(event, ToolCallEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            if (payload.roundDetailRef != requireRound(event).startedPayload.roundDetailRef) {
                fail("TOOL_ROUND_DETAIL_MISMATCH", event, "ToolCall references another ModelRound detail")
            }
            validateToolScope(event, payload.binding, started = false)
            if (payload.source == ToolCallSource.MODEL_REQUESTED && payload.triggerCallId != null) {
                fail("INVALID_TRIGGER_CALL", event, "A model-requested call cannot reference a trigger")
            }
            payload.triggerCallId?.let { triggerId ->
                val trigger = calls[triggerId]
                    ?: fail("MISSING_TRIGGER_CALL", event, "Harness automatic trigger call is missing")
                if (trigger.turnId != event.turnId || trigger.event.sequence >= event.sequence) {
                    fail("INVALID_TRIGGER_CALL", event, "Harness automatic trigger must precede in the same Turn")
                }
            }
            calls[callId] = MutableCall(event, payload)
        }

        private fun acceptToolStarted(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val call = requireCall(event)
            if (call.started != null || call.rejected != null) {
                fail("DUPLICATE_TOOL_DISPOSITION", event, "ToolCall may be started or rejected exactly once")
            }
            validateToolEventScope(event, call, requireAttemptForDevice = true)
            val payload = decode(event, ToolStartedEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            if (payload.roundDetailRef != call.payload.roundDetailRef) {
                fail("TOOL_ROUND_DETAIL_MISMATCH", event, "ToolStarted changed roundDetailRef")
            }
            call.started = event
        }

        private fun acceptToolRejected(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val call = requireCall(event)
            if (call.started != null || call.rejected != null || call.finished != null) {
                fail("DUPLICATE_TOOL_DISPOSITION", event, "Rejected ToolCall cannot be started or settled")
            }
            validateToolEventScope(event, call, requireAttemptForDevice = false)
            val payload = decode(event, ToolCallRejectedEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            if (payload.roundDetailRef != call.payload.roundDetailRef) {
                fail("TOOL_ROUND_DETAIL_MISMATCH", event, "ToolCallRejected changed roundDetailRef")
            }
            call.rejected = event to payload
        }

        private fun acceptToolFinished(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val call = requireCall(event)
            if (call.started == null || call.rejected != null) {
                fail("TOOL_FINISHED_WITHOUT_START", event, "ToolFinished requires one prior ToolStarted")
            }
            if (call.finished != null) {
                fail("DUPLICATE_TOOL_FINISHED", event, "A ToolCall may settle exactly once")
            }
            validateToolEventScope(event, call, requireAttemptForDevice = true)
            val payload = decode(event, ToolFinishedEventPayload.serializer())
            if (payload.roundDetailRef != call.payload.roundDetailRef) {
                fail("TOOL_ROUND_DETAIL_MISMATCH", event, "ToolFinished changed roundDetailRef")
            }
            call.finished = event to payload
        }

        private fun acceptIntervention(event: AgentEventEnvelope) {
            requireOpenTurn(event)
            val call = requireCall(event)
            if (call.payload.effect != ToolEffect.USER_INTERVENTION || call.started == null ||
                call.rejected != null
            ) {
                fail(
                    "INVALID_USER_INTERVENTION",
                    event,
                    "UserInterventionRequested requires a started USER_INTERVENTION ToolCall"
                )
            }
            if (call.intervention != null) {
                fail("DUPLICATE_USER_INTERVENTION", event, "Intervention lifecycle must be unique")
            }
            validateToolEventScope(event, call, requireAttemptForDevice = call.payload.binding == ToolBinding.DEVICE_BOUND)
            val payload = decode(event, UserInterventionRequestedEventPayload.serializer())
            requireContractVersion(event, payload.contractVersion, MODEL_ROUND_EVENT_CONTRACT_VERSION)
            val message = messages[payload.messageId]
                ?: fail("MISSING_INTERVENTION_MESSAGE", event, "Intervention message does not exist")
            if (message.kind != MessageKind.INTERVENTION || message.threadId != event.threadId ||
                message.turnId != event.turnId || message.roundId != event.roundId
            ) {
                fail(
                    "INTERVENTION_MESSAGE_SCOPE_MISMATCH",
                    event,
                    "Intervention message belongs to another lifecycle scope"
                )
            }
            call.intervention = event to payload
            lifecycle(event.requireTurnId()) += LifecycleActivityReadModel(
                itemId = event.eventId,
                turnId = event.requireTurnId(),
                sequence = event.sequence,
                kind = LifecycleActivityKind.USER_INTERVENTION,
                attemptId = event.attemptId,
                executionId = event.executionId,
                callId = event.callId,
                diagnosticCode = null
            )
        }

        private fun acceptAttachment(event: AgentEventEnvelope) {
            val turn = requireTurn(event)
            val call = requireCall(event)
            if (call.finished == null && call.rejected == null) {
                fail("ATTACHMENT_BEFORE_SETTLEMENT", event, "Detail attachment requires a settled ToolCall")
            }
            validateToolEventScope(event, call, requireAttemptForDevice = call.started != null)
            val payload = decode(event, DetailAttachmentAddedEventPayload.serializer())
            if (calls.values.any { candidate ->
                    candidate !== call && candidate.attachments.any { it.second.attachmentId == payload.attachmentId }
                } || call.attachments.any { it.second.attachmentId == payload.attachmentId }
            ) {
                fail("DUPLICATE_ATTACHMENT", event, "attachmentId must be globally unique")
            }
            call.attachments += event to payload
            if (turn.completion != null &&
                event.sequence <= checkNotNull(turn.completion).first.sequence
            ) {
                fail("INVALID_LATE_ATTACHMENT", event, "Late attachment sequence must follow TurnCompleted")
            }
        }

        private fun acceptEpochAdvanced(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            val payload = decode(event, ModelSessionEpochAdvancedEventPayload.serializer())
            if (payload.identity != turn.modelSessionIdentity) {
                fail("MODEL_SESSION_IDENTITY_MISMATCH", event, "Epoch identity differs from the frozen Turn identity")
            }
            val previousEpoch = latestEpochByTurn[event.requireTurnId()] ?: 0L
            if (payload.previousEpoch != previousEpoch || payload.currentEpoch != previousEpoch + 1L) {
                fail("MODEL_SESSION_EPOCH_GAP", event, "ModelSession epoch chain is not contiguous")
            }
            val pendingCheckpoint = pendingCheckpointEpoch
            if (pendingCheckpoint != null) {
                val (checkpointEvent, _) = pendingCheckpoint
                if (payload.reason != EpochAdvanceReason.CONTEXT_CHECKPOINT ||
                    payload.evidenceRef != checkpointEvent.eventId ||
                    payload.diagnosticCode != null
                ) {
                    fail(
                        "CONTEXT_CHECKPOINT_EPOCH_MISMATCH",
                        event,
                        "ContextCheckpoint epoch boundary must reference its checkpoint event"
                    )
                }
                pendingCheckpointEpoch = null
            } else if (payload.reason == EpochAdvanceReason.PROCESS_RECOVERY) {
                if (turn.recoveryRequired?.first?.eventId != payload.evidenceRef ||
                    payload.diagnosticCode != AgentRecoveryDiagnosticCode.PROCESS_RECOVERY ||
                    attempts.values.any { it.turnId == turn.turnId && it.state !in terminalAttemptStates } ||
                    calls.values.any {
                        it.turnId == turn.turnId && it.started != null && it.finished == null
                    }
                ) {
                    fail("INVALID_RECOVERY_EPOCH", event, "Resume requires the current settled recovery boundary")
                }
                turn.recoveryRequired = null
                turn.lastRecoveryResumeSequence = event.sequence
            } else if (payload.reason != EpochAdvanceReason.PROVIDER_HANDLE_INVALIDATED ||
                payload.diagnosticCode.isNullOrBlank() ||
                !payload.evidenceRef.matches(Regex("^sha256:[0-9a-f]{64}$"))
            ) {
                fail(
                    "INVALID_PROVIDER_HANDLE_EPOCH",
                    event,
                    "Standalone epoch boundaries require explicit provider handle invalidation evidence"
                )
            }
            latestEpochByTurn[event.requireTurnId()] = payload.currentEpoch
            turn.modelSessionEpoch = payload.currentEpoch
        }

        private fun acceptContextCheckpoint(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            val payload = decode(event, ContextCheckpointPayload.serializer())
            try {
                ContextCheckpointValidator.validate(payload)
            } catch (error: IllegalArgumentException) {
                fail("INVALID_CONTEXT_CHECKPOINT", event, "Invalid ContextCheckpoint payload", error)
            }
            if (payload.stablePrefixHash != turn.modelSessionIdentity.stablePrefixHash) {
                fail(
                    "CONTEXT_CHECKPOINT_SESSION_MISMATCH",
                    event,
                    "ContextCheckpoint stable prefix differs from the frozen Turn identity"
                )
            }
            val previous = checkpointsByTurn[event.requireTurnId()]
            if (payload.parentCheckpointEventId != previous?.eventId) {
                fail(
                    "CONTEXT_CHECKPOINT_PARENT_MISMATCH",
                    event,
                    "ContextCheckpoint parent is not the latest durable checkpoint"
                )
            }
            if (previous != null &&
                (payload.sourceThroughSequence <= previous.payload.sourceThroughSequence ||
                    payload.contextPolicyHash != previous.payload.contextPolicyHash)
            ) {
                fail(
                    "CONTEXT_CHECKPOINT_LINEAGE_MISMATCH",
                    event,
                    "ContextCheckpoint does not advance the frozen projection"
                )
            }
            val record = ContextCheckpointRecord(event.eventId, payload)
            checkpointsByTurn[event.requireTurnId()] = record
            pendingCheckpointEpoch = event to record
        }

        private fun acceptTurnCompleted(event: AgentEventEnvelope) {
            val turn = requireOpenTurn(event)
            val payload = decode(event, TurnCompletedEventPayload.serializer())
            val incompleteCall = calls.values.firstOrNull {
                it.turnId == turn.turnId && (it.started == null && it.rejected == null ||
                    it.started != null && it.finished == null)
            }
            if (incompleteCall != null) {
                fail(
                    "OPEN_TOOL_AT_TURN_COMPLETION",
                    event,
                    "ToolCall ${incompleteCall.callId} is not settled"
                )
            }
            val openAttempt = attempts.values.firstOrNull {
                it.turnId == turn.turnId && it.state !in terminalAttemptStates
            }
            if (openAttempt != null) {
                fail(
                    "OPEN_ATTEMPT_AT_TURN_COMPLETION",
                    event,
                    "ExecutionAttempt ${openAttempt.attemptId} is not terminal"
                )
            }
            turn.completion = event to payload
            conversation += ConversationEntryReadModel(
                itemId = event.eventId,
                sourceEventId = event.eventId,
                threadId = event.threadId,
                turnId = event.requireTurnId(),
                sequence = event.sequence,
                occurredAt = event.occurredAt,
                kind = if (payload.assistantReply != null) {
                    ConversationEntryKind.TURN_ASSISTANT_REPLY
                } else {
                    ConversationEntryKind.TURN_SYSTEM_NOTICE
                },
                text = payload.assistantReply ?: checkNotNull(payload.systemNotice),
                diagnosticCode = payload.diagnosticCode
            )
        }

        fun finish(): AgentThreadReadModel {
            pendingCheckpointEpoch?.let { (checkpointEvent, _) ->
                fail(
                    "UNPAIRED_CONTEXT_CHECKPOINT",
                    checkpointEvent,
                    "ContextCheckpoint is missing its ModelSession epoch boundary"
                )
            }
            val currentThread = thread
                ?: fail("MISSING_THREAD", null, "AgentEvent stream has no ThreadCreated")
            calls.values.forEach { call ->
                if (call.started == null && call.rejected == null) {
                    fail(
                        "MISSING_TOOL_DISPOSITION",
                        call.event,
                        "ToolCall ${call.callId} has no started or rejected disposition"
                    )
                }
            }
            messages.values.filter { it.kind == MessageKind.INTERVENTION }.forEach { message ->
                val roundCalls = calls.values.filter { call ->
                    call.threadId == message.threadId &&
                        call.turnId == message.turnId &&
                        call.roundId == message.roundId &&
                        call.payload.effect == ToolEffect.USER_INTERVENTION
                }
                val hasLifecycleRequest = roundCalls.any {
                    it.intervention?.second?.messageId == message.messageId
                }
                val wasRejectedBeforeExecution = roundCalls.isNotEmpty() && roundCalls.all {
                    it.started == null && it.rejected != null
                }
                if (!hasLifecycleRequest && !wasRejectedBeforeExecution) {
                    fail(
                        "ORPHAN_INTERVENTION_MESSAGE",
                        message.event,
                        "USER_INTERVENTION AssistantMessage has no lifecycle request"
                    )
                }
            }

            val progressByCall = projectProgressAttachments()
            val attachmentSources = calls.values.asSequence().flatMap { it.attachments.asSequence() }
                .associateBy { it.second.attachmentId }
            val toolDetails = calls.values.associate { call ->
                call.callId to call.toDetail(progressByCall[call.callId].orEmpty(), attachmentSources)
            }
            val resultCards = calls.values.mapNotNull { it.toResultCard() }
                .sortedBy(ResultCardReadModel::sequence)
            val turnModels = turns.values.map { turn -> turn.toReadModel() }
            val activity = turns.values.associate { turn ->
                turn.turnId to turn.toActivity(progressByCall)
            }
            val roundDetails = rounds.values.mapNotNull { it.toReadModel() }
                .associateBy(ModelRoundDetailReadModel::roundId)
            val conversationModel = ConversationReadModel(
                threadId = threadId,
                entries = immutableList(conversation.sortedBy { it.sequence })
            )
            return AgentThreadReadModel(
                metadata = ThreadMetadataReadModel(
                    threadId = threadId,
                    title = currentThread.title,
                    summary = currentThread.summary,
                    pinned = currentThread.pinned,
                    tombstoned = currentThread.tombstoned,
                    createdSequence = currentThread.createdSequence,
                    createdAt = currentThread.createdAt,
                    lastActivitySequence = currentThread.lastActivitySequence,
                    lastActivityAt = currentThread.lastActivityAt
                ),
                turns = immutableList(turnModels),
                conversation = conversationModel,
                activityByTurn = immutableMap(activity),
                toolDetails = immutableMap(toolDetails),
                resultCards = immutableList(resultCards),
                roundDetails = immutableMap(roundDetails),
                completeRecord = CompleteRecordReadModel(
                    threadId = threadId,
                    sourceEventIds = immutableList(sourceEvents.map { it.eventId }),
                    events = immutableList(sourceEvents)
                )
            )
        }

        private fun MutableTurn.toReadModel(): TurnReadModel {
            val activeAttempt = attempts.values
                .filter { it.turnId == turnId && it.state !in terminalAttemptStates }
                .maxByOrNull { it.event.sequence }
            return TurnReadModel(
                threadId = threadId,
                turnId = turnId,
                modelSessionIdentity = modelSessionIdentity,
                providerConfigSnapshotRef = providerConfigSnapshotRef,
                modelSessionEpoch = modelSessionEpoch,
                state = projectedTurnState(this),
                activeAttemptId = activeAttempt?.attemptId,
                terminalStatus = completion?.second?.status,
                startedAt = event.occurredAt,
                completedAt = completion?.first?.occurredAt,
                activationKind = activationKind
            )
        }

        private fun MutableTurn.toActivity(
            progressByCall: Map<String, List<String>>
        ): TurnActivityReadModel {
            val callsByRound = calls.values.filter { it.turnId == turnId }.groupBy { it.roundId }
            val roundTimings = rounds.values.filter { it.turnId == turnId }.associate { round ->
                val roundCalls = callsByRound[round.roundId].orEmpty()
                val settledAt = round.completed?.occurredAt?.takeIf {
                    roundCalls.all { it.finished != null || it.rejected != null }
                }?.let { modelFinishedAt ->
                    maxOf(modelFinishedAt, roundCalls.maxOfOrNull {
                        (it.finished?.first ?: checkNotNull(it.rejected).first).occurredAt
                    } ?: modelFinishedAt)
                }
                round.roundId to ExecutionRoundTimingReadModel(
                    roundId = round.roundId,
                    startedAt = round.started.occurredAt,
                    finishedAt = settledAt ?: completion?.first?.occurredAt
                )
            }
            val toolEntries = calls.values
                .filter { it.turnId == turnId && (it.started != null || it.rejected != null) }
                .map { call -> call.toActivity(progressByCall[call.callId].orEmpty()) }
            val lifecycleEntries = lifecycleByTurn[turnId].orEmpty()
            val hasUserIntervention = lifecycleEntries.any {
                it is LifecycleActivityReadModel &&
                    it.kind == LifecycleActivityKind.USER_INTERVENTION
            }
            val normalizedLifecycleEntries = if (hasUserIntervention) {
                lifecycleEntries.filterNot {
                    it is LifecycleActivityReadModel && it.kind in setOf(
                        LifecycleActivityKind.ATTEMPT_PAUSED_USER,
                        LifecycleActivityKind.ATTEMPT_RESUMED
                    )
                }
            } else {
                lifecycleEntries
            }
            val entries = (toolEntries + normalizedLifecycleEntries)
                .sortedBy(ActivityEntryReadModel::sequence)
            val visiblePending = toolEntries
                .filter { it.visible && it.state == ToolActivityState.PENDING }
                .maxByOrNull { it.sequence }
            val stageSequences = conversation.filter {
                it.turnId == turnId && it.kind == ConversationEntryKind.ASSISTANT_STAGE_RESULT
            }.map { it.sequence }
            val progressHistory = rounds.values
                .filter { it.turnId == turnId }
                .mapNotNull { round -> round.progress?.let { (event, progress) ->
                    PublicProgressReadModel(event.eventId, event.sequence, joinPublicBlocks(progress.publicBlocks),
                        listOf(round.roundId))
                } }
                .sortedBy { it.sequence }
                .fold(emptyList<PublicProgressReadModel>()) { accumulated, next ->
                    val previous = accumulated.lastOrNull()
                    if (previous != null && previous.text.normalizedWhitespace() == next.text.normalizedWhitespace() &&
                        stageSequences.none { it > previous.sequence && it < next.sequence }
                    ) {
                        accumulated.dropLast(1) + accumulated.last().copy(
                            roundIds = immutableList(accumulated.last().roundIds + next.roundIds)
                        )
                    } else {
                        accumulated + next
                    }
                }
            return TurnActivityReadModel(
                turnId = turnId,
                state = projectedTurnState(this),
                entries = immutableList(entries),
                operationCount = toolEntries.count(ToolActivityReadModel::operationCounted),
                currentLabelResourceKey = visiblePending?.presentation?.labelResourceKey,
                publicProgressHistory = immutableList(progressHistory),
                startedAt = event.occurredAt,
                completedAt = completion?.first?.occurredAt,
                totalDurationMillis = completion?.second?.totalDurationMillis,
                activeDurationMillis = completion?.second?.activeDurationMillis,
                executionRoundTimings = immutableMap(roundTimings),
                programShellCalls = immutableList(ProgramShellCallProjector.project(programEventsByTurn[turnId].orEmpty(), turnId))
            )
        }

        private fun MutableCall.toActivity(publicProgressBlocks: List<String>): ToolActivityReadModel {
            val result = finished?.second?.result ?: rejected?.second?.result
            val state = when {
                rejected != null -> ToolActivityState.REJECTED
                result == null -> ToolActivityState.PENDING
                result.isVerifiedSuccess -> ToolActivityState.VERIFIED
                result.executionStatus == ToolExecutionStatus.FAILED -> ToolActivityState.FAILED
                else -> ToolActivityState.UNVERIFIED
            }
            val visible = when {
                payload.effect == ToolEffect.USER_INTERVENTION -> false
                payload.presentation.category == ToolPresentationCategory.COMPLETION -> false
                payload.presentation.category == ToolPresentationCategory.DIAGNOSTIC &&
                    payload.binding == ToolBinding.TURN_LOCAL -> false
                payload.source == ToolCallSource.MODEL_REQUESTED -> true
                result == null -> false
                else -> !result.isVerifiedSuccess
            }
            return ToolActivityReadModel(
                itemId = callId,
                turnId = turnId,
                sequence = started?.sequence ?: checkNotNull(rejected).first.sequence,
                visible = visible,
                roundId = roundId,
                callId = callId,
                attemptId = event.attemptId,
                executionId = event.executionId,
                source = payload.source,
                presentation = payload.presentation,
                state = state,
                diagnosticCode = result?.diagnosticCode,
                publicProgress = publicProgressBlocks.takeIf(List<String>::isNotEmpty)
                    ?.let(::joinPublicBlocks),
                operationCounted = payload.source == ToolCallSource.MODEL_REQUESTED &&
                    (payload.effect == ToolEffect.READ_ONLY || payload.effect == ToolEffect.STATE_CHANGING) &&
                    started != null && finished != null &&
                    result?.executionStatus != ToolExecutionStatus.NOT_EXECUTED,
                startedAt = started?.occurredAt,
                finishedAt = finished?.first?.occurredAt
            )
        }

        private fun MutableCall.toDetail(publicProgressBlocks: List<String>,
            attachmentSources: Map<String, Pair<AgentEventEnvelope, DetailAttachmentAddedEventPayload>>): ToolDetailReadModel {
            val attempt = event.attemptId?.let(attempts::get)
            val result = finished?.second?.result ?: rejected?.second?.result
            val batchImages = result?.batchScreenshots.orEmpty().associateBy { it.attachment.attachmentId }
            return ToolDetailReadModel(
                threadId = threadId,
                turnId = turnId,
                roundId = roundId,
                callId = callId,
                providerCallId = payload.providerCallId,
                providerOrder = payload.providerOrder,
                technicalName = payload.name,
                argumentsJson = redactArguments(payload.argumentsJson, payload.presentation.sensitiveFieldPaths),
                commandSource = when (payload.name) {
                    "shell" -> runCatching {
                        ((Json.parseToJsonElement(payload.argumentsJson) as? JsonObject)?.get("command") as? JsonPrimitive)?.content
                    }.getOrNull()
                    "program" -> runCatching {
                        val args = Json.parseToJsonElement(payload.argumentsJson) as? JsonObject
                        (args?.get("code") as? JsonPrimitive)?.content
                            ?: (args?.get("path") as? JsonPrimitive)?.content
                            ?: "${payload.name} ${payload.argumentsJson}"
                    }.getOrNull()
                    "workspace" -> "${payload.name} ${payload.argumentsJson}"
                    else -> null
                },
                source = payload.source,
                binding = payload.binding,
                effect = payload.effect,
                presentation = payload.presentation,
                roundDetailRef = payload.roundDetailRef,
                attemptId = event.attemptId,
                executionId = event.executionId,
                backendSessionId = attempt?.payload?.context?.backendSessionId,
                displayId = attempt?.payload?.context?.displayId,
                outputText = result?.outputText,
                executionStatus = result?.executionStatus,
                evidenceStatus = result?.evidenceStatus,
                actionEvidence = result?.actionEvidence,
                observationId = result?.observationId,
                diagnosticCode = result?.diagnosticCode,
                metrics = immutableMap(result?.metrics.orEmpty()),
                publicProgressBlocks = immutableList(publicProgressBlocks),
                startedAt = started?.occurredAt,
                finishedAt = finished?.first?.occurredAt,
                exclusiveDurationMillis = finished?.second?.exclusiveDurationMillis,
                attachments = immutableList(attachments.map { (attachmentEvent, attachment) ->
                    DetailAttachmentReadModel(
                        attachmentId = attachment.attachmentId,
                        eventId = attachmentEvent.eventId,
                        sequence = attachmentEvent.sequence,
                        role = attachment.role,
                        contentRef = attachment.contentRef,
                        contentHash = attachment.contentHash,
                        sizeBytes = attachment.sizeBytes,
                        mimeType = attachment.mimeType,
                        width = attachment.width,
                        height = attachment.height,
                        observationId = attachment.observationId,
                        historical = result?.historicalAttachment?.contentRef == attachment.contentRef,
                        sourceOccurredAt = result?.historicalAttachment?.takeIf { it.contentRef == attachment.contentRef }
                            ?.let { source -> attachmentSources[source.attachmentId]
                                ?.takeIf { (event, original) -> event.sequence < attachmentEvent.sequence &&
                                    original.contentRef == source.contentRef }
                                ?.first?.occurredAt },
                        modelContentRef = attachment.modelContentRef,
                        modelSizeBytes = attachment.modelSizeBytes,
                        batchActionIndex = batchImages[attachment.attachmentId]
                            ?.takeIf { it.attachment.contentRef == attachment.contentRef }?.actionIndex
                    )
                }),
                triggerCallId = payload.triggerCallId
            )
        }

        private fun MutableCall.toResultCard(): ResultCardReadModel? {
            if (payload.name != ResultCardToolArguments.TOOL_NAME) return null
            val result = finished?.second?.result ?: return null
            if (!result.isVerifiedSuccess) return null
            // 结果卡片只是展示数据：参数不合法时跳过该卡片，而不是让整个线程投影失败。
            // 否则历史里任何一条参数不完整的 present_result_card 都会让后续任务在
            // 回放该线程时启动即崩（曾表现为通知接管任务 44ms 内自动终止）。
            val card = try {
                ResultCardToolArguments.parse(payload.argumentsJson)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return ResultCardReadModel(
                threadId = threadId,
                turnId = turnId,
                callId = callId,
                sequence = event.sequence,
                title = card.title,
                items = immutableList(card.items.map {
                    ResultCardItemReadModel(it.text, it.deeplink)
                })
            )
        }

        private fun MutableRound.toReadModel(): ModelRoundDetailReadModel? {
            val completedEvent = completed ?: return null
            val payload = completedPayload ?: return null
            return ModelRoundDetailReadModel(
                threadId = threadId,
                turnId = turnId,
                roundId = roundId,
                roundDetailRef = payload.roundDetailRef,
                planFingerprint = payload.planFingerprint,
                submittedItems = payload.submittedItems,
                providerAckSequence = payload.providerAckSequence,
                publicBlocks = immutableList(payload.publicBlocks),
                publicMessageKind = payload.publicMessageKind,
                finishMetadata = immutableMap(payload.finishMetadata.toSortedMap()),
                completedAt = completedEvent.occurredAt,
                sourceEventId = completedEvent.eventId
            )
        }

        private fun projectProgressAttachments(): Map<String, List<String>> {
            val attached = linkedMapOf<String, List<String>>()
            rounds.values.forEach { round ->
                val progress = round.progress ?: return@forEach
                val nextCall = calls.values
                    .asSequence()
                    .filter { it.roundId == round.roundId }
                    .filter { it.payload.source == ToolCallSource.MODEL_REQUESTED }
                    .filter { it.started != null && checkNotNull(it.started).sequence > progress.first.sequence }
                    .minByOrNull { checkNotNull(it.started).sequence }
                nextCall?.let { attached[it.callId] = progress.second.publicBlocks.toList() }
            }
            return attached
        }

        private fun projectedTurnState(turn: MutableTurn): TurnReadModelState {
            if (turn.completion != null) return TurnReadModelState.TERMINAL
            val latestIntervention = calls.values
                .filter { it.turnId == turn.turnId }
                .mapNotNull { call ->
                    val request = call.intervention?.first ?: return@mapNotNull null
                    val finished = call.finished ?: return@mapNotNull null
                    if (!finished.second.result.isVerifiedSuccess) return@mapNotNull null
                    maxOf(request.sequence, finished.first.sequence)
                }
                .maxOrNull()
            val latestAttemptResume = attempts.values
                .filter { it.turnId == turn.turnId }
                .flatMap { it.stateEvents }
                .filter { it.second.state == ExecutionAttemptState.RUNNING }
                .maxOfOrNull { it.first.sequence }
            val latestResume = listOfNotNull(latestAttemptResume, turn.lastRecoveryResumeSequence).maxOrNull()
            val recoverySequence = turn.recoveryRequired?.first?.sequence
            if (recoverySequence != null && (latestResume == null || recoverySequence > latestResume)) {
                return TurnReadModelState.PAUSED_RECOVERY_REQUIRED
            }
            if (latestIntervention != null && (latestResume == null || latestIntervention > latestResume)) {
                return TurnReadModelState.WAITING_FOR_USER
            }
            val latestAttempt = attempts.values
                .filter { it.turnId == turn.turnId && it.state !in terminalAttemptStates }
                .maxByOrNull { it.event.sequence }
            return latestAttempt?.state?.let(::attemptStateToTurnState) ?: TurnReadModelState.RUNNING
        }

        private fun requireThread(event: AgentEventEnvelope): MutableThread = thread
            ?: fail("MISSING_THREAD", event, "${event.eventKind} requires a preceding ThreadCreated")

        private fun requireTurn(event: AgentEventEnvelope): MutableTurn {
            requireThread(event)
            return turns[event.requireTurnId()]
                ?: fail("MISSING_TURN", event, "${event.eventKind} requires a preceding TurnStarted")
        }

        private fun requireOpenTurn(event: AgentEventEnvelope): MutableTurn {
            val turn = requireTurn(event)
            if (turn.completion != null) {
                fail("TURN_ALREADY_TERMINAL", event, "Turn ${turn.turnId} is already terminal")
            }
            return turn
        }

        private fun requireRound(event: AgentEventEnvelope): MutableRound {
            val round = rounds[event.requireRoundId()]
                ?: fail("MISSING_MODEL_ROUND", event, "${event.eventKind} requires ModelRoundStarted")
            if (round.threadId != event.threadId || round.turnId != event.turnId) {
                fail("MODEL_ROUND_SCOPE_MISMATCH", event, "ModelRound belongs to another scope")
            }
            return round
        }

        private fun requireCompletedRound(event: AgentEventEnvelope): MutableRound {
            val round = requireRound(event)
            if (round.completed == null) {
                fail("MODEL_ROUND_NOT_COMPLETED", event, "${event.eventKind} requires ModelRoundCompleted")
            }
            return round
        }

        private fun requireCall(event: AgentEventEnvelope): MutableCall {
            val call = calls[event.requireCallId()]
                ?: fail("MISSING_TOOL_CALL", event, "${event.eventKind} requires ToolCall")
            if (call.threadId != event.threadId || call.turnId != event.turnId ||
                call.roundId != event.roundId
            ) {
                fail("TOOL_CALL_SCOPE_MISMATCH", event, "Tool lifecycle crossed its frozen scope")
            }
            return call
        }

        private fun requireAttempt(event: AgentEventEnvelope): MutableAttempt {
            val attempt = attempts[event.requireAttemptId()]
                ?: fail("MISSING_ATTEMPT", event, "${event.eventKind} requires ExecutionAttemptCreated")
            if (attempt.threadId != event.threadId || attempt.turnId != event.turnId ||
                attempt.executionId != event.executionId
            ) {
                fail("ATTEMPT_SCOPE_MISMATCH", event, "Attempt lifecycle crossed its frozen scope")
            }
            return attempt
        }

        private fun registerMessage(messageId: String, event: AgentEventEnvelope, kind: MessageKind) {
            if (messages.containsKey(messageId)) {
                fail("DUPLICATE_MESSAGE_ID", event, "messageId must be globally unique")
            }
            messages[messageId] = MessageScope(
                messageId = messageId,
                event = event,
                kind = kind,
                threadId = event.threadId,
                turnId = event.requireTurnId(),
                roundId = event.roundId
            )
        }

        private fun validateToolEventScope(
            event: AgentEventEnvelope,
            call: MutableCall,
            requireAttemptForDevice: Boolean
        ) {
            if (event.attemptId != call.event.attemptId || event.executionId != call.event.executionId) {
                fail("TOOL_ATTEMPT_SCOPE_MISMATCH", event, "Tool lifecycle changed attempt scope")
            }
            validateToolScope(event, call.payload.binding, started = requireAttemptForDevice)
            if (event.attemptId != null) requireAttempt(event)
        }

        private fun lifecycle(turnId: String): MutableList<ActivityEntryReadModel> =
            lifecycleByTurn.getOrPut(turnId) { mutableListOf() }
    }

    private fun validateEnvelopeStream(events: List<AgentEventEnvelope>) {
        val threadId = events.first().threadId
        val eventIds = mutableSetOf<String>()
        val idempotencyKeys = mutableSetOf<String>()
        var previousSequence = 0L
        events.forEach { event ->
            if (event.schemaVersion != AGENT_EVENT_SCHEMA_VERSION) {
                fail("UNSUPPORTED_SCHEMA", event, "Unsupported AgentEvent schemaVersion")
            }
            if (event.eventId.isBlank() || !eventIds.add(event.eventId)) {
                fail("DUPLICATE_EVENT_ID", event, "eventId must be non-blank and globally unique")
            }
            if (event.idempotencyKey.isBlank() || !idempotencyKeys.add(event.idempotencyKey)) {
                fail("DUPLICATE_IDEMPOTENCY_KEY", event, "idempotencyKey must be non-blank and unique")
            }
            if (event.sequence <= previousSequence) {
                fail("NON_MONOTONIC_SEQUENCE", event, "Input sequence must be strictly increasing")
            }
            if (event.sequence <= 0L || event.occurredAt < 0L) {
                fail("INVALID_EVENT_CLOCK", event, "Event sequence/time cannot be negative")
            }
            previousSequence = event.sequence
            if (event.threadId.isBlank() || event.threadId != threadId) {
                fail("MULTIPLE_THREADS", event, "A Thread projection accepts exactly one threadId")
            }
            validateScope(event)
            val canonical = try {
                canonicalizeJson(event.payloadJson)
            } catch (error: IllegalArgumentException) {
                fail("INVALID_PAYLOAD_JSON", event, "Event payload must be valid JSON", error)
            }
            if (canonical != event.payloadJson || sha256Hex(canonical) != event.payloadHash) {
                fail("PAYLOAD_INTEGRITY_FAILURE", event, "Event payload canonical form/hash is invalid")
            }
        }
        if (events.first().eventKind != AgentEventKind.THREAD_CREATED) {
            fail("MISSING_THREAD_PREFIX", events.first(), "ThreadCreated must be the first Thread event")
        }
    }

    private fun validateScope(event: AgentEventEnvelope) {
        val rule = scopeRules.getValue(event.eventKind)
        validateRequired(event, "turnId", event.turnId, rule.turn)
        validateRequired(event, "roundId", event.roundId, rule.round)
        validateRequired(event, "attemptId", event.attemptId, rule.attempt)
        validateRequired(event, "executionId", event.executionId, rule.execution)
        validateRequired(event, "callId", event.callId, rule.call)
        if (event.attemptId.isNullOrBlank() != event.executionId.isNullOrBlank()) {
            fail("PARTIAL_ATTEMPT_SCOPE", event, "attemptId and executionId must appear together")
        }
    }

    private fun validateRequired(
        event: AgentEventEnvelope,
        field: String,
        value: String?,
        requirement: ScopeRequirement
    ) {
        when (requirement) {
            ScopeRequirement.REQUIRED -> if (value.isNullOrBlank()) {
                fail("MISSING_SCOPE", event, "${event.eventKind} requires $field")
            }
            ScopeRequirement.FORBIDDEN -> if (value != null) {
                fail("EXTRA_SCOPE", event, "${event.eventKind} forbids $field")
            }
            ScopeRequirement.OPTIONAL -> if (value != null && value.isBlank()) {
                fail("BLANK_SCOPE", event, "$field cannot be blank")
            }
        }
    }

    private fun validateAttemptSnapshot(
        event: AgentEventEnvelope,
        payload: ExecutionAttemptCreatedEventPayload
    ) {
        val context = payload.context
        val capability = payload.capabilitySnapshot
        if (context.executionId != event.executionId || context.executionId.isBlank() ||
            context.backendSessionId.isBlank() || context.cancellationScopeId.isBlank() ||
            context.displayId < 0 || context.capabilitySnapshotRef.isBlank() ||
            !context.capabilitySnapshotHash.isSha256()
        ) {
            fail("INVALID_EXECUTION_CONTEXT", event, "ExecutionContext snapshot is invalid")
        }
        if (capability.capabilitySnapshotRef != context.capabilitySnapshotRef ||
            capability.capabilitySnapshotHash != context.capabilitySnapshotHash ||
            capability.runtimeMode != context.runtimeMode ||
            capability.executionSpace != context.executionSpace ||
            capability.displayId != context.displayId ||
            capability.backendSessionId != context.backendSessionId ||
            !capability.backendAvailable
        ) {
            fail(
                "CAPABILITY_CONTEXT_MISMATCH",
                event,
                "Capability snapshot does not match frozen ExecutionContext"
            )
        }
    }

    private fun validateToolScope(
        event: AgentEventEnvelope,
        binding: ToolBinding,
        started: Boolean
    ) {
        when (binding) {
            ToolBinding.TURN_LOCAL -> if (event.attemptId != null || event.executionId != null) {
                fail("TURN_LOCAL_ATTEMPT_SCOPE", event, "TURN_LOCAL ToolCall cannot fabricate an attempt")
            }
            ToolBinding.DEVICE_BOUND -> if (started && (event.attemptId == null || event.executionId == null)) {
                fail("DEVICE_TOOL_MISSING_ATTEMPT", event, "Started DEVICE_BOUND ToolCall requires an attempt")
            }
        }
    }

    private fun requirePublicEventMatchesRound(
        event: AgentEventEnvelope,
        round: MutableRound,
        kind: PublicMessageKind,
        blocks: List<String>,
        roundDetailRef: String
    ) {
        val completed = checkNotNull(round.completedPayload)
        if (completed.publicMessageKind != kind || completed.publicBlocks != blocks ||
            completed.roundDetailRef != roundDetailRef
        ) {
            fail(
                "PUBLIC_EVENT_ROUND_MISMATCH",
                event,
                "Public event must reference the canonical ModelRound public output"
            )
        }
    }

    private fun requirePublicBlocks(
        event: AgentEventEnvelope,
        blocks: List<String>,
        allowEmpty: Boolean
    ) {
        if ((!allowEmpty && blocks.isEmpty()) || blocks.any(String::isBlank)) {
            fail("INVALID_PUBLIC_BLOCKS", event, "Public output blocks cannot be blank")
        }
    }

    private fun requireContractVersion(event: AgentEventEnvelope, actual: Int, expected: Int) {
        if (actual != expected) {
            fail("UNSUPPORTED_PAYLOAD_CONTRACT", event, "Unsupported payload contractVersion $actual")
        }
    }

    private fun <T> decode(event: AgentEventEnvelope, serializer: KSerializer<T>): T = try {
        json.decodeFromString(serializer, event.payloadJson)
    } catch (error: IllegalArgumentException) {
        fail("INVALID_TYPED_PAYLOAD", event, "Invalid ${event.eventKind} payload", error)
    }

    private fun AgentEventEnvelope.requireTurnId(): String = turnId
        ?: fail("MISSING_SCOPE", this, "$eventKind requires turnId")

    private fun AgentEventEnvelope.requireRoundId(): String = roundId
        ?: fail("MISSING_SCOPE", this, "$eventKind requires roundId")

    private fun AgentEventEnvelope.requireAttemptId(): String = attemptId
        ?: fail("MISSING_SCOPE", this, "$eventKind requires attemptId")

    private fun AgentEventEnvelope.requireCallId(): String = callId
        ?: fail("MISSING_SCOPE", this, "$eventKind requires callId")

    private data class MutableThread(
        var title: String,
        var summary: String?,
        var pinned: Boolean,
        var tombstoned: Boolean,
        val createdSequence: Long,
        val createdAt: Long,
        var lastActivitySequence: Long,
        var lastActivityAt: Long
    )

    private class MutableTurn(
        val event: AgentEventEnvelope,
        val payload: TurnStartedEventPayload
    ) {
        val turnId: String = checkNotNull(event.turnId)
        var userMessageSeen: Boolean = false
        var startMessageSeen: Boolean = false
        var recoveryRequired: Pair<AgentEventEnvelope, TurnRecoveryRequiredEventPayload>? = null
        var lastRecoveryResumeSequence: Long? = null
        var completion: Pair<AgentEventEnvelope, TurnCompletedEventPayload>? = null
        var modelSessionIdentity: ModelSessionIdentity = payload.modelSessionIdentity
        var providerConfigSnapshotRef: String = payload.providerConfigSnapshotRef
        var modelSessionEpoch: Long = 0L
        var activationKind: TurnActivationKind = TurnActivationKind.DIRECT
    }

    private class MutableRound(
        val started: AgentEventEnvelope,
        val startedPayload: ModelRoundStartedEventPayload
    ) {
        val threadId: String = started.threadId
        val turnId: String = checkNotNull(started.turnId)
        val roundId: String = checkNotNull(started.roundId)
        var completed: AgentEventEnvelope? = null
        var completedPayload: ModelRoundCompletedEventPayload? = null
        var openingCommitted: Boolean = false
        var progress: Pair<AgentEventEnvelope, PublicProgressEventPayload>? = null
    }

    private class MutableAttempt(
        val event: AgentEventEnvelope,
        val payload: ExecutionAttemptCreatedEventPayload
    ) {
        val threadId: String = event.threadId
        val turnId: String = checkNotNull(event.turnId)
        val attemptId: String = checkNotNull(event.attemptId)
        val executionId: String = checkNotNull(event.executionId)
        var state: ExecutionAttemptState = payload.state
        val stateEvents = mutableListOf<Pair<AgentEventEnvelope, ExecutionAttemptStateChangedEventPayload>>()
    }

    private class MutableCall(
        val event: AgentEventEnvelope,
        val payload: ToolCallEventPayload
    ) {
        val threadId: String = event.threadId
        val turnId: String = checkNotNull(event.turnId)
        val roundId: String = checkNotNull(event.roundId)
        val callId: String = checkNotNull(event.callId)
        var started: AgentEventEnvelope? = null
        var rejected: Pair<AgentEventEnvelope, ToolCallRejectedEventPayload>? = null
        var finished: Pair<AgentEventEnvelope, ToolFinishedEventPayload>? = null
        var intervention: Pair<AgentEventEnvelope, UserInterventionRequestedEventPayload>? = null
        val attachments = mutableListOf<Pair<AgentEventEnvelope, DetailAttachmentAddedEventPayload>>()
    }

    private enum class MessageKind { USER, USER_STEER, START, STAGE_RESULT, INTERVENTION, NOTICE }

    private data class MessageScope(
        val messageId: String,
        val event: AgentEventEnvelope,
        val kind: MessageKind,
        val threadId: String,
        val turnId: String,
        val roundId: String?
    )

    private enum class ScopeRequirement { REQUIRED, OPTIONAL, FORBIDDEN }

    private data class ScopeRule(
        val turn: ScopeRequirement,
        val round: ScopeRequirement,
        val attempt: ScopeRequirement,
        val execution: ScopeRequirement,
        val call: ScopeRequirement
    )

    private val threadOnly = ScopeRule(
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN
    )
    private val turnOnly = ScopeRule(
        ScopeRequirement.REQUIRED,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN
    )
    private val roundOnly = ScopeRule(
        ScopeRequirement.REQUIRED,
        ScopeRequirement.REQUIRED,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN,
        ScopeRequirement.FORBIDDEN
    )
    private val callWithOptionalAttempt = ScopeRule(
        ScopeRequirement.REQUIRED,
        ScopeRequirement.REQUIRED,
        ScopeRequirement.OPTIONAL,
        ScopeRequirement.OPTIONAL,
        ScopeRequirement.REQUIRED
    )

    private val scopeRules = mapOf(
        AgentEventKind.THREAD_CREATED to threadOnly,
        AgentEventKind.THREAD_METADATA_CHANGED to threadOnly,
        AgentEventKind.THREAD_PIN_CHANGED to threadOnly,
        AgentEventKind.THREAD_TOMBSTONED to threadOnly,
        AgentEventKind.TURN_STARTED to turnOnly,
        AgentEventKind.MODEL_SESSION_ACTIVATED to turnOnly,
        AgentEventKind.MODEL_LOCAL_SESSION_ACTIVATED to turnOnly,
        AgentEventKind.TURN_RECOVERY_REQUIRED to turnOnly,
        AgentEventKind.USER_MESSAGE to turnOnly,
        AgentEventKind.USER_STEER to turnOnly,
        AgentEventKind.MEMORY_CONTEXT_LOADED to turnOnly,
        AgentEventKind.PROGRAM_EXECUTION to turnOnly,
        AgentEventKind.WORKSPACE_FILE_WRITTEN to turnOnly,
        AgentEventKind.WORKSPACE_FOCUS_CHANGED to turnOnly,
        AgentEventKind.MODEL_ROUND_STARTED to roundOnly,
        AgentEventKind.MODEL_ROUND_COMPLETED to roundOnly,
        AgentEventKind.ASSISTANT_MESSAGE to roundOnly,
        AgentEventKind.PUBLIC_PROGRESS to roundOnly,
        AgentEventKind.SYSTEM_NOTICE to turnOnly,
        AgentEventKind.EXECUTION_ATTEMPT_CREATED to ScopeRule(
            ScopeRequirement.REQUIRED,
            ScopeRequirement.REQUIRED,
            ScopeRequirement.REQUIRED,
            ScopeRequirement.REQUIRED,
            ScopeRequirement.FORBIDDEN
        ),
        AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED to ScopeRule(
            ScopeRequirement.REQUIRED,
            ScopeRequirement.FORBIDDEN,
            ScopeRequirement.REQUIRED,
            ScopeRequirement.REQUIRED,
            ScopeRequirement.FORBIDDEN
        ),
        AgentEventKind.TOOL_CALL to callWithOptionalAttempt,
        AgentEventKind.TOOL_CALL_REJECTED to callWithOptionalAttempt,
        AgentEventKind.TOOL_STARTED to callWithOptionalAttempt,
        AgentEventKind.TOOL_FINISHED to callWithOptionalAttempt,
        AgentEventKind.USER_INTERVENTION_REQUESTED to callWithOptionalAttempt,
        AgentEventKind.DETAIL_ATTACHMENT_ADDED to callWithOptionalAttempt,
        AgentEventKind.CONTEXT_CHECKPOINT to turnOnly,
        AgentEventKind.MODEL_SESSION_EPOCH_ADVANCED to turnOnly,
        AgentEventKind.TURN_COMPLETED to turnOnly
    )

    private val pausedAttemptStates = setOf(
        ExecutionAttemptState.PAUSED_USER,
        ExecutionAttemptState.PAUSED_RECOVERY_REQUIRED
    )
    private val terminalAttemptStates = ExecutionAttemptTransitions.terminalStates

    private fun redactArguments(raw: String, paths: List<String>): String {
        if (paths.isEmpty()) return raw
        return try {
            var root = Json.parseToJsonElement(raw)
            paths.sorted().forEach { rawPath ->
                val tokens = rawPath.split('.').filter(String::isNotBlank).let { parts ->
                    if (parts.firstOrNull() == "arguments") parts.drop(1) else parts
                }
                require(tokens.isNotEmpty())
                root = redactAt(root, tokens)
            }
            Json.encodeToString(JsonElement.serializer(), root)
        } catch (_: IllegalArgumentException) {
            Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive("<redacted>"))
        }
    }

    private fun redactAt(element: JsonElement, tokens: List<String>): JsonElement {
        val head = tokens.first()
        return when (element) {
            is JsonObject -> {
                if (!element.containsKey(head)) return element
                JsonObject(element.mapValues { (key, value) ->
                    if (key != head) value else if (tokens.size == 1) {
                        JsonPrimitive("<redacted>")
                    } else {
                        redactAt(value, tokens.drop(1))
                    }
                })
            }
            is JsonArray -> {
                val index = head.toInt()
                if (index !in element.indices) return element
                JsonArray(element.mapIndexed { current, value ->
                    if (current != index) value else if (tokens.size == 1) {
                        JsonPrimitive("<redacted>")
                    } else {
                        redactAt(value, tokens.drop(1))
                    }
                })
            }
            else -> throw IllegalArgumentException("Sensitive path does not resolve")
        }
    }

    private fun joinPublicBlocks(blocks: List<String>): String = blocks.joinToString("\n") { it.trim() }

    private fun String.normalizedWhitespace(): String = trim().replace(Regex("\\s+"), " ")

    private fun String.isSha256(): Boolean = matches(Regex("^[0-9a-f]{64}$"))

    private fun <T> immutableList(source: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(source))

    private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(source))

    private fun fail(
        code: String,
        event: AgentEventEnvelope?,
        message: String,
        cause: Throwable? = null
    ): Nothing = throw AgentEventProjectionException(code, event?.eventId, message, cause)
}
