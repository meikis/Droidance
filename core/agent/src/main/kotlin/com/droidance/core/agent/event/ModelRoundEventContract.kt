// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.CapabilitySnapshot
import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.attempt.ExecutionBackendKind
import com.droidance.core.agent.attempt.ExecutionContext
import com.droidance.core.agent.attempt.PreparedAttemptBinding
import com.droidance.core.agent.attempt.PlannedToolStart
import com.droidance.core.agent.attempt.PreparedRoundExecutionPlan
import com.droidance.core.agent.attempt.ScopedShellCapability
import com.droidance.core.agent.device.ExecutionSpace
import com.droidance.core.agent.device.RuntimeMode
import com.droidance.core.agent.harness.NormalizedModelRound
import com.droidance.core.agent.harness.PlannedToolCall
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.RepairPurpose
import com.droidance.core.agent.harness.RoundExecutionMode
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION_KEY
import com.droidance.core.agent.harness.ZERO_TOOL_OPENING_VERSION
import com.droidance.core.agent.harness.isZeroToolActivation
import com.droidance.core.agent.harness.actionPublicMessageKind
import com.droidance.core.agent.harness.RoundFollowUp
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MODEL_ROUND_EVENT_CONTRACT_VERSION = 1

@Serializable
data class ModelRoundScope(
    val threadId: String,
    val turnId: String,
    val roundId: String
) {
    init {
        require(threadId.isNotBlank()) { "ModelRound threadId cannot be blank" }
        require(turnId.isNotBlank()) { "ModelRound turnId cannot be blank" }
        require(roundId.isNotBlank()) { "ModelRound roundId cannot be blank" }
    }
}

@Serializable
data class SubmittedItemSequenceRange(
    val firstSequence: Long,
    val lastSequence: Long
) {
    init {
        require(firstSequence > 0L) { "Submitted Item sequence must be positive" }
        require(lastSequence >= firstSequence) {
            "Submitted Item sequence range cannot be reversed"
        }
    }
}

@Serializable
enum class ToolCallSource {
    MODEL_REQUESTED,
    HARNESS_AUTOMATIC
}

@Serializable
enum class ToolPresentationCategory {
    PRIMARY,
    SUPPORTING,
    DIAGNOSTIC,
    COMPLETION
}

@Serializable
enum class ResultImageRole {
    NONE,
    INPUT,
    OUTPUT,
    BEFORE_AND_AFTER
}

@Serializable
data class ToolPresentationSnapshot(
    val labelResourceKey: String,
    val category: ToolPresentationCategory,
    val resultImageRole: ResultImageRole,
    val sensitiveFieldPaths: List<String> = emptyList(),
    val actionTitle: String? = null
) {
    init {
        require(labelResourceKey.isNotBlank()) { "Tool presentation label cannot be blank" }
        require(sensitiveFieldPaths.all(String::isNotBlank)) {
            "Sensitive field paths cannot be blank"
        }
        require(sensitiveFieldPaths.toSet().size == sensitiveFieldPaths.size) {
            "Sensitive field paths must be unique"
        }
    }
}

@Serializable
data class ModelRoundStartedEventPayload(
    val contractVersion: Int,
    val repairPurpose: RepairPurpose,
    val startCommitted: Boolean,
    val submittedItems: SubmittedItemSequenceRange,
    val roundDetailRef: String
)

@Serializable
data class ModelRoundCompletedEventPayload(
    val contractVersion: Int,
    val planFingerprint: String,
    val submittedItems: SubmittedItemSequenceRange,
    val providerAckSequence: Long,
    val publicBlocks: List<String>,
    val publicMessageKind: PublicMessageKind?,
    val finishMetadata: Map<String, String>,
    val executionMode: RoundExecutionMode,
    val followUp: RoundFollowUp,
    val terminalDiagnosticCode: String?,
    val candidateCapabilitySnapshotRef: String?,
    val candidateCapabilitySnapshotHash: String?,
    val attemptLifecycleSequence: Long?,
    val roundDetailRef: String
) {
    init {
        require(attemptLifecycleSequence == null || attemptLifecycleSequence > 0L) {
            "Existing attempt lifecycle sequence must be positive"
        }
    }
}

