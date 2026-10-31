// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent

import com.droidance.core.agent.*
import com.droidance.core.device.agent.*

import com.droidance.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.agent.tools.ToolCatalog
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedActionProtocolTest {
    @Test
    fun screenDependentAndFinalizingToolsRequireScreenEvidenceNotes() {
        val tools = listOf(
            ToolDefinition(
                name = "screen_act",
                description = "Screen action",
                parameters = mapOf("x" to ToolParameter("integer", "X coordinate"))
            ),
            ToolDefinition(
                name = "done",
                description = "Finish the task",
                parameters = mapOf("summary" to ToolParameter("string", "Summary"))
            ),
            ToolDefinition(
                name = "present_result_card",
                description = "Present structured results",
                parameters = emptyMap()
            ),
            ToolDefinition(
                name = "android_act",
                description = "Open an app",
                parameters = emptyMap()
            )
        )

        val enriched = GroundedActionProtocol.addScreenEvidenceParameter(
            tools = tools,
            parameterDescription = "Literal facts visible in the attached current screen.",
            applyTo = ToolCatalog.screenEvidenceToolNames
        )

        val click = enriched.first { it.name == "screen_act" }
        assertFalse(
            click.parameters.getValue(GroundedActionProtocol.SCREEN_EVIDENCE_ARGUMENT).required
        )
        assertEquals("Literal facts visible in the attached current screen.",
            click.parameters.getValue(GroundedActionProtocol.SCREEN_EVIDENCE_ARGUMENT).description)
        assertFalse(
            enriched.first { it.name == "done" }
                .parameters.containsKey(GroundedActionProtocol.SCREEN_EVIDENCE_ARGUMENT)
        )
        assertFalse(
            enriched.first { it.name == "present_result_card" }
                .parameters.containsKey(GroundedActionProtocol.SCREEN_EVIDENCE_ARGUMENT)
        )
        assertFalse(
            enriched.first { it.name == "android_act" }
                .parameters.containsKey(GroundedActionProtocol.SCREEN_EVIDENCE_ARGUMENT)
        )
    }

    private val records = """[{"record_id":"row-1","fields":{"label":{"value":"Visible title","source_ref":"source-a"}},"assessment":"still unresolved","uncertainties":["value unread"]}]"""

    @Test fun recordsShareTheWorkspaceShapeAndStaySeparateFromActionDelivery() {
        val result = GroundedActionProtocol.extractScreenEvidence(listOf(ToolCall("call-1", "screen_act",
            """{"actions":[{"op":"click","x":10,"y":20}],"screen_evidence":$records}"""))) { it == "source-a" }
        assertEquals(records, result.evidence.single().records.toString())
        assertEquals("call-1", result.evidence.single().toolCallId)
        assertTrue(result.retentionOutcomes.values.none { it.records == null })
        assertEquals("""{"actions":[{"op":"click","x":10,"y":20}]}""", result.toolCalls.single().arguments)
    }

    @Test fun unknownSourcesAndInvalidRecordShapesDoNotRejectTheDeviceAction() {
        for (note in listOf(records.replace("source-a", "unknown"), "[]", "\"plain prose\"",
            records.replace("\"fields\":{", "\"fields\":["))) {
            val result = GroundedActionProtocol.extractScreenEvidence(listOf(ToolCall("call", "screen_act",
                """{"actions":[{"op":"wait","ms":10}],"screen_evidence":$note}"""))) { it == "source-a" }
            // Invalid entire JSON belongs to ordinary argument validation, not note extraction.
            if (!result.toolCalls.single().arguments.contains("screen_evidence")) {
                assertTrue(result.evidence.isEmpty())
                assertEquals(listOf("call"), result.retentionOutcomes.filterValues { it.records == null }.keys.toList())
                assertTrue(result.toolCalls.single().arguments.contains("actions"))
            }
        }
    }

    @Test fun optionalOmissionNeedsNoEvidenceRepairAndMalformedArgumentsRemainForNormalValidation() {
        val call = ToolCall("call", "screen_act", """{"actions":[{"op":"screenshot"}]}""")
        val result = GroundedActionProtocol.extractScreenEvidence(listOf(call)) { false }
        assertEquals(listOf(call), result.toolCalls)
        assertTrue(result.evidence.isEmpty())
        assertTrue(result.retentionOutcomes.values.none { it.records == null })
        val malformed = call.copy(arguments = """{"actions":[""")
        assertEquals(listOf(malformed), GroundedActionProtocol.extractScreenEvidence(listOf(malformed)) { false }.toolCalls)
    }
    @Test fun rejectedRecordsCarryExactPathAndOriginalPayloadForAnIndependentRepair() {
        val missingId = records.replace("\"record_id\":\"row-1\",", "")
        val rejected = GroundedActionProtocol.extractScreenEvidence(listOf(ToolCall("collect", "screen_act",
            """{"actions":[{"op":"scroll","direction":"down"}],"screen_evidence":$missingId}"""))) { it == "source-a" }
        val outcome = rejected.retentionOutcomes.getValue("collect")
        val receipt = outcome.receipt("model-round:one", "collect")
        assertEquals("RECORD_ID_REQUIRED", outcome.errorCode)
        assertEquals("records[0].record_id", outcome.errorPath)
        assertEquals(missingId, receipt.getValue("rejected_records").toString())
        assertFalse(rejected.toolCalls.single().arguments.contains("screen_evidence"))
        val repair = GroundedActionProtocol.extractScreenEvidence(listOf(ToolCall("repair", "workspace",
            """{"op":"objects","screen_evidence":$records,"record_repair_ref":"model-round:one:record:collect"}""")),
            mapOf("model-round:one:record:collect" to receipt)) { it == "source-a" }
        assertEquals("model-round:one:record:collect", repair.retentionOutcomes.getValue("repair").repairOf)
        assertEquals("""{"op":"objects"}""", repair.toolCalls.single().arguments)
        val expandedRecords = records.dropLast(1) + "," + records.substring(1).replace("row-1", "row-2")
        val expandedRepair = GroundedActionProtocol.extractScreenEvidence(listOf(ToolCall("expanded", "workspace",
            """{"op":"objects","screen_evidence":$expandedRecords,"record_repair_ref":"model-round:one:record:collect"}""")),
            mapOf("model-round:one:record:collect" to receipt)) { it == "source-a" }
        assertEquals(2, expandedRepair.evidence.single().records.size)
        assertEquals("model-round:one:record:collect", expandedRepair.retentionOutcomes.getValue("expanded").repairOf)
        val unrelated = GroundedActionProtocol.extractScreenEvidence(listOf(ToolCall("repair", "workspace",
            """{"op":"objects","screen_evidence":$records,"record_repair_ref":"foreign"}"""))) { true }
        assertEquals("RECORD_REPAIR_REF_UNAVAILABLE", unrelated.retentionOutcomes.getValue("repair").errorCode)
        assertTrue(unrelated.evidence.isEmpty())
        val countReceipt = kotlinx.serialization.json.JsonObject(receipt + mapOf("rejected_records" to
            kotlinx.serialization.json.JsonArray(List(2) { kotlinx.serialization.json.Json.parseToJsonElement(missingId).jsonArray[0] })))
        val truncatedRepair = GroundedActionProtocol.extractScreenEvidence(listOf(ToolCall("repair", "workspace",
            """{"op":"objects","screen_evidence":$records,"record_repair_ref":"model-round:one:record:collect"}""")),
            mapOf("model-round:one:record:collect" to countReceipt)) { it == "source-a" }
        assertEquals("RECORD_REPAIR_COUNT_MISMATCH", truncatedRepair.retentionOutcomes.getValue("repair").errorCode)
    }

}
