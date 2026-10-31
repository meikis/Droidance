// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolActionEvidenceProjectorTest {
    @Test
    fun deliveryTransitionAndGoalProgressRemainIndependentFacts() {
        val evidence = projectToolActionEvidence(
            delivered = true,
            progressState = "STATE_CHANGED",
            rawChanged = true,
            semanticChanged = true,
            goalProgress = false
        )

        assertEquals(ActionDeliveryEvidence.ACTION_DELIVERED, evidence.delivery)
        assertEquals(StateTransitionEvidence.STATE_CHANGED, evidence.transition)
        assertEquals(GoalProgressEvidence.NOT_PROVEN, evidence.goalProgress)
    }

    @Test
    fun rawPixelsNeverBecomeGoalProgress() {
        val evidence = projectToolActionEvidence(
            delivered = true,
            progressState = "RAW_CHANGED",
            rawChanged = true,
            semanticChanged = false,
            goalProgress = false
        )

        assertEquals(ActionDeliveryEvidence.ACTION_DELIVERED, evidence.delivery)
        assertEquals(StateTransitionEvidence.RAW_CHANGED, evidence.transition)
        assertEquals(GoalProgressEvidence.NOT_PROVEN, evidence.goalProgress)
    }

    @Test
    fun explicitGoalStageEvidenceIsRecordedWithoutChangingDelivery() {
        val evidence = projectToolActionEvidence(
            delivered = null,
            progressState = "GOAL_PROGRESS",
            rawChanged = true,
            semanticChanged = true,
            goalProgress = true
        )

        assertEquals(ActionDeliveryEvidence.UNKNOWN, evidence.delivery)
        assertEquals(StateTransitionEvidence.STATE_CHANGED, evidence.transition)
        assertEquals(GoalProgressEvidence.GOAL_PROGRESS, evidence.goalProgress)
    }
}
