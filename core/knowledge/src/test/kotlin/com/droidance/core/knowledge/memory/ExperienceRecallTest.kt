// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import org.junit.Assert.*
import org.junit.Test

class ExperienceRecallTest {
    private val weights = ExperienceRecall.Weights(success = 2, failure = 4)

    @Test fun userWordingReachesAnEnglishMethodThroughTheAppLabelAndRecallPhrases() {
        val indexed = ExperienceRecall.indexTokens(update(recallPhrases = listOf("预定会议")), appLabel = "腾讯会议").toSet()
        val query = ExperienceRecall.queryTokens("帮我在腾讯会议预定明天下午的会议", emptyList())

        assertTrue("腾讯会议" in indexed && "预定会议" in indexed && "com.example.meeting" in indexed)
        assertTrue(query.any { it in indexed })
        assertTrue(ExperienceRecall.queryTokens("帮我预定明天下午的会议", emptyList()).any { it in indexed })
    }

    @Test fun executionContextLeadsSoALongMessageCannotTruncateIt() {
        val longMessage = (0 until 80).map { Char(0x4e00 + it * 7) }.joinToString("")
        val tokens = ExperienceRecall.queryTokens(longMessage, listOf("com.example.meeting", "腾讯会议"))

        assertEquals(64, tokens.size)
        assertEquals(listOf("com.example.meeting", "腾讯会议"), tokens.take(2))
    }

    @Test fun rankingPrefersQueryOverlapThenAssessedSuccessAndDemotesFailures() {
        val query = listOf("com.example.meeting", "腾讯会议", "会议")
        val relevant = candidate("relevant", setOf("com.example.meeting", "腾讯会议"), updatedAt = 1)
        val generic = candidate("generic", setOf("会议"), updatedAt = 9)
        val failing = candidate("failing", setOf("com.example.meeting", "腾讯会议"), failed = 3, updatedAt = 5)
        val proven = candidate("proven", setOf("com.example.meeting", "腾讯会议"), succeeded = 2, updatedAt = 0)

        assertEquals(listOf("proven", "relevant", "generic", "failing"),
            ExperienceRecall.rank(listOf(generic, failing, relevant, proven), query, weights).map { it.id })
    }

    @Test fun equalScoresKeepTheMostRecentlyUpdatedMethodFirst() {
        val older = candidate("older", setOf("会议"), updatedAt = 1)
        val newer = candidate("newer", setOf("会议"), updatedAt = 2)

        assertEquals(listOf("newer", "older"), ExperienceRecall.rank(listOf(older, newer), listOf("会议"), weights).map { it.id })
    }

    @Test fun recallPhrasesAreBoundedOptionalMetadata() {
        ExperienceProjection.validateUpdate(update(recallPhrases = emptyList()))
        listOf(listOf(" "), listOf("x".repeat(81)), List(17) { "phrase $it" }).forEach { phrases ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ExperienceProjection.validateUpdate(update(recallPhrases = phrases))
            }
            assertEquals("EXPERIENCE_RECALL_PHRASES_INVALID", error.message)
        }
    }

    private fun candidate(id: String, tokens: Set<String>, succeeded: Int = 0, failed: Int = 0, updatedAt: Long) =
        ExperienceRecall.Candidate(id, tokens, succeeded, failed, updatedAt)

    private fun update(recallPhrases: List<String>) = ExperienceUpdate("schedule-meeting", 0, "Schedule a meeting",
        "com.example.meeting", listOf("start_time", "duration", "topic"), "The meeting home screen is visible",
        "Open the schedule form, set the topic, start time and duration, then save", "Inspect the time picker before saving",
        "The scheduled meeting appears in the list", "Re-observe the form and replan if a field differs", "", "unknown",
        "Scoped task-agent assessment only", listOf("obs-1"), recallPhrases)
}
