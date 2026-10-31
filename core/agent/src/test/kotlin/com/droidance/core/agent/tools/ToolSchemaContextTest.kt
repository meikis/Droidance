// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.harness.ToolArgumentSchemaValidator
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolSchemaContextTest {
    @Test fun scopedDocumentationPreservesEveryConstraintAndOtherToolsWithTheSameParameterName() {
        val entry = ToolParameter("object", "Original entry guidance", properties = linkedMapOf(
            "op" to ToolParameter("string", "Operation", enumValues = listOf("edit", "read")),
            "value" to ToolParameter("string", "Exact replacement value", false, minLength = 2,
                requiredWhen = mapOf("op" to listOf("edit")), allowedWhen = mapOf("op" to listOf("edit"))),
            "fields" to ToolParameter("object", "Named fields", false,
                additionalPropertySchema = ToolParameter("string", "Field value", minLength = 1))
        ), additionalProperties = false)
        val original = ToolDefinition("example", "Full help", mapOf(
            "entries" to ToolParameter("array", "Entries", items = entry, minItems = 1)))
        val other = original.copy(name = "other")
        val compact = ToolSchemaContext.compact(listOf(original, other), ToolSchemaContextPolicy(
            parameterDescriptions = mapOf("example" to mapOf(
                "entries[].value" to "Replacement text.", "entries[].fields{}" to "Field text."
            ))))
        assertEquals(other, compact[1])
        assertEquals("Exact replacement value", original.parameters.getValue("entries").items!!.properties!!.getValue("value").description)
        fun withoutDocumentation(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.filterKeys { it != "description" }.mapValues { withoutDocumentation(it.value) })
            is JsonArray -> JsonArray(value.map(::withoutDocumentation))
            else -> value
        }
        assertEquals(withoutDocumentation(original.parameters.getValue("entries").toJsonSchema()),
            withoutDocumentation(compact[0].parameters.getValue("entries").toJsonSchema()))
        ToolArgumentSchemaValidator.requireValidSchema(compact[0].parameters)
        listOf(
            """{"entries":[{"op":"edit","value":"ok","fields":{"name":"value"}}]}""",
            """{"entries":[{"op":"edit"}]}""",
            """{"entries":[{"op":"read","value":"ok"}]}""",
            """{"entries":[{"op":"edit","value":"x","extra":true}]}""",
            """{"entries":[]}"""
        ).forEach { arguments ->
            assertEquals(ToolArgumentSchemaValidator.mismatchDetails(original.parameters, arguments),
                ToolArgumentSchemaValidator.mismatchDetails(compact[0].parameters, arguments))
        }
    }

    @Test fun staleDocumentationPathsFailBeforeDispatchWhileAbsentToolsNeedNoOverrides() {
        val policy = ToolSchemaContextPolicy(parameterDescriptions = mapOf("example" to mapOf("missing" to "Text")))
        assertTrue(ToolSchemaContext.compact(emptyList(), policy).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            ToolSchemaContext.compact(listOf(ToolDefinition("example", "Full help", emptyMap())), policy)
        }
    }
    @Test fun helpOnlyToolsSendConstraintsWithoutParameterProse() {
        val original = ToolDefinition("rare", "Rare tool", mapOf(
            "op" to ToolParameter("string", "Operation", enumValues = listOf("a", "b")),
            "entries" to ToolParameter("array", "Entries", required = false, minItems = 1,
                requiredWhen = mapOf("op" to listOf("a")), items = ToolParameter("object", "Entry",
                    properties = mapOf("value" to ToolParameter("string", "Value", minLength = 1))))))
        val other = original.copy(name = "common")
        val (rare, common) = ToolSchemaContext.omitParameterDocumentation(listOf(original, other),
            ToolSchemaContextPolicy(helpOnlyParameterTools = setOf("rare")))
        assertEquals(other, common)
        assertEquals("Rare tool", rare.description)
        val schema = rare.parameters.getValue("entries").toJsonSchema().toString()
        assertFalse(schema.contains("Entries") || schema.contains("\"Value\""))
        assertTrue(schema.contains("Required when op is a."))
        listOf("""{"op":"a","entries":[{}]}""", """{"op":"c"}""", """{"op":"a"}""", """{"op":"b","entries":[{"value":""}]}""").forEach { arguments ->
            assertEquals(ToolArgumentSchemaValidator.mismatchDetails(original.parameters, arguments),
                ToolArgumentSchemaValidator.mismatchDetails(rare.parameters, arguments))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolSchemaContextPolicy(helpOnlyParameterTools = setOf("rare"), parameterDescriptions = mapOf("rare" to mapOf("op" to "Text")))
        }
    }
}