@Serializable
data class AssistantMessageEventPayload(
    val contractVersion: Int,
    val messageId: String,
    val kind: PublicMessageKind,
    val publicBlocks: List<String>,
    val roundDetailRef: String
) {
    init {
        require(messageId.isNotBlank()) { "Assistant messageId cannot be blank" }
        require(kind in setOf(PublicMessageKind.START, PublicMessageKind.USER_INTERVENTION, PublicMessageKind.STAGE_RESULT)) {
            "Only START, USER_INTERVENTION and STAGE_RESULT are persisted as AssistantMessage"
        }
        require(publicBlocks.isNotEmpty()) { "AssistantMessage requires public blocks" }
    }
}

@Serializable
data class PublicProgressEventPayload(
    val contractVersion: Int,
    val publicBlocks: List<String>,
    val roundDetailRef: String
) {
    init {
        require(publicBlocks.isNotEmpty()) { "PublicProgress requires public blocks" }
    }
}

@Serializable
data class ToolCallEventPayload(
    val contractVersion: Int,
    val providerOrder: Int,
    val providerCallId: String?,
    val name: String,
    val argumentsJson: String,
    val source: ToolCallSource,
    val binding: ToolBinding,
    val effect: ToolEffect,
    val contractHash: String,
    val presentation: ToolPresentationSnapshot,
    val roundDetailRef: String,
    val triggerCallId: String? = null
) {
    init {
        require(providerOrder >= 0) { "Provider order cannot be negative" }
        require(providerCallId == null || providerCallId.isNotBlank()) {
            "Provider call id cannot be blank"
        }
        require(name.isNotBlank()) { "Tool name cannot be blank" }
        require(argumentsJson.isNotBlank()) { "Tool arguments cannot be blank" }
        require(contractHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "Tool contractHash must be a lowercase SHA-256 hash"
        }
        require(roundDetailRef.isNotBlank()) { "ToolCall roundDetailRef cannot be blank" }
        require(triggerCallId == null || triggerCallId.isNotBlank()) {
            "Trigger call id cannot be blank"
        }
        require((effect == ToolEffect.COMPLETION) ==
            (presentation.category == ToolPresentationCategory.COMPLETION)
        ) {
            "Completion tools must use COMPLETION presentation and only completion tools may do so"
        }
        require(source == ToolCallSource.HARNESS_AUTOMATIC || triggerCallId == null) {
            "Only Harness automatic calls may reference a trigger call"
        }
    }
}

@Serializable
data class ExecutionContextSnapshotPayload(
    val executionId: String,
    val runtimeMode: RuntimeMode,
    val executionSpace: ExecutionSpace,
    val displayId: Int,
    val backendSessionId: String,
    val capabilitySnapshotRef: String,
    val capabilitySnapshotHash: String,
    val cancellationScopeId: String
)

@Serializable
data class ScopedShellCapabilitySnapshotPayload(
    val evidenceKind: String,
    val backendKind: ExecutionBackendKind,
    val backendSessionId: String,
    val displayId: Int,
    val proofRef: String,
    val proofHash: String
)

@Serializable
data class CapabilitySnapshotEventPayload(
    val capabilitySnapshotRef: String,
    val capabilitySnapshotHash: String,
    val runtimeMode: RuntimeMode,
    val executionSpace: ExecutionSpace,
    val displayId: Int,
    val backendKind: ExecutionBackendKind,
    val backendSessionId: String,
    val backendAvailable: Boolean,
    val capabilities: List<String>,
    val scopedShellCapability: ScopedShellCapabilitySnapshotPayload?
)

@Serializable
data class ExecutionAttemptCreatedEventPayload(
    val contractVersion: Int,
    val state: ExecutionAttemptState,
    val context: ExecutionContextSnapshotPayload,
    val capabilitySnapshot: CapabilitySnapshotEventPayload
) {
    init {
        require(state == ExecutionAttemptState.CREATED) {
            "ExecutionAttemptCreated must persist the CREATED state"
        }
    }
}

@Serializable
data class ToolStartedEventPayload(
    val contractVersion: Int,
    val roundDetailRef: String
)

