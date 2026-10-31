// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.blob.BlobStore
import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class PendingDetailAttachment(
    val attachmentId: String,
    val role: DetailAttachmentRole,
    bytes: ByteArray,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val observationId: String? = null,
    modelImageBytes: ByteArray? = null
) {
    private val content = bytes.copyOf()
    private val modelContent = modelImageBytes?.copyOf()

    init {
        require(attachmentId.isNotBlank()) { "Detail attachmentId cannot be blank" }
        require(
            mimeType.count { it == '/' } == 1 &&
                mimeType.substringBefore('/').isNotBlank() &&
                mimeType.substringAfter('/').isNotBlank() &&
                mimeType.none(Char::isWhitespace)
        ) {
            "Detail attachment mimeType must be a non-blank type/subtype"
        }
        require((width == null) == (height == null)) {
            "Detail attachment width and height must be present together"
        }
        require(width == null || width > 0) { "Detail attachment width must be positive" }
        require(height == null || height > 0) { "Detail attachment height must be positive" }
        require(observationId == null || observationId.isNotBlank()) {
            "Detail attachment observationId cannot be blank"
        }
        require(modelContent == null || mimeType.startsWith("image/")) {
            "Only image attachments may have a model representation"
        }
    }

    internal fun contentSnapshot(): ByteArray = content.copyOf()
    internal fun modelContentSnapshot(): ByteArray? = modelContent?.copyOf()
}

suspend fun materializeDetailAttachments(
    blobStore: BlobStore,
    attachments: List<PendingDetailAttachment>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): List<DetailAttachmentAddedEventPayload> = runInterruptible(dispatcher) {
    attachments.map { pending ->
        val source = pending.contentSnapshot()
        val metadata = blobStore.put(source)
        val modelMetadata = pending.modelContentSnapshot()?.takeUnless { it.contentEquals(source) }?.let(blobStore::put)
        DetailAttachmentAddedEventPayload(
            contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
            attachmentId = pending.attachmentId,
            role = pending.role,
            contentRef = metadata.contentRef,
            contentHash = metadata.contentHash,
            sizeBytes = metadata.sizeBytes,
            mimeType = pending.mimeType,
            width = pending.width,
            height = pending.height,
            observationId = pending.observationId,
            modelContentRef = modelMetadata?.contentRef,
            modelSizeBytes = modelMetadata?.sizeBytes
        )
    }
}
