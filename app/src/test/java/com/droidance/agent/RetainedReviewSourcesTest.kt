// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.agent.blob.FileBlobStore
import com.droidance.core.agent.event.AGENT_EVENT_SCHEMA_VERSION
import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.TurnEventScope
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedReviewSourcesTest {
    @Test
    fun currentObservationAttachmentCanBeLoadedAsAnOriginalReviewSource() {
        val directory = Files.createTempDirectory("current-observation-review-")
        try {
            val blobs = FileBlobStore(directory.toFile())
            val png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            )
            val blob = blobs.put(png)
            val model = blobs.put(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            val scope = TurnEventScope("review-thread", "review-turn")
            val attachment = DetailAttachmentAddedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                attachmentId = "failed-call:observation:obs-current",
                role = DetailAttachmentRole.CURRENT_OBSERVATION,
                contentRef = blob.contentRef,
                contentHash = blob.contentHash,
                sizeBytes = blob.sizeBytes,
                mimeType = "image/png",
                width = 1,
                height = 1,
                observationId = "obs-current",
                modelContentRef = model.contentRef,
                modelSizeBytes = model.sizeBytes
            )
            val event = AgentEventEnvelope(
                schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
                eventId = "attachment-current",
                eventKind = AgentEventKind.DETAIL_ATTACHMENT_ADDED,
                sequence = 4,
                occurredAt = 4,
                threadId = scope.threadId,
                turnId = scope.turnId,
                roundId = null,
                attemptId = null,
                executionId = null,
                callId = "failed-call",
                payloadHash = "a".repeat(64),
                payloadJson = Json.encodeToString(attachment),
                idempotencyKey = "failed-call:observation:obs-current"
            )

            assertEquals(attachment, RetainedReviewSources.resolveImage(listOf(event), scope, "obs-current"))
            assertEquals(attachment, RetainedReviewSources.resolveImage(listOf(event), scope, blob.contentRef))
            assertEquals(attachment, RetainedReviewSources.resolveImage(listOf(event), scope, model.contentRef))
            assertEquals(attachment, RetainedReviewSources.resolveImage(listOf(event), scope, null))
            assertNull(RetainedReviewSources.resolveImage(listOf(event), scope.copy(turnId = "other"), blob.contentRef))
            assertNull(RetainedReviewSources.resolveImage(listOf(event), scope, "unknown"))
            val diagnostic = event.copy(payloadJson = Json.encodeToString(attachment.copy(role = DetailAttachmentRole.DIAGNOSTIC)))
            assertNull(RetainedReviewSources.resolveImage(listOf(diagnostic), scope, null))
            val derived = attachment.copy(observationId = "crop-one", sourceObservationId = "obs-current")
            val derivedEvent = event.copy(sequence = 5, payloadJson = Json.encodeToString(derived))
            assertEquals(attachment, RetainedReviewSources.resolveImage(listOf(event, derivedEvent), scope, null))
            assertEquals(derived, RetainedReviewSources.resolveImage(listOf(event, derivedEvent), scope, "crop-one"))

            val loaded = RetainedReviewSources.load(
                events = listOf(event),
                scope = scope,
                requested = setOf(blob.contentRef),
                blobs = FileBlobStore(directory.toFile())
            )

            assertEquals(setOf(blob.contentRef), loaded.refs)
            assertTrue(loaded.failures.isEmpty())
            assertEquals(1, loaded.messages.size)
            assertTrue(loaded.messages.single().historicalImage)
            assertEquals(Base64.getEncoder().encodeToString(png), loaded.messages.single().imageBase64)
            assertTrue(loaded.messages.single().content.contains("obs-current"))
            assertNull(loaded.failures[blob.contentRef])
            val modelLoaded = RetainedReviewSources.load(listOf(event), scope, setOf(model.contentRef), blobs)
            assertEquals(Base64.getEncoder().encodeToString(blobs.read(model.contentRef, 3)), modelLoaded.messages.single().imageBase64)
            assertTrue(modelLoaded.messages.single().content.contains("retained model representation"))
            assertTrue(modelLoaded.messages.single().content.contains(blob.contentRef))
            val modelArtifact = attachment.copy(contentRef = model.contentRef, contentHash = model.contentHash,
                sizeBytes = model.sizeBytes, mimeType = "image/jpeg", observationId = "model-artifact",
                sourceObservationId = "obs-current", sourceContentRef = blob.contentRef,
                modelContentRef = null, modelSizeBytes = null)
            val imported = event.copy(payloadJson = Json.encodeToString(modelArtifact))
            assertEquals(attachment, RetainedReviewSources.resolveImage(listOf(event, imported.copy(sequence = 5)), scope, model.contentRef))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(java.util.Comparator.reverseOrder<java.nio.file.Path>())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
