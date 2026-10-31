// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolEvidenceStatus
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompletionReviewGateTest {
    @Test fun sourceIndexKeepsUncitedOriginalsAcrossReviewBatches() {
        val gate = CompletionReviewGate(true)
        val sources = setOf("uncited_first", "uncited_second", "cited_last")
        val candidate = CompletionReviewCandidate.from("done",
            """{"results":[{"text":"finding","evidence_refs":["cited_last"]}]}""")
        gate.request("task", "review", sources, candidate)
        assertTrue(gate.candidateSourceRefs().containsAll(sources))
        gate.recordDeliveredSources(setOf("uncited_first"))
        assertEquals(sources - "uncited_first", gate.pendingSources())
        assertTrue(gate.candidateSourceRefs().containsAll(sources))
    }

    @Test fun changedCandidateRetainsOriginalDeliveryAndShowsExactCandidateImmediately() {
        val gate = CompletionReviewGate(true)
        assertTrue(request(gate, "task", "review", setOf("source"))!!.outputText.contains("candidate_to_verify"))
        gate.recordDeliveredSources(setOf("source"))
        assertNotNull(request(gate, "task", "review", setOf("source"), "corrected fact"))
        assertTrue(gate.pendingSources().isEmpty())
        gate.recordDeliveredSources(emptySet())
        assertNull(request(gate, "task", "review", setOf("source"), "corrected fact"))
    }
    @Test fun validSourceIdsCannotApproveAResultUntilOriginalImagesReachAReviewRequest() {
        val gate = CompletionReviewGate(true)
        val sources = setOf("identity-first", "identity-second")
        gate.recordDeliveredSources(sources) // Old context is not a post-candidate review.
        assertEquals(CompletionReviewGate.CODE, request(gate, "Task", "Review", sources)!!.diagnosticCode)
        gate.onToolSettled(ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY, ToolExecutionStatus.SUCCEEDED) // A receipt is not image delivery.
        assertEquals(CompletionReviewGate.SOURCE_CODE, request(gate, "Task", "Review", sources)!!.diagnosticCode)
        gate.recordDeliveredSources(setOf("identity-first"))
        assertNotNull(request(gate, "Task", "Review", sources))
        gate.recordDeliveredSources(setOf("identity-second"))
        assertNull(request(gate, "Task", "Review", sources))
        assertNotNull(request(gate, "Task", "Review", sources + "different-object"))
    }

    @Test fun firstCandidateIsWithheldAndAReadDoesNotCreateAnEndlessReviewLoop() {
        val gate = CompletionReviewGate(true)
        val response = request(gate, "Original constraints", "Review against original sources")!!
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, response.executionStatus)
        assertEquals(CompletionReviewGate.CODE, response.diagnosticCode)
        assertTrue(response.outputText.contains("\"candidate_tool\":\"done\""))
        assertFalse(response.outputText.contains("confirmation_template"))
        assertTrue(response.outputText.contains("candidate_to_verify"))
        assertTrue(response.outputText.contains("Original constraints"))
        gate.onToolSettled(ToolBinding.DEVICE_BOUND, ToolEffect.READ_ONLY, ToolExecutionStatus.SUCCEEDED)
        assertNotNull(request(gate, "Original constraints", "Review"))
        gate.recordDeliveredSources(emptySet())
        assertNull(request(gate, "Original constraints", "Review"))
    }

    @Test fun anotherMutationOrHumanResumeRequiresAnotherReview() {
        val gate = CompletionReviewGate(true)
        request(gate, "Task", "Review")
        gate.onToolSettled(ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING, ToolExecutionStatus.NOT_EXECUTED)
        gate.recordDeliveredSources(emptySet())
        assertNull(request(gate, "Task", "Review"))
        gate.onToolSettled(ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING, ToolExecutionStatus.SUCCEEDED)
        assertNotNull(request(gate, "Task", "Review"))
        gate.reset()
        assertNotNull(request(gate, "Task", "Review"))
    }

    @Test fun localWorkingStateUpdatesPreserveDeliveredSourceReview() {
        for (status in ToolExecutionStatus.entries) {
            for (changed in listOf<Boolean?>(null, false, true)) {
                val gate = CompletionReviewGate(true)
                request(gate, "Task", "Review", setOf("source"))
                gate.recordDeliveredSources(setOf("source"))
                val reviewedCandidate = gate.candidateId()
                assertFalse(gate.onToolSettled(ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING,
                    status, changed))
                assertEquals(reviewedCandidate, gate.candidateId())
                assertNull(request(gate, "Task", "Review", setOf("source")))
            }
        }
    }

    @Test fun localMutationDoesNotErasePartiallyDeliveredReviewSources() {
        val gate = CompletionReviewGate(true)
        val sources = setOf("first", "second")
        request(gate, "Task", "Review", sources)
        gate.recordDeliveredSources(setOf("first"))
        val candidateId = gate.candidateId()

        assertFalse(gate.onToolSettled(ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING,
            ToolExecutionStatus.SUCCEEDED, stateChanged = true))
        assertEquals(candidateId, gate.candidateId())
        assertEquals(setOf("second"), gate.pendingSources())
        assertNotNull(request(gate, "Task", "Review", sources))
        gate.recordDeliveredSources(setOf("second"))
        assertNull(request(gate, "Task", "Review", sources))
    }

    @Test fun provenNoChangePreservesReviewButDoesNotApproveAnUnreviewedCandidate() {
        val gate = CompletionReviewGate(true)
        request(gate, "Task", "Review", setOf("source"))
        assertFalse(gate.onToolSettled(ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING,
            ToolExecutionStatus.SUCCEEDED, stateChanged = false))
        assertNotNull(request(gate, "Task", "Review", setOf("source")))
        gate.recordDeliveredSources(setOf("source"))
        assertFalse(gate.onToolSettled(ToolBinding.TURN_LOCAL, ToolEffect.STATE_CHANGING,
            ToolExecutionStatus.SUCCEEDED, stateChanged = false))
        assertNull(request(gate, "Task", "Review", setOf("source")))
    }

    @Test fun failedOrInterruptedMutationsWithUnknownOrCommittedEffectsRequireNewReview() {
        for (status in listOf(ToolExecutionStatus.FAILED, ToolExecutionStatus.INTERRUPTED,
            ToolExecutionStatus.CANCELED, ToolExecutionStatus.SUCCEEDED)) {
            for (changed in listOf<Boolean?>(null, true)) {
                val gate = CompletionReviewGate(true)
                request(gate, "Task", "Review")
                gate.recordDeliveredSources(emptySet())
                assertTrue(gate.onToolSettled(ToolBinding.DEVICE_BOUND, ToolEffect.STATE_CHANGING,
                    status, changed))
                assertNotNull(request(gate, "Task", "Review"))
            }
        }
    }

    @Test fun actualChangeKnowledgeSurvivesReceiptSerializationWithoutDefaultingUnknownToFalse() {
        for (changed in listOf<Boolean?>(null, false, true)) {
            val result = ToolExecutionResult("receipt", ToolExecutionStatus.SUCCEEDED,
                ToolEvidenceStatus.NOT_REQUIRED, stateChanged = changed)
            val encoded = Json.encodeToString(ToolExecutionResult.serializer(), result)
            val decoded = Json.decodeFromString(ToolExecutionResult.serializer(), encoded)
            assertEquals(changed, decoded.stateChanged)
            val gate = CompletionReviewGate(true)
            request(gate, "Task", "Review")
            gate.recordDeliveredSources(emptySet())
            assertEquals(changed != false, gate.onToolSettled(ToolBinding.DEVICE_BOUND,
                ToolEffect.STATE_CHANGING, decoded.executionStatus, decoded.stateChanged))
        }
    }

    @Test fun disabledPolicyDoesNotAddAReview() {
        assertNull(request(CompletionReviewGate(false), "Task", "Review"))
    }

    @Test fun changingChecksDoesNotTurnOldImagesIntoNewProgress() {
        val gate = CompletionReviewGate(true)
        fun candidate(check: String) = CompletionReviewCandidate.from("done",
            """{"results":[{"text":"fact","evidence_refs":["a"]}],"requirement_checks":[{"requirement_id":"$check"}]}""")
        gate.request("task", "review", setOf("a"), candidate("one"))
        assertTrue(gate.recordDeliveredSources(setOf("a")))
        assertNotNull(gate.request("task", "review", setOf("a"), candidate("two")))
        assertFalse(gate.recordDeliveredSources(setOf("a")))
    }

    @Test fun newUserUpdateNeedsReviewEvenWhenOutputIsUnchanged() {
        val gate = CompletionReviewGate(true)
        val candidate = CompletionReviewCandidate.from("done", """{"results":[{"text":"fact","evidence_refs":["a"]}]}""")
        gate.request("task", "review", setOf("a"), candidate)
        gate.recordDeliveredSources(setOf("a"))
        assertNotNull(gate.request("task", "review", setOf("a"), candidate, listOf("new constraint")))
    }

    @Test fun sourceDeliveryWithoutARepeatedCandidateDoesNotPublishAnything() {
        val gate = CompletionReviewGate(true)
        val initial = request(gate, "task", "review", setOf("a", "b"))!!
        gate.recordDeliveredSources(setOf("a"))
        assertEquals(setOf("b"), gate.pendingSources())
        assertNotNull(request(gate, "task", "review", setOf("a", "b")))
        gate.recordDeliveredSources(setOf("b"))
        assertTrue(gate.reviewedResultIds().isNotEmpty())
        assertEquals(ToolExecutionStatus.NOT_EXECUTED, initial.executionStatus)
        assertNull(request(gate, "task", "review", setOf("a", "b")))
    }

    private fun request(gate: CompletionReviewGate, task: String, instruction: String,
                        sources: Set<String> = emptySet(), text: String = "finding"): com.droidance.core.agent.event.ToolExecutionResult? {
        val candidate = CompletionReviewCandidate.from("done",
            """{"status":"done","results":[{"text":"$text","evidence_refs":${JsonArray(sources.sorted().map(::JsonPrimitive))}}],"unresolved_requirements":[]}""")
        return gate.request(task, instruction, sources, candidate,
            confirmation = buildJsonObject { put("candidate_id", candidate.id) })
    }

    @Test fun sourceDeliveryAndRepeatedDraftAreNotExplicitApproval() {
        val gate = CompletionReviewGate(true)
        val candidate = CompletionReviewCandidate.from("done", """{"results":[{"text":"fact","evidence_refs":["a"]}]}""")
        gate.request("task", "review", setOf("a"), candidate)
        gate.recordDeliveredSources(setOf("a"))
        assertNotNull(gate.request("task", "review", setOf("a"), candidate))
        assertNotNull(gate.request("task", "review", setOf("a"), candidate,
            confirmation = buildJsonObject { put("candidate_id", "different") }))
        assertNull(gate.request("task", "review", setOf("a"), candidate,
            confirmation = buildJsonObject { put("candidate_id", candidate.id) }))
    }

    @Test fun whitespaceInPublicContentIsPartOfTheReviewedVersion() {
        val first = CompletionReviewCandidate.from("done", """{"results":[{"text":"a b"}]}""")
        val second = CompletionReviewCandidate.from("done", """{"results":[{"text":"a  b"}]}""")
        assertNotEquals(first.id, second.id)
    }

    @Test fun changingTextWithTheSameReferencesRequiresAnotherReviewRequest() {
        val gate = CompletionReviewGate(true)
        request(gate, "task", "review", setOf("source"))
        gate.recordDeliveredSources(setOf("source"))
        assertNull(request(gate, "task", "review", setOf("source")))
        assertNotNull(request(gate, "task", "review", setOf("source"), "different finding"))
        assertNotNull(request(gate, "task", "review", setOf("source"), "different finding"))
        gate.recordDeliveredSources(setOf("source"))
        assertNull(request(gate, "task", "review", setOf("source"), "different finding"))
    }

    @Test fun explicitTaskChangeInvalidatesPriorReview() {
        val gate = CompletionReviewGate(true)
        request(gate, "original", "review", setOf("source"))
        gate.recordDeliveredSources(setOf("source"))
        assertNotNull(request(gate, "changed request", "review", setOf("source")))
    }

    @Test fun focusedFileReviewRetainsExactWorkspaceReferenceWithoutChangingCandidateIdentity() {
        val gate = CompletionReviewGate(true)
        val body = """{"results":[{"text":"visible title","evidence_refs":["source"]}]}"""
        val reference = buildJsonObject {
            put("path", "completion.json")
            put("content_ref", "sha256:exact-version")
        }
        val candidate = CompletionReviewCandidate.from("done", body, reference)
        val sameClaimsAtAnotherVersion = CompletionReviewCandidate.from("done", body, buildJsonObject {
            put("path", "completion.json")
            put("content_ref", "sha256:later-version")
        })
        assertEquals(candidate.id, sameClaimsAtAnotherVersion.id)
        assertEquals(candidate.entries, sameClaimsAtAnotherVersion.entries)

        val response = gate.request("task", "review exact facts", setOf("source"), candidate)!!
        val details = Json.parseToJsonElement(response.outputText).jsonObject.getValue("details").jsonObject
        assertFalse(details.containsKey("completion_file"))
        assertEquals(candidate.originalArguments, details.getValue("candidate_to_verify").jsonObject)

        gate.recordDeliveredSources(mapOf("source" to "blob:grounded-image"))
        assertNull(gate.request("task", "review exact facts", setOf("source"), sameClaimsAtAnotherVersion,
            confirmation = buildJsonObject { put("candidate_id", sameClaimsAtAnotherVersion.id) }))
    }

    private fun CompletionReviewGate.recordDeliveredSources(sources: Set<String>) =
        recordDeliveredSources(sources.associateWith { "hash:$it" })
}
