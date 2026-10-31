// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.config.ScreenActionBatchPolicy
import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.model.llm.models.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BatchRuntimeProtocolTest {
    private val policy = ScreenActionBatchPolicy(true, ScreenActionBatchProtocol.operations, 10, 1, 20, 40, 8, 1024, 64)

    @Test fun consecutiveClickAndImplicitTextBindAutomaticallyWithoutASeparateModelField() {
        fun plan(members: String) = ScreenActionBatchPlanner.plan("""{"actions":[$members]}""", policy)
        val click = """{"op":"click","x":300,"y":200}"""
        val input = """{"op":"type_text","text":"value","clear":true}"""
        val ready = plan("$click,$input,$click,$input") as ScreenActionBatchPlan.Ready
        assertEquals(listOf(null, 1, null, 3), ready.actions.map { it.focusFrom })
        assertEquals("""{"text":"value","clear":true,"method":"shortcut"}""", ready.actions[1].argumentsJson)
        assertNull((plan(input) as ScreenActionBatchPlan.Ready).actions.single().focusFrom)
        assertNull((plan("$click,{\"op\":\"wait\",\"ms\":10},$input") as ScreenActionBatchPlan.Ready).actions.last().focusFrom)
        val explicit = input.dropLast(1) + ",\"x\":300,\"y\":200}"
        assertNull((plan("$click,$explicit") as ScreenActionBatchPlan.Ready).actions.last().focusFrom)
        assertTrue(plan(input.dropLast(1) + ",\"focus_from\":1}") is ScreenActionBatchPlan.Rejected)
    }

    @Test fun compactToolSummaryPreservesParameterMeaningsAndExecutableConstraints() {
        val child = ToolParameter("string", "Long documentation ".repeat(100), false,
            enumValues = listOf("a", "b"), requiredWhen = mapOf("op" to listOf("edit")),
            allowedWhen = mapOf("op" to listOf("edit")), minLength = 2)
        val definition = ToolDefinition("example", "Detailed description ".repeat(100), mapOf(
            "op" to ToolParameter("string", "Operation", true, listOf("edit")),
            "nested" to ToolParameter("object", "Nested", false, properties = mapOf("op" to ToolParameter("string", "Operation", true, listOf("edit")), "value" to child))))
        val compact = ToolSchemaContext.compact(listOf(definition), ToolSchemaContextPolicy(
            toolDescriptions = mapOf("example" to "Edit the selected object. Returns the updated version."))).single()
        val brief = compact.parameters.getValue("nested").properties!!.getValue("value")
        assertEquals(child, brief)
        assertEquals(definition.parameters, compact.parameters)
        assertEquals("Edit the selected object. Returns the updated version.", compact.description)
        assertEquals(definition, ToolSchemaContext.compact(listOf(definition), ToolSchemaContextPolicy()).single())
        assertTrue(definition.parameters.getValue("nested").properties!!.getValue("value").description.length > 100)
        ToolArgumentSchemaValidator.requireValidSchema(compact.parameters)
        assertEquals(ToolArgumentSchemaValidator.mismatchDetails(definition.parameters, """{"op":"edit","nested":{}}"""),
            ToolArgumentSchemaValidator.mismatchDetails(compact.parameters, """{"op":"edit","nested":{}}"""))
    }

    @Test fun sourceBoundRecordsRetainIdentityUnitsAndUnknownsAndRejectBrokenProvenance() {
        fun row(id: String, value: String, source: String = "source") = Json.parseToJsonElement(
            """{"record_id":"$id","fields":{"metric":{"value":"$value","source_ref":"$source","region":"card-$id"}},"uncertainties":["name unknown"]}""")
        val first = WorkspaceRecordProtocol.merge(null, JsonArray(listOf(row("a", "100+"))), { it == "source" })
        val next = WorkspaceRecordProtocol.merge(first, JsonArray(listOf(row("b", "200+"), row("a", "150+"))), { it == "source" })
        val records = next.getValue("records").jsonArray
        assertEquals(listOf("a", "b"), records.map { it.jsonObject.getValue("record_id").jsonPrimitive.content })
        assertEquals("150+", records[0].jsonObject.getValue("fields").jsonObject.getValue("metric").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals("name unknown", records[0].jsonObject.getValue("uncertainties").jsonArray[0].jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceRecordProtocol.merge(next, JsonArray(listOf(row("c", "300+", "invented"))), { it == "source" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceRecordProtocol.merge(null, JsonArray(listOf(row("a", "1"), row("a", "2"))), { true })
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceRecordProtocol.merge(buildJsonObject { put("records", "invalid") }, JsonArray(listOf(row("a", "1"))), { true })
        }
        assertEquals(2, next.getValue("records").jsonArray.size)
    }

    @Test fun sameNamedParametersRetainEachDeclaringToolsMeaningIncludingNestedValues() {
        val originals = listOf(
            ToolParameter("object", "Whole task deadline from the user message", false, properties = mapOf(
                "seconds" to ToolParameter("integer", "Elapsed task seconds including work and waits", false))),
            ToolParameter("object", "Invocation timeout", false, properties = mapOf(
                "seconds" to ToolParameter("integer", "Seconds for this invocation only", false)))
        )
        val tools = listOf("a", "b").zip(originals).map { (name, parameter) ->
            ToolDefinition(name, "Definition", mapOf("deadline" to parameter))
        }
        val compact = ToolSchemaContext.compact(tools, ToolSchemaContextPolicy(
            toolDescriptions = mapOf("a" to "Run the task", "b" to "Run the invocation")))
        compact.zip(originals).forEach { (tool, original) ->
            assertEquals(original, tool.parameters.getValue("deadline"))
        }
    }

    @Test fun incrementalObjectCorrectionPreservesOtherFieldsSourcesAndUnresolvedState() {
        val initial = Json.parseToJsonElement("""[{"record_id":"row-1","fields":{"name":{"value":"Entry A","source_ref":"list","region":"first row"},"amount":{"value":"10 each","source_ref":"list"}},"assessment":"pending","uncertainties":["detail missing"]}]""").jsonArray
        val first = WorkspaceRecordProtocol.merge(null, initial) { it in setOf("list", "detail") }
        val update = Json.parseToJsonElement("""[{"record_id":"row-1","fields":{"amount":{"value":"12 each","source_ref":"detail","region":"amount label"}}}]""").jsonArray
        val second = WorkspaceRecordProtocol.merge(first, update) { it in setOf("list", "detail") }
        val record = second.getValue("records").jsonArray.single().jsonObject
        val fields = record.getValue("fields").jsonObject
        assertEquals(initial.single().jsonObject.getValue("fields").jsonObject.getValue("name"), fields.getValue("name"))
        assertEquals(update.single().jsonObject.getValue("fields").jsonObject.getValue("amount"), fields.getValue("amount"))
        assertEquals("pending", record.getValue("assessment").jsonPrimitive.content)
        assertEquals("detail missing", record.getValue("uncertainties").jsonArray.single().jsonPrimitive.content)
        val resolved = Json.parseToJsonElement("""[{"record_id":"row-1","fields":{"detail":{"value":"Observed detail","source_ref":"detail"}},"uncertainties":[]}]""").jsonArray
        val final = WorkspaceRecordProtocol.merge(second, resolved) { it in setOf("list", "detail") }
        assertEquals(3, final.getValue("records").jsonArray.single().jsonObject.getValue("fields").jsonObject.size)
        assertTrue(final.getValue("records").jsonArray.single().jsonObject.getValue("uncertainties").jsonArray.isEmpty())
        assertEquals(2, record.getValue("fields").jsonObject.size) // Earlier versions remain immutable.
    }
}
