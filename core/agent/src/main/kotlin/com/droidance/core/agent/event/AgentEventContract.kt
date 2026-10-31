// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

const val AGENT_EVENT_SCHEMA_VERSION = 3

@Serializable
enum class AgentEventKind {
    THREAD_CREATED,
    THREAD_METADATA_CHANGED,
    THREAD_PIN_CHANGED,
    THREAD_TOMBSTONED,
    TURN_STARTED,
    MODEL_SESSION_ACTIVATED,
    MODEL_LOCAL_SESSION_ACTIVATED,
    TURN_RECOVERY_REQUIRED,
    USER_MESSAGE,
    USER_STEER,
    MEMORY_CONTEXT_LOADED,
    MODEL_ROUND_STARTED,
    MODEL_ROUND_COMPLETED,
    ASSISTANT_MESSAGE,
    PUBLIC_PROGRESS,
    SYSTEM_NOTICE,
    EXECUTION_ATTEMPT_CREATED,
    EXECUTION_ATTEMPT_STATE_CHANGED,
    TOOL_CALL,
    TOOL_CALL_REJECTED,
    TOOL_STARTED,
    TOOL_FINISHED,
    USER_INTERVENTION_REQUESTED,
    DETAIL_ATTACHMENT_ADDED,
    CONTEXT_CHECKPOINT,
    MODEL_SESSION_EPOCH_ADVANCED,
    LEARNING_SESSION_STARTED,
    LEARNING_ACTION_RECORDED,
    LEARNING_SESSION_COMPLETED,
    LEARNING_SKILL_COMPILED,
    COMPILED_SKILL_RUN_STARTED,
    COMPILED_SKILL_RUN_COMPLETED,
    PROGRAM_EXECUTION,
    WORKSPACE_FILE_WRITTEN,
    WORKSPACE_FOCUS_CHANGED,
    TURN_COMPLETED
}

@Serializable
enum class ToolBinding {
    TURN_LOCAL,
    DEVICE_BOUND
}

@Serializable
enum class ToolEffect {
    READ_ONLY,
    STATE_CHANGING,
    USER_INTERVENTION,
    COMPLETION
}

@Serializable
enum class ToolExecutionStatus {
    SUCCEEDED,
    FAILED,
    CANCELED,
    INTERRUPTED,
    NOT_EXECUTED
}

@Serializable
enum class ToolEvidenceStatus {
    NOT_REQUIRED,
    CAPTURED,
    INVALID,
    UNKNOWN
}

@Serializable
enum class ActionDeliveryEvidence {
    ACTION_DELIVERED,
    NOT_DELIVERED,
    UNKNOWN
}

@Serializable
enum class StateTransitionEvidence {
    STATE_CHANGED,
    RAW_CHANGED,
    UNCHANGED,
    UNKNOWN
}

@Serializable
enum class GoalProgressEvidence {
    GOAL_PROGRESS,
    NOT_PROVEN
}

@Serializable
data class ToolActionEvidence(
    val delivery: ActionDeliveryEvidence,
    val transition: StateTransitionEvidence,
    val goalProgress: GoalProgressEvidence
)

@Serializable
data class ToolExecutionResult(
    val outputText: String,
    val executionStatus: ToolExecutionStatus,
    val evidenceStatus: ToolEvidenceStatus,
    val observationId: String? = null,
    val diagnosticCode: String? = null,
    val metrics: Map<String, Long> = emptyMap(),
    val actionEvidence: ToolActionEvidence? = null,
    val platformContractAccepted: Boolean = false,
    val validation: ToolValidationEvidence? = null,
    val historicalAttachment: DetailAttachmentAddedEventPayload? = null,
    val actionFeedback: List<DetailAttachmentAddedEventPayload> = emptyList(),
    val tapCoordinates: List<com.droidance.core.agent.tools.TapCoordinates> = emptyList(),
    val batchScreenshots: List<BatchScreenshotEvidence> = emptyList(),
    val sourceRef: String? = null,
    val data: JsonElement? = null,
    val dataRef: String? = null,
    val schema: String? = null,
    val coverage: Map<String, String>? = null,
    val preview: String? = null,
    /** Executor-proven change to the declared target state; null is unknown, not false.
     * Unchanged pixels or successful delivery alone cannot establish this fact. */
    val stateChanged: Boolean? = null
) {
    init {
        require(executionStatus != ToolExecutionStatus.NOT_EXECUTED || !diagnosticCode.isNullOrBlank()) {
            "NOT_EXECUTED requires a diagnosticCode"
        }
    }

    val isVerifiedSuccess: Boolean
        get() = executionStatus == ToolExecutionStatus.SUCCEEDED &&
            evidenceStatus in setOf(ToolEvidenceStatus.CAPTURED, ToolEvidenceStatus.NOT_REQUIRED)

    /** One complete textual receipt for context, compaction and scoped history reads. */
    fun asReceiptText(): String = outputText.ifBlank {
        Json.encodeToString(serializer(), this)
    }
}

