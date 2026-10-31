// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompletionReviewImageResolutionTest {
    @Test fun requestedEarlierOriginalsResolveWithinTheTurn() {
        val events = listOf(attachment("call_first", 'a'), attachment("call_second", 'b'))
        assertEquals(setOf("call_first", "call_second"),
            CompletionReviewImages.resolve(events, "thread", "turn", setOf("call_first", "call_second")).keys)
    }

    @Test fun nonImageAttachmentsDoNotResolveToReviewImages() {
        assertTrue(CompletionReviewImages.resolve(emptyList(), "thread", "turn", setOf("missing")).isEmpty())
        assertTrue(CompletionReviewImages.resolve(
            listOf(attachment("non_image", 'a', mime = "application/json")), "thread", "turn", setOf("non_image")).isEmpty())
    }

    @Test fun foreignDiagnosticNonImageAndAmbiguousAttachmentsCannotBecomeOriginalSources() {
        val events = listOf(attachment("valid", 'a'),
            attachment("foreign_turn", 'b').copy(turnId = "other"),
            attachment("foreign_thread", 'c').copy(threadId = "other"),
            attachment("diagnostic", 'd', role = DetailAttachmentRole.DIAGNOSTIC),
            attachment("non_image", 'e', mime = "application/json"),
            attachment("ambiguous", 'f'), attachment("ambiguous", '9'))
        assertEquals(setOf("valid"), CompletionReviewImages.resolve(events, "thread", "turn",
            setOf("valid", "foreign_turn", "foreign_thread", "diagnostic", "non_image", "ambiguous")).keys)
    }

    @Test fun duplicateReceiptsForTheSameOriginalRemainOneSource() {
        val original = attachment("original", 'a')
        val events = listOf(original, original.copy(eventId = "repeated", sequence = 2L))
        val sources = CompletionReviewImages.resolve(events, "thread", "turn", setOf("original")).keys
        assertEquals(setOf("original"), sources)
        assertEquals(mapOf("original" to "sha256:${"a".repeat(64)}"),
            CompletionReviewImages.resolve(events, "thread", "turn", sources))
    }

    private fun attachment(id: String, hashCharacter: Char,
                           role: DetailAttachmentRole = DetailAttachmentRole.INPUT_OBSERVATION,
                           mime: String = "image/png"): AgentEventEnvelope {
        val hash = hashCharacter.toString().repeat(64)
        return AgentEventEnvelope(schemaVersion = AGENT_EVENT_SCHEMA_VERSION, eventId = id + hash,
            eventKind = AgentEventKind.DETAIL_ATTACHMENT_ADDED, sequence = 1L, occurredAt = 1L,
            threadId = "thread", turnId = "turn", roundId = "round", attemptId = "attempt",
            executionId = "execution", callId = "call", payloadHash = hash,
            payloadJson = Json.encodeToString(DetailAttachmentAddedEventPayload(
                contractVersion = 1, attachmentId = id, role = role, mimeType = mime,
                contentRef = "sha256:$hash", contentHash = hash, sizeBytes = 10L, observationId = id)),
            idempotencyKey = id + hash)
    }
}