@Serializable
data class ToolCallRejectedEventPayload(
    val contractVersion: Int,
    val result: ToolExecutionResult,
    val candidateCapabilitySnapshotRef: String?,
    val candidateCapabilitySnapshotHash: String?,
    val roundDetailRef: String
) {
    init {
        require(result.executionStatus == ToolExecutionStatus.NOT_EXECUTED) {
            "ToolCallRejected must materialize NOT_EXECUTED"
        }
        require(result.evidenceStatus == ToolEvidenceStatus.NOT_REQUIRED) {
            "ToolCallRejected cannot contain execution evidence"
        }
        require(result.observationId == null) {
            "ToolCallRejected cannot contain an observation"
        }
        require(
            (candidateCapabilitySnapshotRef == null) ==
                (candidateCapabilitySnapshotHash == null)
        ) {
            "Candidate capability ref and hash must be present together"
        }
    }
}

@Serializable
data class UserInterventionRequestedEventPayload(
    val contractVersion: Int,
    val messageId: String
) {
    init {
        require(messageId.isNotBlank()) { "User intervention requires a messageId" }
    }
}

class PreparedModelRoundStart private constructor(
    val scope: ModelRoundScope,
    val repairPurpose: RepairPurpose,
    val startCommitted: Boolean,
    val submittedItems: SubmittedItemSequenceRange,
    val roundDetailRef: String,
    val draft: AgentEventDraft
) {
    companion object {
        fun create(
            scope: ModelRoundScope,
            repairPurpose: RepairPurpose,
            startCommitted: Boolean,
            submittedItems: SubmittedItemSequenceRange,
            roundDetailRef: String
        ): PreparedModelRoundStart {
            require(roundDetailRef.isNotBlank()) { "ModelRound detail ref cannot be blank" }
            val payload = ModelRoundStartedEventPayload(
                contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                repairPurpose = repairPurpose,
                startCommitted = startCommitted,
                submittedItems = submittedItems,
                roundDetailRef = roundDetailRef
            )
            return PreparedModelRoundStart(
                scope = scope,
                repairPurpose = repairPurpose,
                startCommitted = startCommitted,
                submittedItems = submittedItems,
                roundDetailRef = roundDetailRef,
                draft = AgentEventDraft(
                    eventKind = AgentEventKind.MODEL_ROUND_STARTED,
                    threadId = scope.threadId,
                    turnId = scope.turnId,
                    roundId = scope.roundId,
                    payloadJson = roundPlanJson.encodeToString(payload),
                    idempotencyKey = "round:${scope.roundId}:started"
                )
            )
        }
    }
}

