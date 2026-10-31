// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReasoningControlTest {
    private val policy = ReasoningControlPolicy(ReasoningEffort.MEDIUM,
        listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), 2)

    private fun suggestion(value: String) = ReasoningEffortProtocol.extract(listOf(
        ToolCall("call", "screen_act", """{"actions":[],"next_reasoning_effort":$value}""")))

    @Test fun focusedRoundsUseThePolicyFloorUnlessTheUserFixedTheEffort() {
        val control = ReasoningControl(ReasoningControlPolicy(ReasoningEffort.LOW,
            listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), 1, ReasoningEffort.MEDIUM))
        assertEquals(ReasoningEffort.LOW, control.effortFor(focusedRound = false))
        assertEquals(ReasoningEffort.MEDIUM, control.effortFor(focusedRound = true))
        control.restore(control.propose(suggestion("\"high\"")).metadata())
        assertEquals(ReasoningEffort.HIGH, control.effortFor(focusedRound = true))
        control.fixedEffort = ReasoningEffort.MINIMAL
        assertEquals(ReasoningEffort.MINIMAL, control.effortFor(focusedRound = true))
    }

    @Test fun optionalHintsAreRemovedBeforeExecutionAndConflictsNeverChooseAnArbitraryLevel() {
        val low = suggestion("\"low\"")
        assertEquals(ReasoningEffort.LOW, low.suggestion)
        assertEquals("""{"actions":[]}""", low.calls.single().arguments)
        val conflicting = ReasoningEffortProtocol.extract(low.calls + listOf(
            ToolCall("one", "read", """{"next_reasoning_effort":"low"}"""),
            ToolCall("two", "read", """{"next_reasoning_effort":"high"}""")))
        assertEquals("conflicting", conflicting.status)
        assertNull(conflicting.suggestion)
        assertTrue(conflicting.calls.none { "next_reasoning_effort" in it.arguments })
        listOf("3", "null", "{}", "\"unknown\"").forEach { raw ->
            assertEquals("invalid", suggestion(raw).status)
            assertEquals("""{"actions":[]}""", suggestion(raw).calls.single().arguments)
        }
    }

    @Test fun effortChangesOnlyAffectFollowingCommittedDecisionsAndSurviveReconstruction() {
        val control = ReasoningControl(policy)
        assertEquals(ReasoningEffort.MEDIUM, control.requestedEffort)
        val first = control.propose(suggestion("\"low\""))
        assertEquals("held_by_change_interval", first.status)
        control.restore(first.metadata())
        val second = control.propose(suggestion("\"low\""))
        assertEquals("accepted", second.status)
        assertEquals(ReasoningEffort.MEDIUM, control.requestedEffort)
        control.restore(second.metadata())
        assertEquals(ReasoningEffort.LOW, control.requestedEffort)
        val reconstructed = ReasoningControl(policy).also { it.restore(second.metadata()) }
        assertEquals(ReasoningEffort.LOW, reconstructed.requestedEffort)
        assertEquals("held_by_change_interval", reconstructed.propose(suggestion("\"high\"")).status)
        reconstructed.restore(reconstructed.propose(ReasoningEffortProtocol.extract(emptyList())).metadata())
        assertEquals("accepted", reconstructed.propose(suggestion("\"high\"")).status)
    }

    @Test fun fixedPreferencesIncludingProviderDefaultCannotBeOverriddenByAgentMetadata() {
        ReasoningEffort.entries.forEach { fixed ->
            val control = ReasoningControl(policy).also { it.fixedEffort = fixed }
            repeat(3) {
                val decision = control.propose(suggestion("\"low\""))
                assertEquals("fixed_preference", decision.status)
                control.restore(decision.metadata())
                assertEquals(fixed, control.requestedEffort)
            }
        }
    }

    @Test fun schemaAdvertisesOnlyConfiguredLevelsAndHintsRemainOptional() {
        val tools = ReasoningEffortProtocol.addParameter(listOf(ToolDefinition("read", "Read", emptyMap())), "hint", policy)
        val parameter = tools.single().parameters.getValue(ReasoningEffortProtocol.ARGUMENT)
        assertFalse(parameter.required)
        assertEquals(listOf("low", "medium", "high"), parameter.enumValues)
        val control = ReasoningControl(policy)
        val outside = control.propose(suggestion("\"max\""))
        assertEquals("outside_policy", outside.status)
        assertEquals(ReasoningEffort.MEDIUM, outside.effort)
    }
}
