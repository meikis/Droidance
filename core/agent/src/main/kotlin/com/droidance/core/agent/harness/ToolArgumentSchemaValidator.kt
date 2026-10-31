// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.agent.tools.ToolJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import com.droidance.core.model.llm.models.toJsonSchema
import kotlinx.serialization.json.contentOrNull

object ToolArgumentSchemaDiagnosticCode {
    const val ARGUMENT_SCHEMA_MISMATCH = "ARGUMENT_SCHEMA_MISMATCH"
}

object ToolArgumentSchemaFeedback {
    /** Do not replace a concrete executor rejection with an empty, looser model-schema diagnosis. */
    fun forModel(
        contract: RegisteredToolContract,
        argumentsJson: String,
        modelParameters: Map<String, ToolParameter>,
        executorRejection: com.droidance.core.agent.event.ToolExecutionResult
    ): com.droidance.core.agent.event.ToolExecutionResult =
        if ((modelParameters == contract.parameters &&
                executorRejection.diagnosticCode == ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH) ||
            ToolArgumentSchemaValidator.mismatchDetail(modelParameters, argumentsJson) == null)
            executorRejection
        else rejectionResult(contract, argumentsJson, modelParameters)

    fun rejectionResult(
        contract: RegisteredToolContract,
        argumentsJson: String,
        modelParameters: Map<String, ToolParameter> = contract.parameters
    ): com.droidance.core.agent.event.ToolExecutionResult {
        val issues = ToolArgumentSchemaValidator.mismatchDetails(modelParameters, argumentsJson)
        val issue = issues.firstOrNull().orEmpty()
        val path = issue.takeIf { it.startsWith("field ") }?.removePrefix("field ")?.substringBefore(' ')
        val fieldSchema = path?.let { schemaAtPath(modelParameters, it) }
        return com.droidance.core.agent.tools.ToolProtocolResult.notExecuted(
            code = ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH,
            retryable = true,
            details = buildJsonObject {
                put("tool", JsonPrimitive(contract.name))
                put("issue", JsonPrimitive(issue))
                put("schema_issues", JsonArray(issues.map(::JsonPrimitive)))
                put("repair", JsonPrimitive("Repair all reported arguments together; preserve the original call's other fields. This call was not executed."))
                if (path != null && fieldSchema != null) {
                    put("repair_path", JsonPrimitive(path))
                    put("expected_field_schema", fieldSchema.toJsonSchema())
                } else {
                    // Malformed JSON has no trustworthy field path. Retain only the required shape.
                    put("expected", JsonPrimitive(expectedArguments(modelParameters.filterValues { it.required })))
                    put("required_argument_shape", JsonObject(modelParameters.filterValues { it.required }
                        .mapValues { (_, schema) -> exampleValue(schema) }))
                }
                put("received", JsonPrimitive(argumentsJson.take(500)))
            }
        )
    }

    private fun schemaAtPath(parameters: Map<String, ToolParameter>, path: String): ToolParameter? {
        val segments = Regex("[^.\\[\\]]+|\\[\\d+\\]").findAll(path).map { it.value }.toList()
        var schema = parameters[segments.firstOrNull()] ?: return null
        for (segment in segments.drop(1)) {
            schema = if (segment.startsWith("[")) schema.items ?: return null
                else schema.properties?.get(segment) ?: schema.additionalPropertySchema ?: return null
        }
        return schema
    }

    private fun exampleValue(schema: ToolParameter): JsonElement = when (schema.type) {
        "boolean" -> JsonPrimitive(false)
        "integer", "number" -> JsonPrimitive(0)
        "array" -> JsonArray(schema.items?.let { listOf(exampleValue(it)) }.orEmpty())
        "object" -> JsonObject(schema.properties.orEmpty().filterValues { it.required }
            .mapValues { (_, child) -> exampleValue(child) })
        else -> JsonPrimitive(schema.enumValues?.firstOrNull().orEmpty())
    }

    fun expectedArguments(contract: RegisteredToolContract): String =
        expectedArguments(contract.parameters)

    private fun expectedArguments(parameters: Map<String, ToolParameter>): String =
        parameters.entries.joinToString(", ") { (name, parameter) ->
            val requirement = if (parameter.required) "required" else "optional"
            val allowed = parameter.enumValues
                ?.joinToString("|")
                ?.let { "; one of $it" }
                .orEmpty()
            val items = parameter.items?.let { "; items:${it.type}" }.orEmpty()
            "$name:${parameter.type}($requirement$allowed$items)"
        }