class PreparedModelRoundCommit private constructor(
    val scope: ModelRoundScope,
    val expectedStartCommitted: Boolean,
    val repairPurpose: RepairPurpose,
    val submittedItems: SubmittedItemSequenceRange,
    val roundDetailRef: String,
    val planFingerprint: String,
    val normalizedRound: NormalizedModelRound,
    val preparedExecution: PreparedRoundExecutionPlan,
    val drafts: List<AgentEventDraft>
) {
    companion object {
        fun create(
            scope: ModelRoundScope,
            expectedStartCommitted: Boolean,
            repairPurpose: RepairPurpose,
            submittedItems: SubmittedItemSequenceRange,
            roundDetailRef: String,
            normalizedRound: NormalizedModelRound,
            preparedExecution: PreparedRoundExecutionPlan,
            presentationsByCallId: Map<String, ToolPresentationSnapshot>,
            publicMessageId: String? = null
        ): PreparedModelRoundCommit {
            require(roundDetailRef.isNotBlank()) { "ModelRound detail ref cannot be blank" }
            val roundPlan = preparedExecution.roundPlan
            val plannedCalls = (roundPlan.selectedCalls + roundPlan.rejectedCalls.map { it.call })
                .sortedBy(PlannedToolCall::providerOrder)
            require(plannedCalls.map(PlannedToolCall::providerOrder) ==
                normalizedRound.toolCalls.indices.toList()
            ) {
                "The plan must contain each Provider call exactly once in Provider order"
            }
            plannedCalls.forEachIndexed { index, call ->
                require(call.providerCall == normalizedRound.toolCalls[index]) {
                    "Planned call $index does not match the normalized Provider call"
                }
            }
            require(plannedCalls.map(PlannedToolCall::callId).toSet().size == plannedCalls.size) {
                "Harness callIds must be globally unique within a ModelRound"
            }
            require(presentationsByCallId.keys == plannedCalls.map(PlannedToolCall::callId).toSet()) {
                "Every Provider call requires exactly one frozen ToolPresentation"
            }

            val expectedKind = expectedPublicMessageKind(
                round = normalizedRound,
                expectedStartCommitted = expectedStartCommitted,
                repairPurpose = repairPurpose,
                calls = plannedCalls,
                selectedCalls = roundPlan.selectedCalls
            )
            require(roundPlan.publicMessage?.kind == expectedKind) {
                "The public message classification does not match the deterministic lifecycle table"
            }
            require(roundPlan.publicMessage?.text == normalizedRound.publicText.takeIf { expectedKind != null }) {
                "The public message must preserve normalized public block content"
            }
            val committedPublicBlocks = normalizedRound.publicBlocks
                .takeIf { expectedKind != null }
                ?.toList()
                .orEmpty()
            val assistantMessageRequired = expectedKind == PublicMessageKind.START ||
                expectedKind == PublicMessageKind.USER_INTERVENTION || expectedKind == PublicMessageKind.STAGE_RESULT
            require(assistantMessageRequired == !publicMessageId.isNullOrBlank()) {
                "Exactly a durable assistant message requires a non-blank public messageId"
            }

            val rejectedDiagnostics = roundPlan.rejectedCalls.associate {
                it.call.callId to it.diagnosticCode
            }
            preparedExecution.toolRejections.forEach { rejection ->
                require(rejection.result.diagnosticCode == rejectedDiagnostics[rejection.call.callId]) {
                    "Rejected result diagnostic must match the immutable RoundExecutionPlan"
                }
            }

            val toolPayloads = plannedCalls.associate { call ->
                val presentation = checkNotNull(presentationsByCallId[call.callId]).frozenCopy()
                call.callId to ToolCallEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    providerOrder = call.providerOrder,
                    providerCallId = call.providerCall.providerCallId,
                    name = call.providerCall.name,
                    argumentsJson = call.providerCall.argumentsJson,
                    source = ToolCallSource.MODEL_REQUESTED,
                    binding = call.contract.binding,
                    effect = call.contract.effect,
                    contractHash = call.contract.contractHash,
                    presentation = presentation,
                    roundDetailRef = roundDetailRef
                )
            }
            val publicPayload = when (expectedKind) {
                PublicMessageKind.START,
                PublicMessageKind.STAGE_RESULT,
                PublicMessageKind.USER_INTERVENTION -> AssistantMessageEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    messageId = checkNotNull(publicMessageId),
                    kind = expectedKind,
                    publicBlocks = committedPublicBlocks,
                    roundDetailRef = roundDetailRef
                )
                PublicMessageKind.PROGRESS -> PublicProgressEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    publicBlocks = committedPublicBlocks,
                    roundDetailRef = roundDetailRef
                )
                PublicMessageKind.FINAL, null -> null
            }
            val attemptPayload = (preparedExecution.attemptBinding as? PreparedAttemptBinding.New)
                ?.binding
                ?.let { binding ->
                val attempt = binding.attempt
                ExecutionAttemptCreatedEventPayload(
                    contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                    state = attempt.state,
                    context = attempt.context.toSnapshotPayload(),
                    capabilitySnapshot = binding.capabilitySnapshot.toEventPayload()
                )
            }
            val attemptLifecycleSequence =
                (preparedExecution.attemptBinding as? PreparedAttemptBinding.Existing)
                    ?.expectedLifecycleSequence
            val startsByCallId = preparedExecution.toolStarts.associateBy { it.call.callId }
            val rejectionsByCallId = preparedExecution.toolRejections.associateBy { it.call.callId }
            val outcomePayloads = plannedCalls.associate { call ->
                val start = startsByCallId[call.callId]
                val rejection = rejectionsByCallId[call.callId]
                require((start == null) xor (rejection == null)) {
                    "Every Provider call must have exactly one planned disposition"
                }
                call.callId to if (start != null) {
                    PlannedOutcomePayload(
                        eventKind = AgentEventKind.TOOL_STARTED,
                        callId = call.callId,
                        attemptId = start.attemptId,
                        executionId = start.executionId,
                        payloadJson = roundPlanJson.encodeToString(
                            ToolStartedEventPayload(
                                contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                                roundDetailRef = roundDetailRef
                            )
                        )
                    )
                } else {
                    checkNotNull(rejection)
                    val candidateEvidenceApplies = rejection.executionContext == null &&
                        call.contract.binding == ToolBinding.DEVICE_BOUND
                    val payload = ToolCallRejectedEventPayload(
                        contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                        result = rejection.result.copy(metrics = rejection.result.metrics.toMap()),
                        candidateCapabilitySnapshotRef = preparedExecution
                            .candidateCapabilitySnapshotRef
                            .takeIf { candidateEvidenceApplies },
                        candidateCapabilitySnapshotHash = preparedExecution
                            .candidateCapabilitySnapshotHash
                            .takeIf { candidateEvidenceApplies },
                        roundDetailRef = roundDetailRef
                    )
                    PlannedOutcomePayload(
                        eventKind = AgentEventKind.TOOL_CALL_REJECTED,
                        callId = call.callId,
                        attemptId = rejection.attemptId,
                        executionId = rejection.executionId,
                        payloadJson = roundPlanJson.encodeToString(payload)
                    )
                }
            }
            val fingerprintMaterial = ModelRoundPlanFingerprintMaterial(
                scope = scope,
                expectedStartCommitted = expectedStartCommitted,
                repairPurpose = repairPurpose,
                submittedItems = submittedItems,
                publicBlocks = committedPublicBlocks,
                publicMessageKind = expectedKind,
                publicMessageId = publicMessageId,
                finishMetadata = normalizedRound.finishMetadata.toSortedMap(),
                executionMode = roundPlan.executionMode,
                followUp = roundPlan.followUp,
                terminalDiagnosticCode = roundPlan.terminalDiagnosticCode,
                candidateCapabilitySnapshotRef = preparedExecution.candidateCapabilitySnapshotRef,
                candidateCapabilitySnapshotHash = preparedExecution.candidateCapabilitySnapshotHash,
                attemptLifecycleSequence = attemptLifecycleSequence,
                roundDetailRef = roundDetailRef,
                toolCalls = plannedCalls.map { call ->
                    PlannedEventFingerprint(
                        eventKind = AgentEventKind.TOOL_CALL,
                        callId = call.callId,
                        attemptId = startsByCallId[call.callId]?.attemptId
                            ?: rejectionsByCallId[call.callId]?.attemptId,
                        executionId = startsByCallId[call.callId]?.executionId
                            ?: rejectionsByCallId[call.callId]?.executionId,
                        payloadJson = roundPlanJson.encodeToString(toolPayloads.getValue(call.callId))
                    )
                },
                publicPayloadJson = publicPayload?.let { payload ->
                    when (payload) {
                        is AssistantMessageEventPayload -> roundPlanJson.encodeToString(payload)
                        is PublicProgressEventPayload -> roundPlanJson.encodeToString(payload)
                        else -> error("Unsupported public payload")
                    }
                },
                attemptPayload = attemptPayload,
                outcomes = plannedCalls.map { outcomePayloads.getValue(it.callId) }
            )
            val fingerprint = sha256Hex(
                canonicalizeJson(roundPlanJson.encodeToString(fingerprintMaterial))
            )
            val completedPayload = ModelRoundCompletedEventPayload(
                contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                planFingerprint = fingerprint,
                submittedItems = submittedItems,
                providerAckSequence = submittedItems.lastSequence,
                publicBlocks = committedPublicBlocks,
                publicMessageKind = expectedKind,
                finishMetadata = normalizedRound.finishMetadata.toSortedMap(),
                executionMode = roundPlan.executionMode,
                followUp = roundPlan.followUp,
                terminalDiagnosticCode = roundPlan.terminalDiagnosticCode,
                candidateCapabilitySnapshotRef = preparedExecution.candidateCapabilitySnapshotRef,
                candidateCapabilitySnapshotHash = preparedExecution.candidateCapabilitySnapshotHash,
                attemptLifecycleSequence = attemptLifecycleSequence,
                roundDetailRef = roundDetailRef
            )

            val drafts = buildList {
                add(
                    AgentEventDraft(
                        eventKind = AgentEventKind.MODEL_ROUND_COMPLETED,
                        threadId = scope.threadId,
                        turnId = scope.turnId,
                        roundId = scope.roundId,
                        payloadJson = roundPlanJson.encodeToString(completedPayload),
                        idempotencyKey = "round:${scope.roundId}:completed"
                    )
                )
                when (val payload = publicPayload) {
                    is AssistantMessageEventPayload -> add(
                        AgentEventDraft(
                            eventKind = AgentEventKind.ASSISTANT_MESSAGE,
                            threadId = scope.threadId,
                            turnId = scope.turnId,
                            roundId = scope.roundId,
                            payloadJson = roundPlanJson.encodeToString(payload),
                            idempotencyKey = "message:${payload.messageId}:assistant"
                        )
                    )
                    is PublicProgressEventPayload -> add(
                        AgentEventDraft(
                            eventKind = AgentEventKind.PUBLIC_PROGRESS,
                            threadId = scope.threadId,
                            turnId = scope.turnId,
                            roundId = scope.roundId,
                            payloadJson = roundPlanJson.encodeToString(payload),
                            idempotencyKey = "round:${scope.roundId}:progress"
                        )
                    )
                    null -> Unit
                    else -> error("Unsupported public payload")
                }
                plannedCalls.forEach { call ->
                    val start = startsByCallId[call.callId]
                    val rejection = rejectionsByCallId[call.callId]
                    add(
                        AgentEventDraft(
                            eventKind = AgentEventKind.TOOL_CALL,
                            threadId = scope.threadId,
                            turnId = scope.turnId,
                            roundId = scope.roundId,
                            attemptId = start?.attemptId ?: rejection?.attemptId,
                            executionId = start?.executionId ?: rejection?.executionId,
                            callId = call.callId,
                            payloadJson = roundPlanJson.encodeToString(toolPayloads.getValue(call.callId)),
                            idempotencyKey = "call:${call.callId}:declared",
                            toolBinding = call.contract.binding
                        )
                    )
                }
                attemptPayload?.let { payload ->
                    val attempt = checkNotNull(preparedExecution.attempt)
                    add(
                        AgentEventDraft(
                            eventKind = AgentEventKind.EXECUTION_ATTEMPT_CREATED,
                            threadId = scope.threadId,
                            turnId = scope.turnId,
                            roundId = scope.roundId,
                            attemptId = attempt.attemptId,
                            executionId = attempt.context.executionId,
                            payloadJson = roundPlanJson.encodeToString(payload),
                            idempotencyKey = "attempt:${attempt.attemptId}:created"
                        )
                    )
                }
                plannedCalls.forEach { call ->
                    val outcome = outcomePayloads.getValue(call.callId)
                    add(
                        AgentEventDraft(
                            eventKind = outcome.eventKind,
                            threadId = scope.threadId,
                            turnId = scope.turnId,
                            roundId = scope.roundId,
                            attemptId = outcome.attemptId,
                            executionId = outcome.executionId,
                            callId = call.callId,
                            payloadJson = outcome.payloadJson,
                            idempotencyKey = if (outcome.eventKind == AgentEventKind.TOOL_STARTED) {
                                "call:${call.callId}:started"
                            } else {
                                "call:${call.callId}:rejected"
                            },
                            toolBinding = call.contract.binding
                        )
                    )
                    if (call.contract.effect == ToolEffect.USER_INTERVENTION &&
                        outcome.eventKind == AgentEventKind.TOOL_STARTED
                    ) {
                        add(
                            AgentEventDraft(
                                eventKind = AgentEventKind.USER_INTERVENTION_REQUESTED,
                                threadId = scope.threadId,
                                turnId = scope.turnId,
                                roundId = scope.roundId,
                                attemptId = outcome.attemptId,
                                executionId = outcome.executionId,
                                callId = call.callId,
                                payloadJson = roundPlanJson.encodeToString(
                                    UserInterventionRequestedEventPayload(
                                        contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                                        messageId = checkNotNull(publicMessageId)
                                    )
                                ),
                                idempotencyKey = "call:${call.callId}:intervention-requested",
                                toolBinding = call.contract.binding
                            )
                        )
                    }
                }
            }
            drafts.forEach(AgentEventSchema::validate)
            return PreparedModelRoundCommit(
                scope = scope,
                expectedStartCommitted = expectedStartCommitted,
                repairPurpose = repairPurpose,
                submittedItems = submittedItems,
                roundDetailRef = roundDetailRef,
                planFingerprint = fingerprint,
                normalizedRound = NormalizedModelRound.create(
                    normalizedRound.publicBlocks,
                    normalizedRound.toolCalls,
                    normalizedRound.finishMetadata
                ),
                preparedExecution = preparedExecution,
                drafts = Collections.unmodifiableList(ArrayList(drafts))
            )
        }
    }
}

