// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.agent.event.*
import com.droidance.core.persistence.agent.event.*
import com.droidance.agent.event.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompletionReviewImagesTest {
    @Test fun sourcesAreExactTurnScopedAndAmbiguityDoesNotSelectANeighbouringFrame() {
        val first = attachment("identity", "a".repeat(64))
        val otherTurn = attachment("foreign", "b".repeat(64)).copy(turnId = "other")
        val diagnostic = attachment("diagnostic", "c".repeat(64), DetailAttachmentRole.DIAGNOSTIC)
        val refs = CompletionReviewImages.resolve(listOf(first, otherTurn, diagnostic), "thread", "turn",
            setOf("identity", "foreign", "diagnostic", "invented"))
        assertEquals(mapOf("identity" to "sha256:${"a".repeat(64)}"), refs)
        assertTrue(CompletionReviewImages.resolve(listOf(first, attachment("identity", "b".repeat(64))),
            "thread", "turn", setOf("identity")).isEmpty())
    }

    private fun attachment(id: String, hash: String, role: DetailAttachmentRole = DetailAttachmentRole.INPUT_OBSERVATION) =
        AgentEventEnvelope(schemaVersion = AGENT_EVENT_SCHEMA_VERSION, eventId = id + hash,
            eventKind = AgentEventKind.DETAIL_ATTACHMENT_ADDED, sequence = 1L, occurredAt = 1L,
            threadId = "thread", turnId = "turn", roundId = "round", attemptId = "attempt",
            executionId = "execution", callId = "call", payloadHash = hash,
            payloadJson = Json.encodeToString(DetailAttachmentAddedEventPayload(
                contractVersion = 1, attachmentId = id, role = role, mimeType = "image/jpeg",
                contentRef = "sha256:$hash", contentHash = hash, sizeBytes = 10L, observationId = id)),
            idempotencyKey = id + hash)
}
