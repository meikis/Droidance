// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import java.util.Collections

object AgentRecoveryDiagnosticCode {
    const val PROCESS_RECOVERY = "PROCESS_RECOVERY"
}

data class TurnEventScope(
    val threadId: String,
    val turnId: String
) {
    init {
        require(threadId.isNotBlank()) { "Turn threadId cannot be blank" }
        require(turnId.isNotBlank()) { "Turn turnId cannot be blank" }
    }
}

data class ExecutionAttemptEventScope(
    val threadId: String,
    val turnId: String,
    val attemptId: String,
    val executionId: String
) {
    init {
        require(threadId.isNotBlank()) { "ExecutionAttempt threadId cannot be blank" }
        require(turnId.isNotBlank()) { "ExecutionAttempt turnId cannot be blank" }
        require(attemptId.isNotBlank()) { "ExecutionAttempt attemptId cannot be blank" }
        require(executionId.isNotBlank()) { "ExecutionAttempt executionId cannot be blank" }
    }
}

class DurableExecutionAttemptSnapshot(
    val scope: ExecutionAttemptEventScope,
    val state: ExecutionAttemptState,
    val creationSequence: Long,
    val lifecycleSequence: Long,
    context: ExecutionContextSnapshotPayload,
    capabilitySnapshot: CapabilitySnapshotEventPayload
) {
    val context: ExecutionContextSnapshotPayload = context.copy()
    val capabilitySnapshot: CapabilitySnapshotEventPayload = capabilitySnapshot.copy(
        capabilities = Collections.unmodifiableList(ArrayList(capabilitySnapshot.capabilities))
    )

    init {
        require(creationSequence > 0L) {
            "Durable ExecutionAttempt creation sequence must be positive"
        }
        require(lifecycleSequence >= creationSequence) {
            "Durable ExecutionAttempt lifecycle sequence cannot precede creation"
        }
        require(context.executionId == scope.executionId) {
            "Durable ExecutionAttempt Context must match its scope"
        }
        require(capabilitySnapshot.capabilitySnapshotRef == context.capabilitySnapshotRef &&
            capabilitySnapshot.capabilitySnapshotHash == context.capabilitySnapshotHash
        ) {
            "Durable ExecutionAttempt capability snapshot must match its Context"
        }
    }
}

class StartedToolRecoverySnapshot(
    val scope: ToolLifecycleEventScope,
    val roundDetailRef: String,
    val startedSequence: Long
) {
    init {
        require(roundDetailRef.isNotBlank()) {
            "Started Tool recovery roundDetailRef cannot be blank"
        }
        require(startedSequence > 0L) {
            "Started Tool recovery sequence must be positive"
        }
    }
}

class TurnRecoverySnapshot(
    val scope: TurnEventScope,
    activeAttempts: List<DurableExecutionAttemptSnapshot>,
    pendingTools: List<StartedToolRecoverySnapshot>
) {
    val activeAttempts: List<DurableExecutionAttemptSnapshot> =
        Collections.unmodifiableList(ArrayList(activeAttempts))
    val pendingTools: List<StartedToolRecoverySnapshot> =
        Collections.unmodifiableList(ArrayList(pendingTools))
}

class TurnRecoveryCommit(
    val turnRecoveryEvent: AgentEventEnvelope,
    attemptStateEvents: List<AgentEventEnvelope>,
    toolFinishEvents: List<AgentEventEnvelope>
) {
    val attemptStateEvents: List<AgentEventEnvelope> =
        Collections.unmodifiableList(ArrayList(attemptStateEvents))
    val toolFinishEvents: List<AgentEventEnvelope> =
        Collections.unmodifiableList(ArrayList(toolFinishEvents))
}

data class ToolLifecycleEventScope(
    val threadId: String,
    val turnId: String,
    val roundId: String,
    val callId: String,
    val attemptId: String? = null,
    val executionId: String? = null
) {
    init {
        require(threadId.isNotBlank()) { "Tool lifecycle threadId cannot be blank" }
        require(turnId.isNotBlank()) { "Tool lifecycle turnId cannot be blank" }
        require(roundId.isNotBlank()) { "Tool lifecycle roundId cannot be blank" }
        require(callId.isNotBlank()) { "Tool lifecycle callId cannot be blank" }
        require(attemptId.isNullOrBlank() == executionId.isNullOrBlank()) {
            "Tool lifecycle attemptId and executionId must be present together"
        }
    }
}

data class TurnResult(
    val status: String,
    val assistantReply: String? = null,
    val systemNotice: String? = null,
    val diagnosticCode: String? = null,
    val totalDurationMillis: Long? = null,
    val cancellationOrigin: String? = null,
    val activeDurationMillis: Long? = null
) {
    init {
        require(status.isNotBlank()) { "TurnResult status cannot be blank" }
        require((assistantReply == null) xor (systemNotice == null)) {
            "TurnResult requires exactly one of assistantReply or systemNotice"
        }
        require(assistantReply == null || assistantReply.isNotBlank()) {
            "TurnResult assistantReply cannot be blank"
        }
        require(systemNotice == null || systemNotice.isNotBlank()) {
            "TurnResult systemNotice cannot be blank"
        }
        require(diagnosticCode == null || diagnosticCode.isNotBlank()) {
            "TurnResult diagnosticCode cannot be blank"
        }
        require(totalDurationMillis == null || totalDurationMillis >= 0L) {
            "TurnResult total duration cannot be negative"
        }
        require(activeDurationMillis == null || activeDurationMillis >= 0L) {
            "TurnResult active duration cannot be negative"
        }
    }
}
