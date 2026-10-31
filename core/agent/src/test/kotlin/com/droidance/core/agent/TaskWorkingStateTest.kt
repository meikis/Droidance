// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWorkingStateTest {
    @Test
    fun rendersOnlyCurrentClosedStateAndBoundedRecoveryContext() {
        val rendered = TaskWorkingState(
            checkpoint = "revision=8 latest_grounded=obs_7",
            latestClosedObservationId = "obs_7",
            semanticContextKey = "page:checkout",
            verifiedEvidence = "fact_3=visible total",
            recentActions = listOf("round=3 tap failed", "round=4 scroll state_change"),
            blockedActions = listOf("page:checkout|action=tap:submit")
        ).render()

        assertTrue(rendered.contains("latest_closed_observation_id: obs_7"))
        assertTrue(rendered.contains("semantic_context_key: page:checkout"))
        assertTrue(rendered.contains("fact_3=visible total"))
        assertTrue(rendered.contains("round=4 scroll state_change"))
        assertTrue(rendered.contains("page:checkout|action=tap:submit"))
    }

    @Test
    fun omitsEmptySectionsInsteadOfInventingState() {
        val rendered = TaskWorkingState(
            checkpoint = "revision=0",
            latestClosedObservationId = null,
            semanticContextKey = null,
            verifiedEvidence = "",
            recentActions = emptyList(),
            blockedActions = emptyList()
        ).render()

        assertTrue(rendered.contains("revision=0"))
        assertFalse(rendered.contains("latest_closed_observation_id:"))
        assertFalse(rendered.contains("recent_actions:"))
        assertFalse(rendered.contains("blocked_actions:"))
    }

    @Test
    fun boundedRenderKeepsEvidenceAndLatestRecoveryContextVisible() {
        val rendered = TaskWorkingState(
            checkpoint = "checkpoint-${"c".repeat(2_000)}",
            latestClosedObservationId = "obs_9",
            semanticContextKey = "page:results",
            verifiedEvidence = "evidence-${"e".repeat(5_000)}",
            recentActions = (1..12).map { "action-$it-${"a".repeat(400)}" },
            blockedActions = (1..12).map { "blocked-$it-${"b".repeat(400)}" }
        ).render(maxChars = 1_200)

        assertTrue(rendered.length <= 1_200)
        assertTrue(rendered.contains("verified_evidence:"))
        assertTrue(rendered.contains("evidence-"))
        assertTrue(rendered.contains("action-12"))
        assertTrue(rendered.contains("blocked-12"))
        assertFalse(rendered.contains("action-1\n"))
        assertFalse(rendered.contains("blocked-1\n"))
    }
}
