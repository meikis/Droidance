// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.attempt.ExecutionAttemptState
import com.droidance.core.agent.llm.session.EpochAdvanceReason
import com.droidance.core.agent.llm.session.ModelSessionIdentity
import kotlinx.serialization.Serializable

const val AGENT_READ_EVENT_CONTRACT_VERSION = 1

@Serializable
data class ThreadCreatedEventPayload(
    val contractVersion: Int,
    val initialTitle: String
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(initialTitle.isNotBlank()) { "Thread initial title cannot be blank" }
    }
}

@Serializable
data class ThreadMetadataChangedEventPayload(
    val contractVersion: Int,
    val title: String,
    val summary: String?
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(title.isNotBlank()) { "Thread title cannot be blank" }
        requireOptionalText(summary, "Thread summary")
    }
}

@Serializable
data class ThreadPinChangedEventPayload(
    val contractVersion: Int,
    val isPinned: Boolean
) {
    init {
        requireReadEventContractVersion(contractVersion)
    }
}

@Serializable
data class ThreadTombstonedEventPayload(
    val contractVersion: Int
) {
    init {
        requireReadEventContractVersion(contractVersion)
    }
}

@Serializable
data class TurnStartedEventPayload(
    val contractVersion: Int,
    val modelSessionIdentity: ModelSessionIdentity,
    val providerConfigSnapshotRef: String
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(providerConfigSnapshotRef.isNotBlank()) {
            "Provider config snapshot ref cannot be blank"
        }
    }
}

@Serializable
data class ModelSessionActivatedEventPayload(
    val contractVersion: Int,
    val modelSessionIdentity: ModelSessionIdentity,
    val providerConfigSnapshotRef: String,
    val previousEpoch: Long,
    val currentEpoch: Long
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(providerConfigSnapshotRef.isNotBlank()) {
            "Activated provider config snapshot ref cannot be blank"
        }
        require(previousEpoch >= 0L && currentEpoch == Math.addExact(previousEpoch, 1L)) {
            "Android activation must advance the current ModelSession by one epoch"
        }
    }
}

@Serializable
data class TurnRecoveryRequiredEventPayload(
    val contractVersion: Int,
    val recoveryId: String,
    val diagnosticCode: String
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(recoveryId.isNotBlank()) { "Turn recoveryId cannot be blank" }
        require(diagnosticCode.isNotBlank()) { "Turn recovery diagnosticCode cannot be blank" }
    }
}

@Serializable
data class UserAttachmentPayload(
    val attachmentId: String,
    val contentRef: String,
    val contentHash: String,
    val sizeBytes: Long,
    val mimeType: String
) {
    init {
        require(attachmentId.isNotBlank()) { "User attachmentId cannot be blank" }
        requireSha256(contentHash, "User attachment contentHash")
        require(contentRef == "sha256:$contentHash") {
            "User attachment contentRef must match its SHA-256 contentHash"
        }
        require(sizeBytes > 0L) { "User attachment sizeBytes must be positive" }
        require(mimeType.startsWith("image/") && mimeType.length > "image/".length) {
            "User attachment mimeType must be an image type"
        }
    }
}

@Serializable
data class UserTextEventPayload(
    val contractVersion: Int,
    val messageId: String,
    val text: String,
    val attachments: List<UserAttachmentPayload> = emptyList(),
    val activeDurationMillis: Long? = null
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(activeDurationMillis == null || activeDurationMillis >= 0L)
        require(messageId.isNotBlank()) { "User text messageId cannot be blank" }
        require(text.isNotBlank()) { "User text cannot be blank" }
        require(attachments.map(UserAttachmentPayload::attachmentId).distinct().size == attachments.size) {
            "User attachmentIds must be unique within a message"
        }
    }
}

@Serializable
enum class MemoryContextScope {
    CORE_PROFILE,
    RELATED_PROFILE,
    LONG_TERM,
    EPISODE,
    EXPERIENCE
}

@Serializable
data class MemoryContextItemPayload(
    val ref: String,
    val scope: MemoryContextScope,
    val summary: String,
    val sourceThroughSequence: Long? = null
) {
    init {
        require(ref.matches(Regex("^[a-z_]+:[A-Za-z0-9._:-]+$"))) {
            "Memory context ref must be a typed stable identifier"
        }
        require(summary.isNotBlank()) { "Memory context summary cannot be blank" }
        require(summary.length <= 500) { "Memory context summary cannot exceed 500 characters" }
        require(sourceThroughSequence == null || sourceThroughSequence > 0L) {
            "Memory context source sequence must be positive"
        }
    }
}

