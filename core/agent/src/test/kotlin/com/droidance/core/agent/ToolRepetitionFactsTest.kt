// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolRepetitionFactsTest {
    private fun window() = ToolRepetitionFacts(ToolRepetitionPolicy(8, 3,
        setOf("action_title"), setOf("request_id")))

    @Test fun cosmeticFieldsDoNotHideRepeatedResultsAndWarningsAreBounded() {
        val facts = window()
        for (i in 1..2) assertNull(facts.record("read", """{"key":"same","action_title":"$i"}""",
            ToolProtocolResult.success("""{"state":"pending","request_id":"$i"}""")))
        assertNotNull(facts.record("read", """{"action_title":"third","key":"same"}""",
            ToolProtocolResult.success("""{"request_id":"three","state":"pending"}""")))
        assertNull(facts.record("read", """{"key":"same"}""",
            ToolProtocolResult.success("""{"state":"pending"}""")))
    }

    @Test fun changingResultsAndNestedSourceFieldsRemainInformation() {
        val facts = window()
        repeat(8) { assertNull(facts.record("read", "{}",
            ToolProtocolResult.success("""{"data":{"request_id":"$it"}}"""))) }
        repeat(8) { assertNull(facts.record("read", "{}", ToolProtocolResult.success("Value $it"))) }
        repeat(8) { assertNull(facts.record("read", "{}", ToolProtocolResult.success("same preview")
            .copy(data = Json.parseToJsonElement("""{"request_id":"$it"}""")))) }
    }

    @Test fun aChangedOutcomeOrTaskResetStartsANewWindow() {
        val facts = window()
        repeat(2) { assertNull(facts.record("read", "{}", ToolProtocolResult.success("pending"))) }
        assertNull(facts.record("read", "{}", ToolProtocolResult.success("finished")))
        repeat(2) { assertNull(facts.record("read", "{}", ToolProtocolResult.success("pending"))) }
        facts.reset()
        assertNull(facts.record("read", "{}", ToolProtocolResult.success("pending")))
    }

    @Test fun aNewStreakCanWarnAfterAChangedOutcome() {
        val facts = window()
        repeat(2) { assertNull(facts.record("read", "{}", ToolProtocolResult.success("pending"))) }
        assertNotNull(facts.record("read", "{}", ToolProtocolResult.success("pending")))
        assertNull(facts.record("read", "{}", ToolProtocolResult.success("changed")))
        repeat(2) { assertNull(facts.record("read", "{}", ToolProtocolResult.success("pending"))) }
        assertNotNull(facts.record("read", "{}", ToolProtocolResult.success("pending")))
    }
}
