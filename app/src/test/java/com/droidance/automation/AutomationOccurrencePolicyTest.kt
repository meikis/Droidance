// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutomationOccurrencePolicyTest {
    @Test
    fun scheduledOccurrenceKeyIsStableForOneDefinitionVersionAndDeadline() {
        val first = AutomationOccurrenceIds.scheduled("automation-1", 3, 1_789_000_000_000L)
        val duplicate = AutomationOccurrenceIds.scheduled("automation-1", 3, 1_789_000_000_000L)
        val edited = AutomationOccurrenceIds.scheduled("automation-1", 4, 1_789_000_000_000L)

        assertEquals(first, duplicate)
        assertNotEquals(first, edited)
    }

    @Test
    fun deliveryFailureNeverChangesACompletedExecutionBackToQueued() {
        val completed = AutomationOccurrence(
            id = "occurrence-1",
            automationId = "automation-1",
            definitionVersion = 1,
            scheduledForMs = 1_789_000_000_000L,
            executionState = AutomationExecutionState.SUCCEEDED,
            deliveryState = AutomationDeliveryState.PENDING
        )

        val failedDelivery = AutomationOccurrencePolicy.deliveryFailed(
            completed,
            reasonCode = "CHANNEL_OFFLINE",
            attemptedAtMs = 1_789_000_010_000L
        )

        assertEquals(AutomationExecutionState.SUCCEEDED, failedDelivery.executionState)
        assertEquals(AutomationDeliveryState.FAILED, failedDelivery.deliveryState)
        assertEquals(1, failedDelivery.deliveryAttempts)
    }
}