@Serializable
data class MemoryContextLoadedEventPayload(
    val contractVersion: Int,
    val queryHash: String,
    val items: List<MemoryContextItemPayload>,
    /** Execution context of a refresh for the same user input, such as an observed foreground package. */
    val contextKey: String? = null
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(contextKey == null || contextKey.matches(Regex("^[a-z_]+:[A-Za-z0-9._-]{1,255}$"))) {
            "Memory context key must be a typed stable identifier"
        }
        requireSha256(queryHash, "Memory context queryHash")
        require(items.size <= 64) { "Memory context cannot contain more than 64 summaries" }
        require(items.map(MemoryContextItemPayload::ref).distinct().size == items.size) {
            "Memory context refs must be unique"
        }
    }
}

@Serializable
data class SystemNoticeEventPayload(
    val contractVersion: Int,
    val noticeId: String,
    val text: String,
    val diagnosticCode: String? = null
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(noticeId.isNotBlank()) { "System noticeId cannot be blank" }
        require(text.isNotBlank()) { "System notice text cannot be blank" }
        requireOptionalText(diagnosticCode, "System notice diagnosticCode")
    }
}

@Serializable
data class ExecutionAttemptStateChangedEventPayload(
    val contractVersion: Int,
    val state: ExecutionAttemptState,
    val diagnosticCode: String? = null
) {
    init {
        requireReadEventContractVersion(contractVersion)
        requireOptionalText(diagnosticCode, "Execution attempt diagnosticCode")
    }
}

@Serializable
data class ToolFinishedEventPayload(
    val contractVersion: Int,
    val result: ToolExecutionResult,
    val roundDetailRef: String,
    val exclusiveDurationMillis: Long? = null
) {
    init {
        requireReadEventContractVersion(contractVersion)
        // ToolStarted starts the invocation (including admission checks), not physical
        // delivery. A withheld invocation closes with its typed NOT_EXECUTED receipt.
        require(result.isVerifiedSuccess || !result.diagnosticCode.isNullOrBlank()) {
            "An unverified ToolFinished result requires a diagnosticCode"
        }
        require(roundDetailRef.isNotBlank()) { "ToolFinished roundDetailRef cannot be blank" }
        require(exclusiveDurationMillis == null || exclusiveDurationMillis >= 0L) {
            "ToolFinished exclusive duration cannot be negative"
        }
    }
}

@Serializable
data class DetailAttachmentAddedEventPayload(
    val contractVersion: Int,
    val attachmentId: String,
    val role: DetailAttachmentRole,
    val contentRef: String,
    val contentHash: String,
    val sizeBytes: Long,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val observationId: String? = null,
    /** Original image transformed to produce this attachment; null denotes source pixels. */
    val sourceObservationId: String? = null,
    val sourceContentRef: String? = null,
    val sourceRegion: ImageSourceRegion? = null,
    /** Optional model representation of the same source; contentRef always retains source bytes. */
    val modelContentRef: String? = null,
    val modelSizeBytes: Long? = null
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(attachmentId.isNotBlank()) { "Detail attachmentId cannot be blank" }
        requireSha256(contentHash, "Detail attachment contentHash")
        require(contentRef == "sha256:$contentHash") {
            "Detail attachment contentRef must match its SHA-256 contentHash"
        }
        require(sizeBytes >= 0L) { "Detail attachment sizeBytes cannot be negative" }
        requireValidMimeType(mimeType)
        require((width == null) == (height == null)) {
            "Detail attachment width and height must be present together"
        }
        require(width == null || width > 0) { "Detail attachment width must be positive" }
        require(height == null || height > 0) { "Detail attachment height must be positive" }
        requireOptionalText(observationId, "Detail attachment observationId")
        requireOptionalText(sourceObservationId, "Detail attachment sourceObservationId")
        sourceContentRef?.let { ref ->
            require(ref.startsWith("sha256:")) { "Image source must identify retained bytes" }
            requireSha256(ref.removePrefix("sha256:"), "Image source content ref")
        }
        require(sourceRegion == null || sourceContentRef != null) { "Image source pixels require a retained source ref" }
        require((modelContentRef == null) == (modelSizeBytes == null)) {
            "Model image reference and size must be present together"
        }
        modelContentRef?.let { ref ->
            require(mimeType.startsWith("image/")) { "Only images have a model representation" }
            require(ref.startsWith("sha256:")) { "Model image must identify retained bytes" }
            requireSha256(ref.removePrefix("sha256:"), "Model image content ref")
            require(checkNotNull(modelSizeBytes) >= 0L) { "Model image size cannot be negative" }
        }
    }
}