    fun mismatchDetail(
        contract: RegisteredToolContract,
        argumentsJson: String
    ): String? = ToolArgumentSchemaValidator.mismatchDetail(contract, argumentsJson)
}

object ToolArgumentSchemaValidator {
    private val jsonInteger = Regex("-?(?:0|[1-9][0-9]*)")
    private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val supportedTypes = setOf(
        "string",
        "integer",
        "number",
        "boolean",
        "object",
        "array"
    )

    fun requireValidSchema(parameters: Map<String, ToolParameter>) {
        parameters.forEach { (name, parameter) ->
            requireValidParameter(name, parameter)
            (parameter.requiredWhen.entries + parameter.allowedWhen.entries).forEach { (selector, values) ->
                require(selector in parameters && parameters.getValue(selector).type == "string") {
                    "Tool parameter $name conditional selector must be a sibling string"
                }
                require(values.isNotEmpty() && values.all(String::isNotBlank) && values.distinct().size == values.size) {
                    "Tool parameter $name conditional values must be nonempty and unique"
                }
                parameters.getValue(selector).enumValues?.let { allowed ->
                    require(values.all { it in allowed }) { "Tool parameter $name conditional values must match its selector enum" }
                }
            }
        }
    }

    private fun requireValidParameter(name: String, parameter: ToolParameter) {
            require(!parameter.deferredValidation || !parameter.required && parameter.requiredWhen.isEmpty()) { "Deferred validation requires an optional parameter" }
            require(name.isNotBlank()) { "Tool parameter name cannot be blank" }
            require(parameter.type in supportedTypes) {
                "Tool parameter $name has unsupported type ${parameter.type}"
            }
            parameter.minLength?.let { minimum ->
                require(parameter.type == "string" && minimum >= 0) {
                    "Tool parameter $name minLength requires string type and a nonnegative bound"
                }
            }
            parameter.minItems?.let { minimum ->
                require(parameter.type == "array" && minimum >= 0) {
                    "Tool parameter $name minItems requires array type and a nonnegative bound"
                }
            }
            parameter.items?.let { item ->
                require(parameter.type == "array") {
                    "Tool parameter $name item type requires array type"
                }
                requireValidParameter("$name[]", item)
            }
            parameter.enumValues?.let { values ->
                require(parameter.type == "string") {
                    "Tool parameter $name enum requires string type"
                }
                require(values.isNotEmpty() && values.all(String::isNotBlank)) {
                    "Tool parameter $name enum values cannot be empty or blank"
                }
                require(values.toSet().size == values.size) {
                    "Tool parameter $name enum values must be unique"
                }
            }
            parameter.properties?.let { properties ->
                require(parameter.type == "object") {
                    "Tool parameter $name properties require object type"
                }
                requireValidSchema(properties)
            }
            parameter.additionalPropertySchema?.let { schema ->
                require(parameter.type == "object") {
                    "Tool parameter $name additional-property schema requires object type"
                }
                require(parameter.additionalProperties == null) {
                    "Tool parameter $name cannot declare both additional-property forms"
                }
                requireValidParameter("$name.*", schema)
            }
            parameter.additionalProperties?.let {
                require(parameter.type == "object") {
                    "Tool parameter $name additionalProperties requires object type"
                }
            }
    }

    fun diagnosticFor(
        contract: RegisteredToolContract,
        argumentsJson: String
    ): String? = mismatchDetail(contract, argumentsJson)?.let {
        ToolArgumentSchemaDiagnosticCode.ARGUMENT_SCHEMA_MISMATCH
    }

    fun mismatchDetail(
        contract: RegisteredToolContract,
        argumentsJson: String
    ): String? = mismatchDetail(contract.parameters, argumentsJson)

    fun mismatchDetail(
        parameters: Map<String, ToolParameter>,
        argumentsJson: String
    ): String? = mismatchDetails(parameters, argumentsJson).firstOrNull()

    fun mismatchDetails(
        parameters: Map<String, ToolParameter>,
        argumentsJson: String
    ): List<String> {
        val root = try {
            Json.parseToJsonElement(ToolJson.sanitizeArgumentsJson(argumentsJson))
        } catch (error: IllegalArgumentException) {
            val cause = error.message.orEmpty().substringBefore('\n').substringBefore("JSON input:").take(300)
            return listOf("arguments contain invalid JSON: $cause")
        }
        val arguments = root as? JsonObject
            ?: return listOf("arguments expected object but received ${describeValue(root)}")
        if (!arguments.isStrictJsonValue()) {
            return listOf("arguments expected strict JSON but received a non-standard JSON value")
        }
        return buildList { checkFields("", parameters, arguments, this) }
    }

