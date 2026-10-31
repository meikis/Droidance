// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionRejectionCycleGuardTest {
    @Test fun emptyAndPreviouslyDeliveredSourcesCannotRenewTheReviewBudget() {
        val guard = CompletionRejectionCycleGuard(1)
        fun deliver(vararg contentRefs: String) {
            val sources = contentRefs.associateWith { it }
            guard.recordReviewedSources(sources, sources.keys)
        }
        deliver("sha256:a", "sha256:b")
        assertFalse(guard.reviewRequired())
        deliver()
        // A changing current frame is not a candidate's requested original source.
        guard.recordReviewedSources(mapOf("unrelated-current-frame" to "sha256:new"), setOf("requested-original"))
        deliver("sha256:b", "sha256:a")
        assertTrue(guard.reviewRequired())
        deliver("sha256:c")
        assertFalse(guard.reviewRequired())
        assertTrue(guard.reviewRequired())
        guard.reset()
        assertFalse(guard.reviewRequired())
        deliver("sha256:a")
        assertFalse(guard.reviewRequired())
    }

    @Test
    fun missingSignalHasARepairOpportunityAndSharesTheRejectionBudget() {
        val guard = CompletionRejectionCycleGuard(3)
        assertFalse(guard.rejectMissingSignal())
        assertFalse(rejected(guard))
        assertTrue(guard.rejectMissingSignal())
        guard.reset()
        assertFalse(guard.rejectMissingSignal())
    }

    @Test
    fun identicalCompletionAgainstUnchangedEvidenceStopsOnRepeat() {
        val guard = CompletionRejectionCycleGuard(3)

        assertFalse(rejected(guard))
        assertTrue(rejected(guard))
    }

    @Test
    fun newGroundedEvidenceAllowsAnotherCompletionDecision() {
        val guard = CompletionRejectionCycleGuard(3)
        assertFalse(rejected(guard))

        assertFalse(rejected(guard, availableEvidenceRefs = "obs_1,obs_2"))
    }

    @Test
    fun correctedModelReferencesAllowAnotherCompletionDecision() {
        val guard = CompletionRejectionCycleGuard(3)
        assertFalse(rejected(guard, claimedEvidenceRefs = "obs_1"))

        assertFalse(rejected(guard, claimedEvidenceRefs = "obs_1,obs_2"))
    }

    @Test
    fun correctedClaimsAgainstTheSameReferencesAreNotAnIdenticalRejection() {
        val guard = CompletionRejectionCycleGuard(3)
        assertFalse(rejected(guard, claimedResults = "[]"))
        assertFalse(rejected(guard, claimedResults = "[{\"result\":\"Verified\",\"evidence_refs\":[\"obs_1\"]}]"))
        assertTrue(rejected(guard, claimedResults = "[{\"result\":\"Verified\",\"evidence_refs\":[\"obs_1\"]}]"))
    }

    @Test
    fun resetClearsThePreviousRejectionBoundary() {
        val guard = CompletionRejectionCycleGuard(3)
        assertFalse(rejected(guard))
        guard.reset()

        assertFalse(rejected(guard))
    }

    @Test
    fun rewordingAndChangingFrameIdsCannotRenewTheRejectionBudget() {
        val guard = CompletionRejectionCycleGuard(3)
        assertFalse(rejected(guard, claimedResults = "first", availableEvidenceRefs = "obs_1"))
        assertFalse(rejected(guard, claimedResults = "second", availableEvidenceRefs = "obs_1,obs_2"))
        assertTrue(rejected(guard, claimedResults = "third", availableEvidenceRefs = "obs_1,obs_2,obs_3"))
        guard.reset()
        assertFalse(rejected(guard))
    }

    @Test fun aNormalSourceReviewDoesNotConsumePriorProtocolFailures() {
        val guard = CompletionRejectionCycleGuard(3)
        assertFalse(rejected(guard, claimedResults = "first"))
        assertFalse(rejected(guard, claimedResults = "second"))
        assertFalse(guard.reviewRequired())
        assertFalse(guard.reviewRequired())
        assertFalse(guard.reviewRequired())
        assertTrue(guard.reviewRequired())
        guard.reset()
        assertFalse(guard.reviewRequired())
    }

    @Test fun shrinkingReportedStructuralDefectsRenewsRepairOpportunity() {
        val guard = CompletionRejectionCycleGuard(3)
        fun attempt(indices: String) = guard.isRepeated("done", "done", "candidate", "source", "source",
            "source", "source", """{"code":"COMPLETION_RESULTS_REQUIRED","details":{"unbound_requirement_indices":[$indices]}}""")
        assertFalse(attempt("0,1,2,3"))
        assertFalse(attempt("0,1,2"))
        assertFalse(attempt("0,1"))
        assertFalse(attempt("0"))
        assertTrue(attempt("0"))
    }

    @Test fun newlyExposedFieldGetsRepairButReturningToOldDefectsDoesNotRenewBudget() {
        val guard = CompletionRejectionCycleGuard(3)
        fun attempt(field: String) = guard.isRepeated("done", "done", field, "source", "source",
            "source", "source", """{"code":"ARGUMENT_SCHEMA_MISMATCH","details":{"reason":"invalid_value","field":"$field"}}""")
        assertFalse(attempt("requirement_checks"))
        assertFalse(attempt("results[0].deeplink"))
        assertFalse(attempt("requirement_checks"))
        assertTrue(attempt("results[0].deeplink"))
    }

    private fun rejected(
        guard: CompletionRejectionCycleGuard,
        claimedEvidenceRefs: String = "obs_1",
        availableEvidenceRefs: String = "obs_1",
        claimedResults: String = "[]"
    ): Boolean = guard.isRepeated(
        toolName = "done",
        completionStatus = "done",
        claimedResults = claimedResults,
        claimedEvidenceRefs = claimedEvidenceRefs,
        claimedTerminalObservationRef = "obs_1",
        availableEvidenceRefs = availableEvidenceRefs,
        visibleTerminalObservationRef = "obs_1",
        rejectionResult = "{\"code\":\"COMPLETION_EVIDENCE_REQUIRED\"}"
    )
}
