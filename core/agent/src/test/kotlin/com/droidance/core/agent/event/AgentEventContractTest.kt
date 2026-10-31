// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventContractTest {
    @Test
    fun userAttachmentRequiresAContentAddressedImageReference() {
        val hash = "a".repeat(64)
        val attachment = UserAttachmentPayload(
            attachmentId = "user-image-1",
            contentRef = "sha256:$hash",
            contentHash = hash,
            sizeBytes = 12L,
            mimeType = "image/png"
        )
        assertEquals("sha256:$hash", attachment.contentRef)
        assertThrows(IllegalArgumentException::class.java) {
            attachment.copy(mimeType = "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) {
            attachment.copy(contentRef = "sha256:${"b".repeat(64)}")
        }
    }

    @Test
    fun canonical_payload_is_independent_of_object_key_order() {
        assertEquals(
            canonicalizeJson("""{"b":2,"a":{"d":4,"c":3}}"""),
            canonicalizeJson("""{"a":{"c":3,"d":4},"b":2}""")
        )
    }

    @Test
    fun turn_round_call_and_attempt_scopes_are_validated_before_storage() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentEventSchema.validate(
                AgentEventDraft(
                    eventKind = AgentEventKind.TOOL_STARTED,
                    threadId = "thread",
                    turnId = "turn",
                    roundId = "round",
                    callId = "call",
                    payloadJson = "{}",
                    idempotencyKey = "key",
                    toolBinding = ToolBinding.DEVICE_BOUND
                )
            )
        }

        AgentEventSchema.validate(
            AgentEventDraft(
                eventKind = AgentEventKind.TOOL_STARTED,
                threadId = "thread",
                turnId = "turn",
                roundId = "round",
                attemptId = "attempt",
                executionId = "execution",
                callId = "call",
                payloadJson = "{}",
                idempotencyKey = "key",
                toolBinding = ToolBinding.DEVICE_BOUND
            )
        )
    }

    @Test
    fun not_executed_requires_a_machine_readable_diagnostic() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ToolExecutionResult(
                outputText = "",
                executionStatus = ToolExecutionStatus.NOT_EXECUTED,
                evidenceStatus = ToolEvidenceStatus.NOT_REQUIRED
            )
        }
        assertTrue(error.message.orEmpty().contains("diagnosticCode"))
    }

    @Test
    fun detail_attachment_requires_content_addressed_ref_and_non_negative_size() {
        val contentHash = "a".repeat(64)

        assertThrows(IllegalArgumentException::class.java) {
            DetailAttachmentAddedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                attachmentId = "attachment-ref",
                role = DetailAttachmentRole.RESULT,
                contentRef = "sha256:${"b".repeat(64)}",
                contentHash = contentHash,
                sizeBytes = 1L,
                mimeType = "image/png"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DetailAttachmentAddedEventPayload(
                contractVersion = AGENT_READ_EVENT_CONTRACT_VERSION,
                attachmentId = "attachment-size",
                role = DetailAttachmentRole.RESULT,
                contentRef = "sha256:$contentHash",
                contentHash = contentHash,
                sizeBytes = -1L,
                mimeType = "image/png"
            )
        }
    }

    @Test fun model_representation_requires_paired_content_addressed_metadata() {
        val source = DetailAttachmentAddedEventPayload(1, "source", DetailAttachmentRole.INPUT_OBSERVATION,
            "sha256:" + "a".repeat(64), "a".repeat(64), 100, "image/png")
        val ref = "sha256:" + "b".repeat(64)
        assertThrows(IllegalArgumentException::class.java) { source.copy(modelContentRef = ref) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(modelSizeBytes = 20) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(modelContentRef = "bad", modelSizeBytes = 20) }
        assertThrows(IllegalArgumentException::class.java) { source.copy(modelContentRef = ref, modelSizeBytes = -1) }
        val pair = source.copy(modelContentRef = ref, modelSizeBytes = 20)
        assertEquals(source.contentRef, pair.contentRef)
        assertEquals(ref, pair.modelContentRef)
    }
}