    private fun isRequired(parameter: ToolParameter, siblings: JsonObject): Boolean =
        parameter.required || parameter.requiredWhen.any { (selector, values) ->
            val value = siblings[selector] as? JsonPrimitive
            value?.isString == true && value.contentOrNull in values
        }

    private fun checkFields(
        prefix: String, fields: Map<String, ToolParameter>, siblings: JsonObject,
        issues: MutableList<String>
    ) {
        fields.forEach { (name, field) ->
            if (field.deferredValidation) return@forEach
            val path = if (prefix.isEmpty()) name else "$prefix.$name"
            val value = siblings[name]
            val required = isRequired(field, siblings)
            if (value == null || (value is JsonNull && !required)) {
                if (required) issues += "field $path is required"
                return@forEach
            }
            val disallowed = field.allowedWhen.entries.firstOrNull { (selector, allowed) ->
                val selected = siblings[selector] as? JsonPrimitive
                selected?.isString == true && selected.contentOrNull !in allowed
            }
            if (disallowed != null) {
                issues += "field $path is allowed only when ${disallowed.key} is ${disallowed.value.joinToString("|")}; omit it for this operation"
            } else checkParameter(path, field, value, issues)
        }
    }

    private fun checkParameter(
        path: String, parameter: ToolParameter, value: JsonElement, issues: MutableList<String>
    ) {
        if (parameter.deferredValidation) return
        if (!matchesType(value, parameter.type)) {
            issues += "field $path expected ${parameter.type} but received ${describeValue(value)}"
            return
        }
        parameter.enumValues?.let { values ->
            if ((value as JsonPrimitive).content !in values) {
                issues += "field $path expected one of ${values.joinToString("|")} but received ${describeValue(value)}"
                return
            }
        }
        parameter.minLength?.let { minimum ->
            val text = (value as JsonPrimitive).content
            if (text.codePointCount(0, text.length) < minimum) {
                issues += "field $path expected string length >= $minimum"
            }
        }
        parameter.minItems?.let { minimum ->
            if ((value as JsonArray).size < minimum) {
                issues += "field $path expected array length >= $minimum"
            }
        }
        parameter.items?.let { item ->
            (value as JsonArray).forEachIndexed { index, nested -> checkParameter("$path[$index]", item, nested, issues) }
        }
        if (parameter.type == "object") {
            val objectValue = value as JsonObject
            val properties = parameter.properties.orEmpty()
            checkFields(path, properties, objectValue, issues)
            val unknownKeys = objectValue.keys - properties.keys
            if (parameter.additionalProperties == false) {
                unknownKeys.sorted().forEach { issues += "field $path contains unsupported property $it" }
            }
            parameter.additionalPropertySchema?.let { schema ->
                unknownKeys.sorted().forEach { checkParameter("$path.$it", schema, objectValue.getValue(it), issues) }
            }
        }
    }

    private fun describeValue(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> when {
            value.isString -> "string ($value)"
            value.content == "null" -> "null"
            value.content == "true" || value.content == "false" -> "boolean (${value.content})"
            jsonInteger.matches(value.content) -> "integer (${value.content})"
            jsonNumber.matches(value.content) -> "number (${value.content})"
            else -> "primitive (${value.content})"
        }
    }

    private fun matchesType(value: JsonElement, type: String): Boolean =
        when (type) {
            "string" -> (value as? JsonPrimitive)?.isString == true
            "integer" -> (value as? JsonPrimitive)?.let {
                !it.isString && jsonInteger.matches(it.content)
            } == true
            "number" -> (value as? JsonPrimitive)?.let {
                !it.isString && jsonNumber.matches(it.content)
            } == true
            "boolean" -> (value as? JsonPrimitive)?.let {
                if (!it.isString) {
                    it.content == "true" || it.content == "false"
                } else {
                    it.content.equals("true", ignoreCase = true) || it.content.equals("false", ignoreCase = true)
                }
            } == true
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> false
        }

    private fun JsonElement.isStrictJsonValue(): Boolean = when (this) {
        is JsonObject -> values.all { it.isStrictJsonValue() }
        is JsonArray -> all { it.isStrictJsonValue() }
        is JsonPrimitive -> isString || content == "null" || content == "true" ||
            content == "false" || jsonNumber.matches(content)
    }
}
