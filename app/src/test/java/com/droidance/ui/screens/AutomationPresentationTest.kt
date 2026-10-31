// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.screens

import com.droidance.automation.*
import org.junit.Assert.*
import org.junit.Test

class AutomationPresentationTest {
    private fun definition(enabled: Boolean = true, ended: Boolean = false) = AutomationDefinition(
        id = "plan", version = 1, title = "A task", instruction = "Do the task", titleWasGenerated = true,
        schedule = AutomationSchedule(AutomationCadence.DAILY, 9, 0, timeZoneId = "UTC"),
        enabled = enabled, finishedAtMs = if (ended) 1L else 0L
    )
    private fun occurrence(state: AutomationExecutionState, delivery: AutomationDeliveryState = AutomationDeliveryState.NOT_REQUESTED) =
        AutomationOccurrence("run", "plan", 1, 1L, executionState = state, deliveryState = delivery)

    @Test fun endedPlansNeverAlsoAppearAsActiveOrPaused() {
        for (enabled in listOf(true, false)) {
            val plan = definition(enabled, ended = true)
            assertEquals(AutomationPlanState.ENDED, automationPlanState(plan))
            assertTrue(matchesAutomationFilter(plan, AutomationListFilter.ALL))
            assertTrue(matchesAutomationFilter(plan, AutomationListFilter.ENDED))
            assertFalse(matchesAutomationFilter(plan, AutomationListFilter.ACTIVE))
            assertFalse(matchesAutomationFilter(plan, AutomationListFilter.PAUSED))
        }
    }
    @Test fun filtersKeepActiveAndPausedPlansSeparate() {
        for ((enabled, expected) in listOf(true to AutomationListFilter.ACTIVE, false to AutomationListFilter.PAUSED)) {
            val plan = definition(enabled)
            assertTrue(matchesAutomationFilter(plan, expected))
            assertEquals(1, AutomationListFilter.entries.drop(1).count { matchesAutomationFilter(plan, it) })
        }
    }
    @Test fun previousCompletionDoesNotLabelTheCurrentPlanAsCompleted() {
        for (state in listOf(AutomationExecutionState.SUCCEEDED, AutomationExecutionState.SKIPPED, AutomationExecutionState.CANCELLED)) {
            assertNull(automationListExecutionState(occurrence(state)))
        }
        assertNull(automationListExecutionState(null))
    }
    @Test fun ongoingWorkAndUnresolvedResultsRemainVisible() {
        for (state in listOf(AutomationExecutionState.QUEUED, AutomationExecutionState.RUNNING, AutomationExecutionState.FAILED,
            AutomationExecutionState.WAITING_FOR_USER, AutomationExecutionState.UNKNOWN)) {
            assertEquals(state, automationListExecutionState(occurrence(state)))
        }
    }
    @Test fun successfulExecutionDoesNotHideFailedDelivery() {
        assertTrue(automationDeliveryNeedsAttention(occurrence(AutomationExecutionState.SUCCEEDED, AutomationDeliveryState.FAILED)))
        assertFalse(automationDeliveryNeedsAttention(occurrence(AutomationExecutionState.SUCCEEDED, AutomationDeliveryState.HANDOFF_ACCEPTED)))
        assertFalse(automationDeliveryNeedsAttention(null))
    }
}
