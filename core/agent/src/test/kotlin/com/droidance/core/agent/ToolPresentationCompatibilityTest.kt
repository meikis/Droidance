// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.ResultImageRole
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.agent.event.ToolPresentationCategory
import com.droidance.core.agent.event.ToolPresentationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPresentationCompatibilityTest {
    @Test
    fun unavailableCompletionToolUsesDiagnosticPresentationAfterLocalRejection() {
        val configuredCompletion = ToolPresentationSnapshot(
            labelResourceKey = "tool.done",
            category = ToolPresentationCategory.COMPLETION,
            resultImageRole = ResultImageRole.NONE
        )

        val frozen = compatibleToolPresentation(
            plannedEffect = ToolEffect.STATE_CHANGING,
            configured = configuredCompletion
        )

        assertEquals(ToolPresentationCategory.DIAGNOSTIC, frozen.category)
        assertEquals("tool.unknown", frozen.labelResourceKey)
    }

    @Test
    fun matchingCompletionPresentationIsPreserved() {
        val configuredCompletion = ToolPresentationSnapshot(
            labelResourceKey = "tool.done",
            category = ToolPresentationCategory.COMPLETION,
            resultImageRole = ResultImageRole.NONE
        )

        assertEquals(
            configuredCompletion,
            compatibleToolPresentation(ToolEffect.COMPLETION, configuredCompletion)
        )
    }
}
