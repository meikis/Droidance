// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

/** Projects independent runtime facts without interpreting the user's business goal. */
fun projectToolActionEvidence(
    delivered: Boolean?,
    progressState: String?,
    rawChanged: Boolean?,
    semanticChanged: Boolean?,
    goalProgress: Boolean?
): ToolActionEvidence {
    val normalizedState = progressState?.trim()?.uppercase()
    val delivery = when (delivered) {
        true -> ActionDeliveryEvidence.ACTION_DELIVERED
        false -> ActionDeliveryEvidence.NOT_DELIVERED
        null -> ActionDeliveryEvidence.UNKNOWN
    }
    val transition = when {
        semanticChanged == true || normalizedState in setOf("STATE_CHANGED", "GOAL_PROGRESS") ->
            StateTransitionEvidence.STATE_CHANGED
        rawChanged == true || normalizedState == "RAW_CHANGED" ->
            StateTransitionEvidence.RAW_CHANGED
        normalizedState == "UNCHANGED" -> StateTransitionEvidence.UNCHANGED
        else -> StateTransitionEvidence.UNKNOWN
    }
    val goal = if (goalProgress == true || normalizedState == "GOAL_PROGRESS") {
        GoalProgressEvidence.GOAL_PROGRESS
    } else {
        GoalProgressEvidence.NOT_PROVEN
    }
    return ToolActionEvidence(delivery, transition, goal)
}
