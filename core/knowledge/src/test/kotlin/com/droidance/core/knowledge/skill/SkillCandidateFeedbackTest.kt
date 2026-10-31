// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.skill

import com.droidance.core.knowledge.memory.ExperienceUseRecord
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillCandidateFeedbackTest {
    @Test fun skillFeedbackUsesOnlyTheExactRevisionAndMostRecentWindow() {
        val feedback = SkillCandidateFeedback.fromSkillAssessments("current", listOf(
            assessment("older", "succeeded", 100), assessment("current", "succeeded", 1),
            assessment("current", "failed", 3), assessment("current", "unknown", 2)
        ), windowLimit = 2)

        assertEquals(2, feedback.recentCount)
        assertEquals(0, feedback.succeeded)
        assertEquals(1, feedback.failed)
        assertEquals(1, feedback.unknown)
        assertEquals("failed", feedback.latestOutcome)
        assertEquals("current", feedback.toJson().getValue("revision").jsonPrimitive.content)
        assertEquals(2, feedback.toJson().getValue("window_limit").jsonPrimitive.int)
    }

    @Test fun experienceAdoptionAndUnknownNeverBecomeSuccessAndOtherRevisionsAreExcluded() {
        val ref = "experience:method:2"
        val feedback = SkillCandidateFeedback.fromExperienceUses(ref, listOf(
            use("experience:method:1", "succeeded", 50), use(ref, "adopted", 30),
            use(ref, "unknown", 20), use(ref, "failed", 10)
        ))

        assertEquals(3, feedback.recentCount)
        assertEquals(0, feedback.succeeded)
        assertEquals(1, feedback.failed)
        assertEquals(1, feedback.unknown)
        assertEquals(1, feedback.unassessed)
        assertEquals("unassessed", feedback.latestOutcome)
    }

    @Test fun feedbackIsBoundedAndReportsNoSamplesWithoutInventingAnOutcome() {
        val feedback = SkillCandidateFeedback.fromSkillAssessments("r", listOf(
            assessment("r", "failed", 1, "Long\n".repeat(200))))
        assertTrue(feedback.latestSummary!!.length <= 120)
        assertFalse(feedback.latestSummary!!.contains('\n'))
        assertEquals(80, feedback.promptSummary(80).length)
        val empty = SkillCandidateFeedback.fromSkillAssessments("r", emptyList())
        assertEquals(0, empty.recentCount)
        assertNull(empty.latestOutcome)
        assertFalse(empty.toJson().containsKey("latest_outcome"))
    }

    private fun assessment(revision: String, outcome: String, at: Long, summary: String = outcome) = buildJsonObject {
        put("revision", revision); put("outcome", outcome); put("assessed_at", at); put("summary", summary)
    }

    private fun use(ref: String, outcome: String, at: Long) = ExperienceUseRecord(
        "use-$ref-$at", ref, "thread", "turn", at, outcome, outcome, recordedAt = at)
}
