// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import com.droidance.core.agent.event.AGENT_EVENT_SCHEMA_VERSION
import com.droidance.core.agent.event.AGENT_READ_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.MODEL_ROUND_EVENT_CONTRACT_VERSION
import com.droidance.core.agent.event.AgentEventEnvelope
import com.droidance.core.agent.event.AgentEventKind
import com.droidance.core.agent.event.DetailAttachmentAddedEventPayload
import com.droidance.core.agent.event.DetailAttachmentRole
import com.droidance.core.agent.event.ModelRoundCompletedEventPayload
import com.droidance.core.agent.event.SubmittedItemSequenceRange
import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolCallEventPayload
import com.droidance.core.agent.event.ToolCallSource
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolFinishedEventPayload
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import com.droidance.core.agent.event.TurnCompletedEventPayload
import com.droidance.core.agent.harness.PublicMessageKind
import com.droidance.core.agent.harness.RoundExecutionMode
import com.droidance.core.agent.harness.RoundFollowUp
import com.droidance.core.agent.ActionControlProtocol
import com.droidance.core.model.llm.models.ToolCall
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceProjectionTest {
    @Test
    fun projectsOnlyAnAcceptedCurrentTurnMethodWithOriginalReadEvidence() {
        val proposals = ExperienceProjection.project(completedTurn(readEvidence = true), "turn-current", 3, 12_000)

        assertEquals(1, proposals.size)
        val record = requireNotNull(proposals.single().record)
        assertNull(proposals.single().error)
        assertEquals("experience:price_filter:1", record.ref)
        assertEquals("turn-current", record.turnId)
        assertEquals(listOf("event-turn-current-2"), record.sourceEvents)
        assertEquals("task_agent", record.assessmentAuthority)
        assertEquals("task_agent_requires_current_verification", record.applicabilityAuthority)
    }

    @Test
    fun succeededExperienceRequiresMatchingSupportedCompletionClaimAndEvidence() {
        val accepted = ExperienceProjection.project(completedTurn(
            readEvidence = true, outcome = "succeeded", requirementId = "req-price"
        ), "turn-current", 3, 12_000).single()
        assertNotNull(accepted.record)

        val wrongRequirement = ExperienceProjection.project(completedTurn(
            readEvidence = true, outcome = "succeeded", requirementId = "req-price",
            completionRequirementId = "missing-requirement"
        ), "turn-current", 3, 12_000).single()
        assertNull(wrongRequirement.record)
        assertEquals("EXPERIENCE_SUPPORTED_REQUIREMENT_REQUIRED", wrongRequirement.error)

        val mismatchedEvidence = ExperienceProjection.project(completedTurn(
            readEvidence = true, outcome = "succeeded", requirementId = "req-price",
            completionEvidenceRef = "observation:another-observation"
        ), "turn-current", 3, 12_000).single()
        assertNull(mismatchedEvidence.record)
        assertEquals("EXPERIENCE_REQUIREMENT_EVIDENCE_MISMATCH", mismatchedEvidence.error)
    }

    @Test
    fun succeededExperienceUsesOriginalImageAndReceiptSourcesFromMultipleSelectedResults() {
        val proposal = ExperienceProjection.project(completedTurn(
            readEvidence = true, imageEvidence = true, additionalReadEvidence = true,
            outcome = "succeeded", requirementId = "r1",
            experienceEvidenceRefs = listOf("observation:obs-current", "event:event-turn-current-5"),
            completionResults = listOf("Visible filter" to "observation:obs-current",
                "Original device data" to "event:event-turn-current-5"),
            completionResultIndices = listOf(0, 1)
        ), "turn-current", 3, 12_000).single()

        assertNull(proposal.error)
        assertEquals(listOf("event-turn-current-2", "event-turn-current-5"),
            requireNotNull(proposal.record).sourceEvents)
        assertEquals("succeeded", proposal.record!!.update.outcome)
    }

    @Test
    fun succeededExperienceCannotUseARealSourceOutsideItsSelectedCompletionResults() {
        val proposal = ExperienceProjection.project(completedTurn(
            readEvidence = true, imageEvidence = true, additionalReadEvidence = true,
            outcome = "succeeded", requirementId = "r1",
            experienceEvidenceRefs = listOf("observation:obs-current", "event:event-turn-current-5"),
            completionResults = listOf("Visible filter" to "observation:obs-current",
                "Original device data" to "event:event-turn-current-5"),
            completionResultIndices = listOf(0)
        ), "turn-current", 3, 12_000).single()

        assertNull(proposal.record)
        assertEquals("EXPERIENCE_REQUIREMENT_EVIDENCE_MISMATCH", proposal.error)
    }

    @Test
    fun succeededProcessExperienceUsesItsDirectSourceWithoutBorrowingAResultSource() {
        val proposal = ExperienceProjection.project(completedTurn(
            readEvidence = true, outcome = "succeeded", requirementId = "c1",
            completionResultIndices = emptyList(),
            completionProcessEvidenceRefs = "observation:obs-current"
        ), "turn-current", 3, 12_000).single()

        assertNull(proposal.error)
        assertEquals(listOf("event-turn-current-2"), requireNotNull(proposal.record).sourceEvents)
    }

    @Test
    fun succeededExperienceAcceptsOnlySourcesBoundToValidResultIndices() {
        val redundant = ExperienceProjection.project(completedTurn(
            readEvidence = true, outcome = "succeeded", requirementId = "r1",
            completionResultIndices = listOf(0), repeatedBoundSources = true
        ), "turn-current", 3, 12_000).single()
        assertNull(redundant.error)
        assertEquals(listOf("event-turn-current-2"), requireNotNull(redundant.record).sourceEvents)
        for (indices in listOf(listOf(1), listOf(-1), listOf(0, 0))) {
            val proposal = ExperienceProjection.project(completedTurn(
                readEvidence = true, outcome = "succeeded", requirementId = "r1",
                completionResultIndices = indices,
                requireValidControl = false
            ), "turn-current", 3, 12_000).single()

            assertNull(proposal.record)
            assertEquals("EXPERIENCE_SUPPORTED_REQUIREMENT_REQUIRED", proposal.error)
        }
    }

    @Test
    fun refusesAnObservationReferenceThatExistsOnlyInAnotherTurn() {
        val events = completedTurn(readEvidence = false) + readSource("turn-other", "obs-other", 20)
        val proposal = ExperienceProjection.project(events, "turn-current", 3, 12_000).single()

        assertNull(proposal.record)
        assertEquals("EXPERIENCE_SOURCE_NOT_CURRENT_EXTERNAL_EVIDENCE", proposal.error)
    }

    @Test
    fun refusesStateChangingActionReceiptAsAReusableSource() {
        val events = completedTurn(readEvidence = false, stateChangingEvidence = true)
        val proposal = ExperienceProjection.project(events, "turn-current", 3, 12_000).single()

        assertNull(proposal.record)
        assertEquals("EXPERIENCE_SOURCE_NOT_CURRENT_EXTERNAL_EVIDENCE", proposal.error)
    }

    @Test
    fun refusesEvidenceCopiedFromAHistoricalAttachment() {
        val events = completedTurn(readEvidence = false, historicalEvidence = true)
        val proposal = ExperienceProjection.project(events, "turn-current", 3, 12_000).single()

        assertNull(proposal.record)
        assertEquals("EXPERIENCE_SOURCE_NOT_CURRENT_EXTERNAL_EVIDENCE", proposal.error)
    }

    @Test
    fun invalidOptionalExperienceDoesNotChangeCompletionProjectionOrOtherUpdates() {
        val events = completedTurn(readEvidence = true, addInvalidSibling = true)
        val proposals = ExperienceProjection.project(events, "turn-current", 3, 12_000)

        assertEquals(2, proposals.size)
        assertNotNull(proposals[0].record)
        assertNull(proposals[0].error)
        assertNull(proposals[1].record)
        assertEquals("EXPERIENCE_ID_INVALID", proposals[1].error)
        assertEquals(AgentEventKind.TURN_COMPLETED, events.last().eventKind)
    }

    @Test
    fun unfinishedTurnDoesNotProduceExperience() {
        val events = completedTurn(readEvidence = true).dropLast(1)
        assertTrue(ExperienceProjection.project(events, "turn-current", 3, 12_000).isEmpty())
    }

    private fun completedTurn(
        readEvidence: Boolean,
        imageEvidence: Boolean = false,
        additionalReadEvidence: Boolean = false,
        stateChangingEvidence: Boolean = false,
        historicalEvidence: Boolean = false,
        addInvalidSibling: Boolean = false,
        outcome: String = "unknown",
        requirementId: String = "",
        completionRequirementId: String = requirementId.ifBlank { "r1" },
        completionEvidenceRef: String = "observation:obs-current",
        experienceEvidenceRefs: List<String> = listOf("observation:obs-current"),
        completionResults: List<Pair<String, String>> = listOf("Completed" to completionEvidenceRef),
        completionResultIndices: List<Int> = listOf(0),
        completionProcessEvidenceRefs: String? = null,
        repeatedBoundSources: Boolean = false,
        requireValidControl: Boolean = true
    ): List<AgentEventEnvelope> {
        val source = when {
            readEvidence -> readSource("turn-current", "obs-current", 1, imageEvidence) +
                if (additionalReadEvidence) readSource("turn-current", "receipt-current", if (imageEvidence) 4 else 3) else emptyList()
            stateChangingEvidence -> listOf(
                event("turn-current", 1, AgentEventKind.TOOL_CALL, toolCall(
                    name = "android_act", arguments = "{}", binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.STATE_CHANGING, presentationCategory = ToolPresentationCategory.PRIMARY
                ), roundId = "round-source", callId = "source-call"),
                event("turn-current", 2, AgentEventKind.TOOL_FINISHED, ToolFinishedEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    ToolExecutionResult("Action delivered and screen changed", ToolExecutionStatus.SUCCEEDED,
                        ToolEvidenceStatus.CAPTURED, observationId = "obs-current"),
                    "round-source"
                ), roundId = "round-source", callId = "source-call")
            )
            historicalEvidence -> listOf(
                event("turn-current", 1, AgentEventKind.TOOL_CALL, toolCall(
                    name = "device_read", arguments = "{}", binding = ToolBinding.DEVICE_BOUND,
                    effect = ToolEffect.READ_ONLY, presentationCategory = ToolPresentationCategory.PRIMARY
                ), roundId = "round-source", callId = "source-call"),
                event("turn-current", 2, AgentEventKind.TOOL_FINISHED, ToolFinishedEventPayload(
                    AGENT_READ_EVENT_CONTRACT_VERSION,
                    ToolExecutionResult("Previously captured content", ToolExecutionStatus.SUCCEEDED,
                        ToolEvidenceStatus.CAPTURED, observationId = "obs-current",
                        historicalAttachment = oldAttachment()),
                    "round-source"
                ), roundId = "round-source", callId = "source-call")
            )
            else -> emptyList()
        }
        val sourceOffset = source.size
        val update = update("price_filter", "observation:obs-current", requirementId, outcome)
            .copy(evidence_refs = experienceEvidenceRefs)
        val updates = if (addInvalidSibling) listOf(update, update("bad id", "observation:obs-current")) else listOf(update)
        val controlArgs = buildJsonObject {
            put("status", "done")
            put("results", buildJsonArray { completionResults.forEach { (text, refs) -> add(buildJsonObject {
                put("text", text); put("evidence_refs", JsonArray(refs.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive)))
            }) } })
            put("unresolved_requirements", JsonArray(emptyList()))
            put("requirement_checks", buildJsonArray { add(buildJsonObject {
                put("requirement_id", completionRequirementId); put("status", "supported")
                put("unresolved_index", -1)
                put("result_indices", JsonArray(completionResultIndices.map(::JsonPrimitive)))
                if (repeatedBoundSources) put("evidence_refs", JsonArray(completionEvidenceRef.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive)))
                completionProcessEvidenceRefs?.let { put("evidence_refs", JsonArray(it.split(',').map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive))) }
            }) })
            put("experience_updates", JSON.encodeToJsonElement(updates))
        }
        val extracted = ActionControlProtocol.extract(listOf(ToolCall("provider-done", "done", controlArgs.toString())))
        if (requireValidControl) assertNull(extracted.controls.getValue("provider-done").invalidArgument)
        val doneCall = ToolCallEventPayload(
            contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
            providerOrder = 0,
            providerCallId = "provider-done",
            name = "done",
            argumentsJson = extracted.toolCalls.single().arguments,
            source = ToolCallSource.MODEL_REQUESTED,
            binding = ToolBinding.TURN_LOCAL,
            effect = ToolEffect.COMPLETION,
            contractHash = "a".repeat(64),
            presentation = ToolPresentationSnapshot("tool.done", ToolPresentationCategory.COMPLETION, ResultImageRole.NONE),
            roundDetailRef = "detail-done"
        )
        val roundSequence = sourceOffset + 1L
        val doneSequence = roundSequence + 1
        val all = source.toMutableList()
        all += event("turn-current", roundSequence, AgentEventKind.MODEL_ROUND_COMPLETED,
            ModelRoundCompletedEventPayload(
                contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
                planFingerprint = "d".repeat(64),
                submittedItems = SubmittedItemSequenceRange(1, 1),
                providerAckSequence = roundSequence,
                publicBlocks = listOf("Completed"),
                publicMessageKind = PublicMessageKind.FINAL,
                finishMetadata = extracted.finishMetadata,
                executionMode = RoundExecutionMode.SERIAL,
                followUp = RoundFollowUp.COMPLETE_AFTER_RESULTS,
                terminalDiagnosticCode = null,
                candidateCapabilitySnapshotRef = null,
                candidateCapabilitySnapshotHash = null,
                attemptLifecycleSequence = null,
                roundDetailRef = "round-detail"
            ), roundId = "round-done")
        all += event("turn-current", doneSequence, AgentEventKind.TOOL_CALL, doneCall,
            roundId = "round-done", callId = "done-call")
        all += event("turn-current", doneSequence + 1, AgentEventKind.TOOL_FINISHED,
            ToolFinishedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION,
                ToolExecutionResult("Task completed", ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.NOT_REQUIRED),
                "detail-done"), roundId = "round-done", callId = "done-call")
        all += event("turn-current", doneSequence + 2, AgentEventKind.TURN_COMPLETED,
            TurnCompletedEventPayload(AGENT_READ_EVENT_CONTRACT_VERSION, "done", assistantReply = "Completed"))
        return all
    }

    private fun readSource(turnId: String, observationId: String, sequence: Long, image: Boolean = false) = listOf(
        event(turnId, sequence, AgentEventKind.TOOL_CALL, toolCall(
            name = "android_read", arguments = if (image) """{"op":"screenshot"}""" else """{"op":"content","uri":"content://example/items"}""", binding = ToolBinding.DEVICE_BOUND,
            effect = ToolEffect.READ_ONLY, presentationCategory = ToolPresentationCategory.PRIMARY
        ).copy(providerCallId = "provider-source-$sequence"), roundId = "round-source", callId = "source-call-$turnId-$sequence"),
        event(turnId, sequence + 1, AgentEventKind.TOOL_FINISHED, ToolFinishedEventPayload(
            AGENT_READ_EVENT_CONTRACT_VERSION,
            ToolExecutionResult("Original device observation: visible price filter", ToolExecutionStatus.SUCCEEDED,
                ToolEvidenceStatus.CAPTURED, observationId = observationId),
            "round-source"
        ), roundId = "round-source", callId = "source-call-$turnId-$sequence")
    ) + if (image) listOf(event(turnId, sequence + 2, AgentEventKind.DETAIL_ATTACHMENT_ADDED,
        oldAttachment().copy(attachmentId = "original-image", observationId = observationId),
        roundId = "round-source", callId = "source-call-$turnId-$sequence")) else emptyList()

    private fun toolCall(name: String, arguments: String, binding: ToolBinding, effect: ToolEffect,
                         presentationCategory: ToolPresentationCategory) = ToolCallEventPayload(
        contractVersion = MODEL_ROUND_EVENT_CONTRACT_VERSION,
        providerOrder = 0,
        providerCallId = "provider-source",
        name = name,
        argumentsJson = arguments,
        source = ToolCallSource.MODEL_REQUESTED,
        binding = binding,
        effect = effect,
        contractHash = "a".repeat(64),
        presentation = ToolPresentationSnapshot("tool.$name", presentationCategory, ResultImageRole.NONE),
        roundDetailRef = "detail-source"
    )

    private fun update(id: String, evidenceRef: String, requirementId: String = "", outcome: String = "unknown") = buildJsonUpdate(
        id = id, expectedRevision = 0, evidenceRef = evidenceRef, requirementId = requirementId, outcome = outcome
    )

    private fun buildJsonUpdate(id: String, expectedRevision: Long, evidenceRef: String,
                                requirementId: String = "", outcome: String = "unknown") =
        ExperienceUpdate(id, expectedRevision, "Filter by price", "example.app", listOf("min", "max"),
            "Search results are visible", "Open filters, enter the range, apply, then inspect results",
            "Re-observe after opening filters; stop if the range control is absent",
            "The requested range is visibly applied", "Re-observe and replan if the filter panel differs",
            requirementId, outcome, "Scoped task-agent assessment only", listOf(evidenceRef))

    private fun oldAttachment() = DetailAttachmentAddedEventPayload(
        AGENT_READ_EVENT_CONTRACT_VERSION,
        attachmentId = "historical-image",
        role = DetailAttachmentRole.INPUT_OBSERVATION,
        contentRef = "sha256:${"b".repeat(64)}",
        contentHash = "b".repeat(64),
        sizeBytes = 1,
        mimeType = "image/png",
        width = 1,
        height = 1,
        observationId = "obs-historical"
    )

    private inline fun <reified T> event(
        turnId: String,
        sequence: Long,
        kind: AgentEventKind,
        payload: T,
        roundId: String? = null,
        callId: String? = null
    ): AgentEventEnvelope {
        val payloadJson = JSON.encodeToString(payload)
        return AgentEventEnvelope(
            schemaVersion = AGENT_EVENT_SCHEMA_VERSION,
            eventId = "event-$turnId-$sequence",
            eventKind = kind,
            sequence = sequence,
            occurredAt = sequence * 10,
            threadId = "thread-$turnId",
            turnId = turnId,
            roundId = roundId,
            attemptId = null,
            executionId = null,
            callId = callId,
            payloadHash = "c".repeat(64),
            payloadJson = payloadJson,
            idempotencyKey = "key-$turnId-$sequence"
        )
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
