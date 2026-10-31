// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.agent.event.ToolBinding
import com.droidance.core.agent.event.ToolEffect
import com.droidance.core.model.llm.models.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentSchemaValidatorTest {
    @Test fun minimumBoundsCheckNestedValuesAndRetainFieldConstraintsInRepairFeedback() {
        val parameters = mapOf("review" to ToolParameter("object", "", required = false, properties = linkedMapOf(
            "finding" to ToolParameter("string", "", minLength = 2),
            "sources" to ToolParameter("array", "", minItems = 1, items = ToolParameter("string", "", minLength = 1))
        )))
        ToolArgumentSchemaValidator.requireValidSchema(parameters)
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters, "{}"))
        val empty = """{"review":{"finding":"","sources":[]}}"""
        assertEquals(listOf("field review.finding expected string length >= 2", "field review.sources expected array length >= 1"),
            ToolArgumentSchemaValidator.mismatchDetails(parameters, empty))
        assertEquals("field review.sources[0] expected string length >= 1", ToolArgumentSchemaValidator.mismatchDetail(parameters,
            """{"review":{"finding":"Seen","sources":[""]}}"""))
        val feedback = kotlinx.serialization.json.Json.parseToJsonElement(
            ToolArgumentSchemaFeedback.rejectionResult(contract(parameters), empty).outputText)
        org.junit.Assert.assertTrue(feedback.toString().contains("\"repair_path\":\"review.finding\""))
        org.junit.Assert.assertTrue(feedback.toString().contains("\"minLength\":2"))
    }

    @Test fun stringMinimumCountsUnicodeCodePointsInsteadOfUtf16UnitsOrGraphemes() {
        val parameters = mapOf("text" to ToolParameter("string", "", minLength = 2))
        assertEquals("field text expected string length >= 2", ToolArgumentSchemaValidator.mismatchDetail(parameters,
            """{"text":"😀"}"""))
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters, """{"text":"😀x"}"""))
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters, """{"text":"e\u0301"}"""))
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters, """{"text":"  "}"""))
    }

    @Test fun minimumBoundsAreOptInAndZeroAllowsEmptyValues() {
        val unbounded = mapOf("text" to ToolParameter("string", ""), "items" to ToolParameter("array", ""))
        val zero = mapOf("text" to ToolParameter("string", "", minLength = 0), "items" to ToolParameter("array", "", minItems = 0))
        for (parameters in listOf(unbounded, zero)) {
            ToolArgumentSchemaValidator.requireValidSchema(parameters)
            assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters, """{"text":"","items":[]}"""))
        }
    }

    @Test fun negativeOrWrongTypeMinimumMetadataIsRejectedRecursively() {
        val invalid = listOf(
            ToolParameter("string", "", minLength = -1),
            ToolParameter("array", "", minItems = -1),
            ToolParameter("array", "", minLength = 1),
            ToolParameter("string", "", minItems = 1)
        )
        for (parameter in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                ToolArgumentSchemaValidator.requireValidSchema(mapOf("value" to parameter))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ToolArgumentSchemaValidator.requireValidSchema(mapOf("nested" to ToolParameter("array", "", items =
                    ToolParameter("object", "", additionalPropertySchema = parameter))))
            }
        }
    }

    @Test fun batchReportsAllInvalidFieldsAndOperationSpecificArgumentsTogether() {
        val fields = com.droidance.core.agent.tools.ScreenActionBatchProtocol.parameters("Clear")
        val batch = mapOf("actions" to ToolParameter("array", "", items =
            ToolParameter("object", "", properties = fields, additionalProperties = false)))
        val payload = """{"actions":[{"op":"click","x":"100","y":"200","clear":true},
            {"op":"type_text","text":"value"}]}"""
        val issues = ToolArgumentSchemaValidator.mismatchDetails(batch, payload)
        assertEquals(4, issues.size)
        org.junit.Assert.assertTrue(issues.any { it.startsWith("field actions[0].x expected integer") })
        org.junit.Assert.assertTrue(issues.any { it.startsWith("field actions[0].y expected integer") })
        org.junit.Assert.assertTrue(issues.any { it.contains("actions[0].clear is allowed only when op is type_text") })
        org.junit.Assert.assertTrue(issues.any { it == "field actions[1].clear is required" })
        val feedback = ToolArgumentSchemaFeedback.rejectionResult(contract(batch), payload).outputText
        org.junit.Assert.assertTrue(feedback.contains("schema_issues"))
        org.junit.Assert.assertTrue(feedback.contains("actions[1].clear"))
    }

    @Test fun conditionalFieldsAreCheckedAtRootAndInsideBatchBeforeDispatch() {
        val fields = com.droidance.core.agent.tools.ScreenActionBatchProtocol.parameters("Explicit clear")
        ToolArgumentSchemaValidator.requireValidSchema(fields)
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(fields, """{"op":"click","x":100,"y":100}"""))
        assertEquals("field clear is required", ToolArgumentSchemaValidator.mismatchDetail(fields,
            """{"op":"type_text","text":"value"}"""))
        assertEquals("field key is required", ToolArgumentSchemaValidator.mismatchDetail(fields,
            """{"op":"press_key"}"""))
        val batch = mapOf("actions" to ToolParameter("array", "", items =
            ToolParameter("object", "", properties = fields, additionalProperties = false)))
        val payload = """{"actions":[{"op":"click","x":100,"y":100},{"op":"type_text","text":"value"}]}"""
        assertEquals("field actions[1].clear is required", ToolArgumentSchemaValidator.mismatchDetail(batch, payload))
        val repair = ToolArgumentSchemaFeedback.rejectionResult(contract(batch), payload).outputText
        org.junit.Assert.assertTrue(repair.contains("actions[1].clear"))
        org.junit.Assert.assertTrue(repair.contains("expected_field_schema"))
        org.junit.Assert.assertFalse(repair.contains("required_argument_shape"))
        org.junit.Assert.assertFalse(repair.contains("Normalized target X"))
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(batch,
            """{"actions":[{"op":"type_text","text":"value","clear":false}]}"""))
        assertThrows(IllegalArgumentException::class.java) { ToolArgumentSchemaValidator.requireValidSchema(
            mapOf("clear" to ToolParameter("boolean", "", false, requiredWhen = mapOf("missing" to listOf("input"))))) }
    }

    @Test fun malformedJsonReportsParserLocationWithoutEchoingInputDump() {
        val parameters = mapOf("text" to parameter("string"))
        val malformed = """{"text":"Unit\|price","private":"private-marker"}"""
        val detail = ToolArgumentSchemaValidator.mismatchDetail(parameters, malformed).orEmpty()
        org.junit.Assert.assertTrue(detail, detail.contains("invalid JSON"))
        org.junit.Assert.assertTrue(detail, detail.contains("offset"))
        org.junit.Assert.assertFalse(detail, detail.contains("private-marker"))
        assertNull(ToolArgumentSchemaValidator.mismatchDetail(parameters,
            """{"text":"Unit\\|price","private":"private-marker"}"""))
    }

    @Test fun validNonObjectReportsItsTypeSeparatelyFromMalformedJson() {
        assertEquals("arguments expected object but received array",
            ToolArgumentSchemaValidator.mismatchDetail(emptyMap(), "[]"))
        org.junit.Assert.assertTrue(ToolArgumentSchemaValidator.mismatchDetail(emptyMap(), "{")
            .orEmpty().contains("invalid JSON"))
    }

    @Test fun looseCompletionFileSchemaPreservesMissingInlineExecutorField() {
        val base = contract(mapOf("unresolved_requirements" to parameter("array", itemType = "string")))
        val loose = base.parameters.mapValues { (_, field) -> field.copy(required = false) } +
            ("completion_file" to ToolParameter("string", "", required = false))
        val rejected = ToolArgumentSchemaFeedback.rejectionResult(base, "{}")
        val feedback = ToolArgumentSchemaFeedback.forModel(base, "{}", loose, rejected)
        assertEquals(rejected, feedback)
        org.junit.Assert.assertTrue(feedback.outputText.contains("field unresolved_requirements is required"))
        // A file expansion failure retains the real executor diagnosis, not fabricated inline fields.
        val fileFailure = com.droidance.core.agent.tools.ToolProtocolResult.invalidArgument("completion_file", "missing")
        assertEquals(fileFailure, ToolArgumentSchemaFeedback.forModel(base,
            """{"completion_file":"missing.json"}""", loose, fileFailure))
    }

    @Test fun invalidModelFieldStillUsesOriginalModelArgumentsForRepair() {
        val base = contract(mapOf("summary" to parameter("string")))
        val model = mapOf("results" to parameter("array", itemType = "string"))
        val feedback = ToolArgumentSchemaFeedback.forModel(base, "{}", model,
            ToolArgumentSchemaFeedback.rejectionResult(base, "{}"))
        org.junit.Assert.assertTrue(feedback.outputText.contains("field results is required"))
        org.junit.Assert.assertFalse(feedback.outputText.contains("field summary is required"))
    }

    @Test fun repairIssueUsesTheModelProtocolRatherThanExecutorSummary() {
        val base = contract(mapOf("summary" to parameter("string")))
        val model = mapOf("results" to ToolParameter("array", "", items = ToolParameter("string", "")))
        val feedback = ToolArgumentSchemaFeedback.rejectionResult(base, "{}", model).outputText
        org.junit.Assert.assertTrue(feedback.contains("field results is required"))
        org.junit.Assert.assertFalse(feedback.contains("summary"))
        val valid = ToolArgumentSchemaFeedback.rejectionResult(base, """{"results":["body"]}""", model).outputText
        org.junit.Assert.assertFalse(valid.contains("summary"))
    }

    @Test
    fun repairFeedbackIncludesModelOnlyRequiredClaims() {
        val base = contract(mapOf("summary" to parameter("string")))
        val modelParameters = base.parameters + ("claims" to ToolParameter(
            type = "array", description = "Claims", required = true,
            items = ToolParameter(type = "object", description = "Claim", properties = mapOf(
                "result" to parameter("string"), "evidence_refs" to parameter("string")
            ))
        ))
        val feedback = ToolArgumentSchemaFeedback.rejectionResult(base, "{broken", modelParameters).outputText
        org.junit.Assert.assertTrue(feedback.contains("claims:array(required"))
        org.junit.Assert.assertTrue(feedback.contains("evidence_refs"))
        org.junit.Assert.assertFalse(base.parameters.containsKey("claims"))
    }

    @Test
    fun accepts_supported_types_optional_fields_enum_and_unknown_fields() {
        val contract = contract(
            mapOf(
                "text" to parameter("string"),
                "count" to parameter("integer"),
                "ratio" to parameter("number"),
                "enabled" to parameter("boolean"),
                "payload" to parameter("object"),
                "items" to parameter("array", itemType = "object"),
                "mode" to parameter("string", enumValues = listOf("fast", "safe")),
                "optional" to parameter("string", required = false)
            )
        )

        val diagnostic = ToolArgumentSchemaValidator.diagnosticFor(
            contract = contract,
            argumentsJson = """
                {
                  "text": "query",
                  "count": 2,
                  "ratio": 1.25,
                  "enabled": true,
                  "payload": {"nested": [1, 2]},
                  "items": [{"text":"first"}],
                  "mode": "safe",
                  "provider_extension": ["kept", 3]
                }
            """.trimIndent()
        )

        assertNull(diagnostic)
    }

    @Test
    fun arguments_must_be_a_valid_json_root_object() {
        listOf(
            "",
            "{",
            "[]",
            "null",
            "true",
            "1",
            "\"text\""
        ).forEach { argumentsJson ->
            assertSchemaMismatch(contract(emptyMap()), argumentsJson)
        }
    }

    @Test
    fun rejects_non_standard_json_numbers_even_in_unknown_fields() {
        listOf(
            "{\"unknown\":NaN}",
            "{\"unknown\":Infinity}",
            "{\"unknown\":-Infinity}",
            "{\"unknown\":01}",
            "{\"unknown\":1.}",
            "{\"unknown\":.1}"
        ).forEach { argumentsJson ->
            assertSchemaMismatch(contract(emptyMap()), argumentsJson)
        }
    }

    @Test
    fun required_fields_must_exist_and_null_does_not_satisfy_a_declared_type() {
        val required = contract(mapOf("value" to parameter("string")))
        val optional = contract(mapOf("value" to parameter("string", required = false)))

        assertSchemaMismatch(required, "{}")
        assertSchemaMismatch(required, "{\"value\":null}")
        assertNull(ToolArgumentSchemaValidator.diagnosticFor(optional, "{}"))
        assertNull(ToolArgumentSchemaValidator.diagnosticFor(optional, "{\"value\":null}"))
    }

    @Test
    fun deferred_validation_is_only_allowed_for_optional_fields_and_skips_only_that_value() {
        val parameters = mapOf(
            "status" to parameter("string", enumValues = listOf("done", "failed")),
            "experience_updates" to ToolParameter(
                type = "array",
                description = "Optional proposal, validated by the durable projection.",
                required = false,
                items = ToolParameter("object", "Experience proposal", required = true),
                deferredValidation = true
            )
        )
        val contract = contract(parameters)

        assertNull(ToolArgumentSchemaValidator.diagnosticFor(
            contract,
            """{"status":"done","experience_updates":"malformed optional proposal"}"""
        ))
        assertEquals(
            ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
            ToolArgumentSchemaValidator.diagnosticFor(
                contract,
                """{"status":false,"experience_updates":"malformed optional proposal"}"""
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            contract(mapOf("experience_updates" to ToolParameter(
                type = "array", description = "A required deferred field is invalid.",
                required = true, deferredValidation = true
            )))
        }
    }

    @Test
    fun optional_nested_fields_accept_null_as_omitted() {
        val item = ToolParameter(
            type = "object",
            description = "item",
            required = true,
            properties = mapOf(
                "target_id" to ToolParameter("string", "target id", required = false),
                "x" to ToolParameter("integer", "x", required = true)
            )
        )
        val contract = contract(
            mapOf("actions" to ToolParameter("array", "actions", items = item))
        )

        assertNull(
            ToolArgumentSchemaValidator.diagnosticFor(
                contract,
                """{"actions":[{"target_id":null,"x":331}]}"""
            )
        )
        assertSchemaMismatch(
            contract,
            """{"actions":[{"target_id":"text_1","x":null}]}"""
        )
    }

    @Test
    fun validates_each_supported_parameter_type_without_coercion() {
        assertNull(diagnostic("string", "\"value\""))
        assertSchemaMismatch("string", "1")

        assertNull(diagnostic("integer", "42"))
        assertNull(diagnostic("integer", "123456789012345678901234567890"))
        assertSchemaMismatch("integer", "42.5")
        assertSchemaMismatch("integer", "\"42\"")

        assertNull(diagnostic("number", "42"))
        assertNull(diagnostic("number", "42.5"))
        assertSchemaMismatch("number", "\"42.5\"")

        assertNull(diagnostic("boolean", "false"))
        assertNull(diagnostic("boolean", "\"false\""))
        assertNull(diagnostic("boolean", "\"True\""))
        assertSchemaMismatch("boolean", "\"not_a_boolean\"")

        assertNull(diagnostic("object", "{\"key\":\"value\"}"))
        assertSchemaMismatch("object", "[]")

        assertNull(diagnostic("array", "[\"value\", 1]"))
        assertSchemaMismatch("array", "{\"value\":1}")
    }

    @Test
    fun declared_array_item_type_is_validated_without_coercion() {
        val objectItems = contract(
            mapOf("items" to parameter("array", itemType = "object"))
        )

        assertNull(
            ToolArgumentSchemaValidator.diagnosticFor(
                objectItems,
                """{"items":[{"name":"first"}]}"""
            )
        )
        assertSchemaMismatch(objectItems, """{"items":["{\"name\":\"first\"}"]}""")
    }

    @Test
    fun nested_required_properties_and_string_additional_values_are_validated() {
        val item = ToolParameter(
            type = "object",
            description = "item",
            required = false,
            properties = mapOf(
                "fields" to ToolParameter(
                    type = "object",
                    description = "fields",
                    properties = mapOf(
                        "constraint_evidence" to ToolParameter(
                            type = "object",
                            description = "evidence",
                            additionalPropertySchema = ToolParameter("string", "literal", false)
                        )
                    ),
                    additionalProperties = true
                )
            ),
            additionalProperties = false
        )
        val nested = contract(mapOf("items" to ToolParameter("array", "items", items = item)))

        assertNull(
            ToolArgumentSchemaValidator.diagnosticFor(
                nested,
                """{"items":[{"fields":{"name":"A","constraint_evidence":{"price":"588"}}}]}"""
            )
        )
        assertSchemaMismatch(nested, """{"items":[{"fields":{"name":"A"}}]}""")
        assertSchemaMismatch(
            nested,
            """{"items":[{"fields":{"constraint_evidence":{"price":588}}}]}"""
        )
    }

    @Test
    fun string_enum_requires_an_exact_declared_value() {
        val contract = contract(
            mapOf("mode" to parameter("string", enumValues = listOf("fast", "safe")))
        )

        assertNull(ToolArgumentSchemaValidator.diagnosticFor(contract, "{\"mode\":\"fast\"}"))
        assertSchemaMismatch(contract, "{\"mode\":\"FAST\"}")
        assertSchemaMismatch(contract, "{\"mode\":1}")
    }

    @Test
    fun repair_feedback_describes_the_expected_schema_without_business_rules() {
        val contract = contract(
            linkedMapOf(
                "x" to parameter("integer"),
                "y" to parameter("integer"),
                "note" to parameter("string", required = false)
            )
        )

        assertEquals(
            "x:integer(required), y:integer(required), note:string(optional)",
            ToolArgumentSchemaFeedback.expectedArguments(contract)
        )
    }

    @Test
    fun repair_feedback_identifies_the_exact_field_type_without_coercion() {
        val contract = contract(
            linkedMapOf(
                "op" to parameter("string"),
                "clear" to parameter("boolean", required = false)
            )
        )

        assertEquals(
            "field clear expected boolean but received string (\"not_a_boolean\")",
            ToolArgumentSchemaFeedback.mismatchDetail(
                contract,
                """{"op":"type_text","clear":"not_a_boolean"}"""
            )
        )
        assertNull(
            ToolArgumentSchemaFeedback.mismatchDetail(
                contract,
                """{"op":"type_text","clear":"True"}"""
            )
        )
        assertNull(
            ToolArgumentSchemaFeedback.mismatchDetail(
                contract,
                """{"op":"type_text","clear":true}"""
            )
        )
    }

    @Test
    fun registration_rejects_unsupported_or_ambiguous_schema_constructs() {
        val invalidSchemas = listOf(
            mapOf("value" to parameter("integer", enumValues = listOf("1"))),
            mapOf("value" to parameter("string", enumValues = emptyList())),
            mapOf("value" to parameter("string", enumValues = listOf("valid", " "))),
            mapOf("value" to parameter("string", enumValues = listOf("same", "same"))),
            mapOf("value" to parameter("string", itemType = "object")),
            mapOf("value" to parameter("array", itemType = "unsupported"))
        )

        invalidSchemas.forEach { parameters ->
            assertThrows(IllegalArgumentException::class.java) {
                contract(parameters)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(mapOf(" " to parameter("string")))
        }
    }

    @Test
    fun contract_hash_is_order_independent_and_covers_the_frozen_schema() {
        val enumValues = mutableListOf("first", "second")
        val first = contract(
            linkedMapOf(
                "mode" to ToolParameter("string", "mode", enumValues = enumValues),
                "note" to ToolParameter("string", "note", required = false)
            )
        )
        val reordered = contract(
            linkedMapOf(
                "note" to ToolParameter("string", "note", required = false),
                "mode" to ToolParameter(
                    "string",
                    "mode",
                    enumValues = listOf("first", "second")
                )
            )
        )

        assertEquals(first.contractHash, reordered.contractHash)
        enumValues += "late-mutation"
        assertEquals(listOf("first", "second"), first.parameters.getValue("mode").enumValues)
        assertEquals(first.contractHash, reordered.contractHash)
        assertNotEquals(
            first.contractHash,
            contract(
                mapOf(
                    "mode" to ToolParameter(
                        "string",
                        "changed description",
                        enumValues = listOf("first", "second")
                    ),
                    "note" to ToolParameter("string", "note", required = false)
                )
            ).contractHash
        )
        assertNotEquals(
            first.contractHash,
            contract(first.parameters, description = "Changed tool description").contractHash
        )
    }

    @Test
    fun accepts_arguments_with_markdown_code_fences() {
        val contract = contract(mapOf("query" to parameter("string")))
        val fenced = "```json\n{\"query\":\"hello\"}\n```"
        assertNull(ToolArgumentSchemaValidator.diagnosticFor(contract, fenced))
    }

    @Test
    fun accepts_arguments_with_leaked_xml_parameter_tags() {
        val contract = contract(mapOf(
            "requirement_checks" to ToolParameter("array", "checks", required = true, items = parameter("string")),
            "status" to parameter("string")
        ))
        val rawLeaked = """{"requirement_checks"><parameter name="required": ["r1"], "status": "done"}"""
        assertNull(ToolArgumentSchemaValidator.diagnosticFor(contract, rawLeaked))
    }

    private fun diagnostic(type: String, jsonValue: String): String? =
        ToolArgumentSchemaValidator.diagnosticFor(
            contract = contract(mapOf("value" to parameter(type))),
            argumentsJson = "{\"value\":$jsonValue}"
        )

    private fun assertSchemaMismatch(type: String, jsonValue: String) {
        assertEquals(
            ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
            diagnostic(type, jsonValue)
        )
    }

    private fun assertSchemaMismatch(
        contract: RegisteredToolContract,
        argumentsJson: String
    ) {
        assertEquals(
            ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
            ToolArgumentSchemaValidator.diagnosticFor(contract, argumentsJson)
        )
    }

    private fun contract(
        parameters: Map<String, ToolParameter>,
        description: String = "Validate a test schema"
    ) = RegisteredToolContract(
        name = "test_tool",
        description = description,
        binding = ToolBinding.TURN_LOCAL,
        effect = ToolEffect.READ_ONLY,
        parameters = parameters
    )

    private fun parameter(
        type: String,
        required: Boolean = true,
        enumValues: List<String>? = null,
        itemType: String? = null
    ) = ToolParameter(
        type = type,
        description = "Test parameter",
        required = required,
        enumValues = enumValues,
        items = itemType?.let { ToolParameter(it, "Test array item", false) }
    )
}
