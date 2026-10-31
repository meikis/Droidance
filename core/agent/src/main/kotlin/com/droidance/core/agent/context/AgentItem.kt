// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.context

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.harness.PlannedToolCall
import com.droidance.core.agent.harness.PublicMessageKind

/**
 * Provider-visible items after EventStore projection.
 *
 * Every item is assigned to the ModelRound that consumed or produced it before
 * ContextUnit construction. Audit-only events do not enter this contract.
 */
sealed interface AgentItem {
    val itemId: String
    val sequence: Long
    val roundId: String
}

enum class UserInputKind {
    MESSAGE,
    STEER
}

data class UserInputAgentItem(
    override val itemId: String,
    override val sequence: Long,
    override val roundId: String,
    val kind: UserInputKind,
    val text: String
) : AgentItem {
    init {
        validateIdentity(itemId, sequence, roundId)
        require(text.isNotBlank()) { "User input text cannot be blank" }
    }
}

data class AssistantOutputAgentItem(
    override val itemId: String,
    override val sequence: Long,
    override val roundId: String,
    val kind: PublicMessageKind,
    val text: String
) : AgentItem {
    init {
        validateIdentity(itemId, sequence, roundId)
        require(text.isNotBlank()) { "Assistant output text cannot be blank" }
    }
}

data class ToolCallAgentItem(
    override val itemId: String,
    override val sequence: Long,
    override val roundId: String,
    val call: PlannedToolCall
) : AgentItem {
    init {
        validateIdentity(itemId, sequence, roundId)
        require(call.callId.isNotBlank()) { "Tool callId cannot be blank" }
        require(call.providerOrder >= 0) { "Tool provider order cannot be negative" }
        require(call.providerCall.name.isNotBlank()) { "Tool name cannot be blank" }
        require(call.contract.name == call.providerCall.name) {
            "Tool registration must match the provider tool name"
        }
    }
}

data class ToolResultAgentItem(
    override val itemId: String,
    override val sequence: Long,
    override val roundId: String,
    val callId: String,
    val result: ToolExecutionResult
) : AgentItem {
    init {
        validateIdentity(itemId, sequence, roundId)
        require(callId.isNotBlank()) { "Tool result callId cannot be blank" }
    }
}

sealed interface ObservationContentRef

data class TextObservationContentRef(
    val textRef: String
) : ObservationContentRef {
    init {
        require(textRef.isNotBlank()) { "Observation text ref cannot be blank" }
    }
}

data class ScreenshotObservationContentRef(
    val imageRef: String,
    val textSummaryRef: String?,
    val sourceObservationId: String? = null,
    val sourceContentRef: String? = null,
    val sourceRegion: com.droidance.core.agent.event.ImageSourceRegion? = null,
    val modelContentRef: String? = null
) : ObservationContentRef {
    init {
        require(imageRef.isNotBlank()) { "Observation image ref cannot be blank" }
        require(textSummaryRef == null || textSummaryRef.isNotBlank()) {
            "Observation text summary ref cannot be blank"
        }
        modelContentRef?.let { com.droidance.core.agent.blob.hashFromContentRef(it) }
    }
}

data class ObservationRefAgentItem(
    override val itemId: String,
    override val sequence: Long,
    override val roundId: String,
    val observationId: String,
    val content: ObservationContentRef,
    val callId: String? = null
) : AgentItem {
    init {
        validateIdentity(itemId, sequence, roundId)
        require(observationId.isNotBlank()) { "Observation id cannot be blank" }
        require(callId == null || callId.isNotBlank()) { "Observation callId cannot be blank" }
    }
}

private fun validateIdentity(itemId: String, sequence: Long, roundId: String) {
    require(itemId.isNotBlank()) { "Agent itemId cannot be blank" }
    require(sequence > 0) { "Agent item sequence must be positive" }
    require(roundId.isNotBlank()) { "Context AgentItem requires roundId" }
}