data class RecordedToolStart(
    val event: AgentEventEnvelope,
    val plannedStart: PlannedToolStart
)

class RecordedModelRoundPlan internal constructor(
    val completed: AgentEventEnvelope,
    toolCalls: List<AgentEventEnvelope>,
    val publicEvent: AgentEventEnvelope?,
    val attemptCreated: AgentEventEnvelope?,
    outcomes: List<AgentEventEnvelope>,
    starts: List<RecordedToolStart>,
    interventionRequests: List<AgentEventEnvelope>
) {
    val toolCalls: List<AgentEventEnvelope> =
        Collections.unmodifiableList(ArrayList(toolCalls))
    val outcomes: List<AgentEventEnvelope> =
        Collections.unmodifiableList(ArrayList(outcomes))
    val starts: List<RecordedToolStart> =
        Collections.unmodifiableList(ArrayList(starts))
    val interventionRequests: List<AgentEventEnvelope> =
        Collections.unmodifiableList(ArrayList(interventionRequests))

    companion object {
        fun from(
            plan: PreparedModelRoundCommit,
            events: List<AgentEventEnvelope>
        ): RecordedModelRoundPlan {
            val completed = events.single { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            val toolCalls = events.filter { it.eventKind == AgentEventKind.TOOL_CALL }
            val publicEvents = events.filter {
                it.eventKind == AgentEventKind.ASSISTANT_MESSAGE ||
                    it.eventKind == AgentEventKind.PUBLIC_PROGRESS
            }
            val attemptEvents = events.filter {
                it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED
            }
            val outcomes = events.filter {
                it.eventKind == AgentEventKind.TOOL_STARTED ||
                    it.eventKind == AgentEventKind.TOOL_CALL_REJECTED
            }
            val startsByCallId = events
                .filter { it.eventKind == AgentEventKind.TOOL_STARTED }
                .associateBy { checkNotNull(it.callId) }
            val starts = plan.preparedExecution.toolStarts.map { planned ->
                RecordedToolStart(
                    event = checkNotNull(startsByCallId[planned.call.callId]),
                    plannedStart = planned
                )
            }
            return RecordedModelRoundPlan(
                completed = completed,
                toolCalls = toolCalls,
                publicEvent = publicEvents.singleOrNull(),
                attemptCreated = attemptEvents.singleOrNull(),
                outcomes = outcomes,
                starts = starts,
                interventionRequests = events.filter {
                    it.eventKind == AgentEventKind.USER_INTERVENTION_REQUESTED
                }
            )
        }
    }
}

sealed interface ModelRoundPlanRecordResult {
    val recorded: RecordedModelRoundPlan
}

class NewlyCommittedModelRoundPlan(
    override val recorded: RecordedModelRoundPlan
) : ModelRoundPlanRecordResult {
    private val executionClaimed = AtomicBoolean(false)

    fun claimForExecution(): RecordedModelRoundPlan {
        check(executionClaimed.compareAndSet(false, true)) {
            "Newly committed ModelRound plan execution has already been claimed"
        }
        return recorded
    }
}

class AlreadyCommittedModelRoundPlan(
    override val recorded: RecordedModelRoundPlan
) : ModelRoundPlanRecordResult

@Serializable
private data class ModelRoundPlanFingerprintMaterial(
    val scope: ModelRoundScope,
    val expectedStartCommitted: Boolean,
    val repairPurpose: RepairPurpose,
    val submittedItems: SubmittedItemSequenceRange,
    val publicBlocks: List<String>,
    val publicMessageKind: PublicMessageKind?,
    val publicMessageId: String?,
    val finishMetadata: Map<String, String>,
    val executionMode: RoundExecutionMode,
    val followUp: RoundFollowUp,
    val terminalDiagnosticCode: String?,
    val candidateCapabilitySnapshotRef: String?,
    val candidateCapabilitySnapshotHash: String?,
    val attemptLifecycleSequence: Long?,
    val roundDetailRef: String,
    val toolCalls: List<PlannedEventFingerprint>,
    val publicPayloadJson: String?,
    val attemptPayload: ExecutionAttemptCreatedEventPayload?,
    val outcomes: List<PlannedOutcomePayload>
)

@Serializable
private data class PlannedEventFingerprint(
    val eventKind: AgentEventKind,
    val callId: String,
    val attemptId: String?,
    val executionId: String?,
    val payloadJson: String
)

@Serializable
private data class PlannedOutcomePayload(
    val eventKind: AgentEventKind,
    val callId: String,
    val attemptId: String?,
    val executionId: String?,
    val payloadJson: String
)

private fun expectedPublicMessageKind(
    round: NormalizedModelRound,
    expectedStartCommitted: Boolean,
    repairPurpose: RepairPurpose,
    calls: List<PlannedToolCall>,
    selectedCalls: List<PlannedToolCall>
): PublicMessageKind? {
    if (round.isZeroToolActivation()) {
        require(round.finishMetadata[ZERO_TOOL_OPENING_VERSION_KEY] == ZERO_TOOL_OPENING_VERSION)
        require(calls.isEmpty() && !expectedStartCommitted && round.publicText != null) {
            "Activation must commit one public START before tools"
        }
        return PublicMessageKind.START
    }
    val hasPublic = round.publicText != null
    val hasIntervention = calls.any { it.contract.effect == ToolEffect.USER_INTERVENTION }
    val hasAction = calls.any {
        it.contract.effect == ToolEffect.READ_ONLY ||
            it.contract.effect == ToolEffect.STATE_CHANGING
    }
    return when {
        hasIntervention -> PublicMessageKind.USER_INTERVENTION.takeIf {
            hasPublic && selectedCalls.any { it.contract.effect == ToolEffect.USER_INTERVENTION }
        }
        hasAction -> round.actionPublicMessageKind(expectedStartCommitted).takeIf { hasPublic }
        else -> PublicMessageKind.FINAL.takeIf { hasPublic }
    }
}

private fun ToolPresentationSnapshot.frozenCopy(): ToolPresentationSnapshot =
    ToolPresentationSnapshot(
        labelResourceKey = labelResourceKey,
        category = category,
        resultImageRole = resultImageRole,
        sensitiveFieldPaths = sensitiveFieldPaths.sorted(),
        actionTitle = actionTitle
    )

private fun ExecutionContext.toSnapshotPayload(): ExecutionContextSnapshotPayload =
    ExecutionContextSnapshotPayload(
        executionId = executionId,
        runtimeMode = runtimeMode,
        executionSpace = executionSpace,
        displayId = displayId,
        backendSessionId = backendSessionId,
        capabilitySnapshotRef = capabilitySnapshotRef,
        capabilitySnapshotHash = capabilitySnapshotHash,
        cancellationScopeId = cancellationScopeId
    )

private fun CapabilitySnapshot.toEventPayload(): CapabilitySnapshotEventPayload =
    CapabilitySnapshotEventPayload(
        capabilitySnapshotRef = capabilitySnapshotRef,
        capabilitySnapshotHash = capabilitySnapshotHash,
        runtimeMode = runtimeMode,
        executionSpace = executionSpace,
        displayId = displayId,
        backendKind = backendKind,
        backendSessionId = backendSessionId,
        backendAvailable = backendAvailable,
        capabilities = capabilities.map { it.name }.sorted(),
        scopedShellCapability = scopedShellCapability?.toSnapshotPayload()
    )

private fun ScopedShellCapability.toSnapshotPayload(): ScopedShellCapabilitySnapshotPayload =
    ScopedShellCapabilitySnapshotPayload(
        evidenceKind = evidenceKind.name,
        backendKind = backendKind,
        backendSessionId = backendSessionId,
        displayId = displayId,
        proofRef = proofRef,
        proofHash = proofHash
    )

private val roundPlanJson = Json { encodeDefaults = true }

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
