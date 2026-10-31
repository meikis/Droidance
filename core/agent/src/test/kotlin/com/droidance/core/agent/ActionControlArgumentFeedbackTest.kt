// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionControlArgumentFeedbackTest {
    private val parameters = linkedMapOf(
        "status" to ToolParameter("string", "", required = true),
        "results" to ToolParameter("array", "", required = true, items = ToolParameter("string", "")),
        "unresolved_requirements" to ToolParameter("array", "", required = true, items = ToolParameter("string", ""))
    )

    @Test fun malformedCompletionReportsOneParserIssueWithoutInventingMissingFields() {
        val call = ToolCall("completion", "done",
            """{"status":"done","results":["Unit\|price"],"unresolved_requirements":[]}""")
        val control = ActionControlProtocol.extract(listOf(call)).controls.getValue(call.id)
        val result = ActionControlProtocol.argumentFailure(control, parameters)
        assertEquals("ARGUMENT_SCHEMA_MISMATCH", result.diagnosticCode)
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        val issues = details.getValue("schema_issues").jsonArray
        assertEquals(1, issues.size)
        assertTrue(issues.single().jsonPrimitive.content.contains("offset"))
        assertEquals(0, details.getValue("missing_parameters").jsonArray.size)
    }

    @Test fun parsedObjectStillReportsActualMissingFieldsAndConcreteFailure() {
        val control = ActionControlProtocol.Control("completion", null,
            invalidArgument = CompletionSubmissionProtocol.ARGUMENT,
            invalidReason = "deliverable_result_index_out_of_range",
            originalArguments = """{"status":"done"}""")
        val result = ActionControlProtocol.argumentFailure(control, parameters)
        val details = Json.parseToJsonElement(result.outputText).jsonObject.getValue("details").jsonObject
        assertEquals("deliverable_result_index_out_of_range", details.getValue("reason").jsonPrimitive.content)
        assertEquals(listOf("results", "unresolved_requirements"),
            details.getValue("missing_parameters").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, details.getValue("schema_issues").jsonArray.size)
    }
}