data class AgentEventDraft(
    val eventKind: AgentEventKind,
    val threadId: String,
    val turnId: String? = null,
    val roundId: String? = null,
    val attemptId: String? = null,
    val executionId: String? = null,
    val callId: String? = null,
    val payloadJson: String,
    val idempotencyKey: String,
    val toolBinding: ToolBinding? = null
)

@Serializable
data class AgentEventEnvelope(
    val schemaVersion: Int,
    val eventId: String,
    val eventKind: AgentEventKind,
    val sequence: Long,
    val occurredAt: Long,
    val threadId: String,
    val turnId: String?,
    val roundId: String?,
    val attemptId: String?,
    val executionId: String?,
    val callId: String?,
    val payloadHash: String,
    val payloadJson: String,
    val idempotencyKey: String
)

object AgentEventSchema {
    private val threadOnlyKinds = setOf(
        AgentEventKind.THREAD_CREATED,
        AgentEventKind.THREAD_METADATA_CHANGED,
        AgentEventKind.THREAD_PIN_CHANGED,
        AgentEventKind.THREAD_TOMBSTONED,
        AgentEventKind.LEARNING_SESSION_STARTED,
        AgentEventKind.LEARNING_ACTION_RECORDED,
        AgentEventKind.LEARNING_SESSION_COMPLETED,
        AgentEventKind.LEARNING_SKILL_COMPILED,
        AgentEventKind.COMPILED_SKILL_RUN_STARTED,
        AgentEventKind.COMPILED_SKILL_RUN_COMPLETED
    )

    private val roundKinds = setOf(
        AgentEventKind.MODEL_ROUND_STARTED,
        AgentEventKind.MODEL_ROUND_COMPLETED,
        AgentEventKind.ASSISTANT_MESSAGE,
        AgentEventKind.PUBLIC_PROGRESS,
        AgentEventKind.TOOL_CALL,
        AgentEventKind.TOOL_CALL_REJECTED,
        AgentEventKind.TOOL_STARTED,
        AgentEventKind.TOOL_FINISHED,
        AgentEventKind.USER_INTERVENTION_REQUESTED,
        AgentEventKind.DETAIL_ATTACHMENT_ADDED
    )

    private val attemptKinds = setOf(
        AgentEventKind.EXECUTION_ATTEMPT_CREATED,
        AgentEventKind.EXECUTION_ATTEMPT_STATE_CHANGED
    )

    private val callKinds = setOf(
        AgentEventKind.TOOL_CALL,
        AgentEventKind.TOOL_CALL_REJECTED,
        AgentEventKind.TOOL_STARTED,
        AgentEventKind.TOOL_FINISHED,
        AgentEventKind.USER_INTERVENTION_REQUESTED,
        AgentEventKind.DETAIL_ATTACHMENT_ADDED
    )

    fun validate(draft: AgentEventDraft) {
        require(draft.threadId.isNotBlank()) { "Every event requires threadId" }
        require(draft.idempotencyKey.isNotBlank()) { "Every event requires idempotencyKey" }
        require(draft.payloadJson.isNotBlank()) { "Every event requires payload" }
        require(parsePayload(draft.payloadJson) is JsonObject) { "Event payload must be a JSON object" }

        if (draft.eventKind !in threadOnlyKinds) {
            require(!draft.turnId.isNullOrBlank()) { "${draft.eventKind} requires turnId" }
        }
        if (draft.eventKind in roundKinds) {
            require(!draft.roundId.isNullOrBlank()) { "${draft.eventKind} requires roundId" }
        }
        if (draft.eventKind in attemptKinds) {
            requireAttemptScope(draft)
        }
        if (draft.eventKind in callKinds) {
            require(!draft.callId.isNullOrBlank()) { "${draft.eventKind} requires callId" }
        }
        if (draft.eventKind in setOf(AgentEventKind.TOOL_STARTED, AgentEventKind.TOOL_FINISHED) &&
            draft.toolBinding == ToolBinding.DEVICE_BOUND
        ) {
            requireAttemptScope(draft)
        }
        require(draft.attemptId.isNullOrBlank() == draft.executionId.isNullOrBlank()) {
            "attemptId and executionId must either both be present or both be absent"
        }
    }

    private fun requireAttemptScope(draft: AgentEventDraft) {
        require(!draft.attemptId.isNullOrBlank()) { "${draft.eventKind} requires attemptId" }
        require(!draft.executionId.isNullOrBlank()) { "${draft.eventKind} requires executionId" }
    }
}

fun canonicalizeJson(raw: String): String =
    Json.encodeToString(JsonElement.serializer(), canonicalize(parsePayload(raw)))

private fun parsePayload(raw: String): JsonElement = Json.parseToJsonElement(raw)

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries
            .sortedBy { it.key }
            .associate { (key, value) -> key to canonicalize(value) }
    )
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}

/** Capability surface selected for a turn; DIRECT has no tools or knowledge reads. */
enum class TurnActivationKind { DIRECT, LOCAL, ANDROID }