/** Actual source pixels used by an image transform, independent of action coordinates. */
@Serializable
data class ImageSourceRegion(
    val x: Int, val y: Int, val width: Int, val height: Int,
    val sourceWidth: Int, val sourceHeight: Int
) {
    init {
        require(x >= 0 && y >= 0 && width > 0 && height > 0)
        require(sourceWidth > 0 && sourceHeight > 0)
        require(x.toLong() + width <= sourceWidth && y.toLong() + height <= sourceHeight)
    }
}

@Serializable
data class TurnCompletedEventPayload(
    val contractVersion: Int,
    val status: String,
    val assistantReply: String? = null,
    val systemNotice: String? = null,
    val diagnosticCode: String? = null,
    val totalDurationMillis: Long? = null,
    val cancellationOrigin: String? = null,
    val activeDurationMillis: Long? = null
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(status.isNotBlank()) { "Turn completion status cannot be blank" }
        require((assistantReply == null) xor (systemNotice == null)) {
            "Turn completion requires exactly one of assistantReply or systemNotice"
        }
        requireOptionalText(assistantReply, "Turn completion assistantReply")
        requireOptionalText(systemNotice, "Turn completion systemNotice")
        requireOptionalText(diagnosticCode, "Turn completion diagnosticCode")
        require(totalDurationMillis == null || totalDurationMillis >= 0L) {
            "Turn completion total duration cannot be negative"
        }
        require(activeDurationMillis == null || activeDurationMillis >= 0L) {
            "Turn completion active duration cannot be negative"
        }
    }
}

@Serializable
data class ModelSessionEpochAdvancedEventPayload(
    val contractVersion: Int,
    val previousEpoch: Long,
    val currentEpoch: Long,
    val reason: EpochAdvanceReason,
    val evidenceRef: String,
    val diagnosticCode: String? = null,
    val identity: ModelSessionIdentity
) {
    init {
        requireReadEventContractVersion(contractVersion)
        require(previousEpoch >= 0L) { "Previous ModelSession epoch cannot be negative" }
        require(currentEpoch == Math.addExact(previousEpoch, 1L)) {
            "ModelSession epoch must advance exactly once"
        }
        require(evidenceRef.isNotBlank()) { "ModelSession epoch evidenceRef cannot be blank" }
        requireOptionalText(diagnosticCode, "ModelSession epoch diagnosticCode")
    }
}

private fun requireReadEventContractVersion(contractVersion: Int) {
    require(contractVersion == AGENT_READ_EVENT_CONTRACT_VERSION) {
        "Unsupported agent read event contractVersion: $contractVersion"
    }
}

private fun requireOptionalText(value: String?, label: String) {
    require(value == null || value.isNotBlank()) { "$label cannot be blank" }
}

private fun requireSha256(value: String, label: String) {
    require(value.matches(Regex("^[0-9a-f]{64}$"))) {
        "$label must be a lowercase SHA-256 hash"
    }
}

private fun requireValidMimeType(mimeType: String) {
    require(
        mimeType.count { it == '/' } == 1 &&
            mimeType.substringBefore('/').isNotBlank() &&
            mimeType.substringAfter('/').isNotBlank() &&
            mimeType.none(Char::isWhitespace)
    ) {
        "Detail attachment mimeType must be a non-blank type/subtype"
    }
}

@Serializable
enum class DetailAttachmentRole {
    INPUT_OBSERVATION,
    RESULT,
    /** A later model-visible frame; the call scope is a storage anchor, not its cause. */
    CURRENT_OBSERVATION,
    DIAGNOSTIC
}
