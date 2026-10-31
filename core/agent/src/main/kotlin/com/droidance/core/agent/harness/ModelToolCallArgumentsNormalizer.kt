// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.harness

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Performs deliberately small, schema-safe recovery for model calls.
 *
 * Some providers stream a function argument with one missing array/object delimiter or append a
 * protocol-only field after the root object. We must not guess values or repair a malformed
 * device action. Against the tool's own parameter schema, scalar values are canonicalized only
 * when the conversion is lossless: a quoted plain integer or decimal for an integer or number
 * field, a quoted `true`/`false` for a boolean field, and a JSON number or boolean for a string
 * field. An ime text input without a target reuses the click on the editor immediately before it
 * in the same batch. Callers still run the strict contract validator afterwards, so each repair
 * saves a model round without hiding a real error.
 */
object ModelToolCallArgumentsNormalizer {
    private const val SCREEN_ACT = "screen_act"
    private val JSON_VALUE_STARTS = "-0123456789tfn".toSet()
    private val plainInteger = Regex("-?(0|[1-9][0-9]{0,8})")
    private val plainDecimal = Regex("-?(0|[1-9][0-9]{0,14})(\\.[0-9]{1,15})?")
    data class Result(
        val toolCall: ToolCall,
        val repaired: Boolean
    )

    fun normalize(
        toolCall: ToolCall,
        allowStructuralRepair: Boolean,
        parameters: Map<String, ToolParameter>? = null
    ): Result {
        val structuralResult = if (isJsonObject(toolCall.arguments)) {
            Result(toolCall, repaired = false)
        } else if (allowStructuralRepair) {
            val repairedArguments = repairJsonObject(toolCall.arguments)
                ?: return Result(toolCall, repaired = false)
            Result(
                toolCall = toolCall.copy(arguments = repairedArguments),
                repaired = true
            )
        } else {
            Result(toolCall, repaired = false)
        }
        val typedResult = normalizeSafeScalarTypes(structuralResult.toolCall, parameters)
        return Result(
            toolCall = typedResult.toolCall,
            repaired = structuralResult.repaired || typedResult.repaired
        )
    }

    private fun normalizeSafeScalarTypes(toolCall: ToolCall, parameters: Map<String, ToolParameter>?): Result {
        if (parameters.isNullOrEmpty()) return Result(toolCall, repaired = false)
        val arguments = runCatching {
            Json.parseToJsonElement(toolCall.arguments).jsonObject
        }.getOrNull() ?: return Result(toolCall, repaired = false)
        var changed = false
        fun canonical(value: JsonElement, schema: ToolParameter?): JsonElement {
            if (schema == null) return value
            return when (value) {
                is JsonPrimitive -> scalar(value, schema.type)?.also { changed = true } ?: value
                is JsonArray -> schema.items?.let { item -> JsonArray(value.map { canonical(it, item) }) } ?: value
                is JsonObject -> JsonObject(value.mapValues { (name, child) ->
                    canonical(child, schema.properties?.get(name) ?: schema.additionalPropertySchema)
                })
            }
        }
        var normalized = JsonObject(arguments.mapValues { (name, value) -> canonical(value, parameters[name]) })
        if (toolCall.name == SCREEN_ACT) normalized = inheritImeTarget(normalized) { changed = true }
        return if (!changed) Result(toolCall, repaired = false)
            else Result(toolCall.copy(arguments = normalized.toString()), repaired = true)
    }

    /** Lossless scalar canonicalization only; null means the value stays as sent. */
    private fun scalar(value: JsonPrimitive, type: String): JsonPrimitive? {
        if (value is JsonNull) return null
        return when (type) {
            "integer" -> value.takeIf { it.isString && plainInteger.matches(it.content) }?.let { JsonPrimitive(it.content.toInt()) }
            "number" -> value.takeIf { it.isString && plainDecimal.matches(it.content) }
                ?.let { JsonPrimitive(it.content.toBigDecimal()) }
            "boolean" -> value.takeIf { it.isString }?.content?.lowercase()
                ?.takeIf { it == "true" || it == "false" }?.let { JsonPrimitive(it == "true") }
            "string" -> value.takeIf { !it.isString }?.let { JsonPrimitive(it.content) }
            else -> null
        }
    }

    /**
     * An ime type_text needs its editor target. When the same batch has just clicked an editor
     * at x/y and the ime input names no target, that click is the observed editor; reuse it
     * instead of returning a schema error for another model round.
     */
    private fun inheritImeTarget(arguments: JsonObject, onChange: () -> Unit): JsonObject {
        val actions = arguments["actions"] as? JsonArray ?: return arguments
        var previous: JsonObject? = null
        val updated = actions.map { element ->
            val action = element as? JsonObject ?: return@map element.also { previous = null }
            val op = (action["op"] as? JsonPrimitive)?.contentOrNull
            val ime = op == "type_text" && (action["method"] as? JsonPrimitive)?.contentOrNull == "ime"
            val click = previous?.takeIf { (it["op"] as? JsonPrimitive)?.contentOrNull == "click" &&
                it["x"] != null && it["y"] != null && it["coordinate_ref"] == null && it["target_id"] == null }
            val result = if (ime && click != null && listOf("x", "y", "target_id").none(action::containsKey)) {
                onChange()
                JsonObject(action + mapOf("x" to click.getValue("x"), "y" to click.getValue("y")))
            } else action
            previous = action
            result
        }
        return JsonObject(arguments + ("actions" to JsonArray(updated)))
    }

    private fun isJsonObject(raw: String): Boolean = runCatching {
        Json.parseToJsonElement(raw).jsonObject
    }.isSuccess

    /**
     * Repairs only delimiter-level damage. It never inserts a key, value, or text. A comma that
     * is followed by a quoted object property while an array is open is interpreted as a missing
     * array close; a closing object delimiter while an array is open receives the same close.
     */
    private fun repairJsonObject(raw: String): String? {
        val input = raw.trim()
        if (input.isBlank()) return null

        val output = StringBuilder(input.length + 8)
        val expectedClosers = ArrayDeque<Char>()
        var rootStarted = false
        var rootClosed = false
        var inString = false
        var escaped = false
        var removedExtraArrayClose = false
        var index = 0

        while (index < input.length) {
            if (rootClosed) {
                val tail = input.substring(index)
                if (tail.any { it != '}' && it != ']' && !it.isWhitespace() }) return null
                break
            }

            val current = input[index]
            if (inString) {
                output.append(current)
                if (escaped) {
                    escaped = false
                } else if (current == '\\') {
                    escaped = true
                } else if (current == '"') {
                    inString = false
                }
                index += 1
                continue
            }

            when (current) {
                '"' -> {
                    inString = true
                    output.append(current)
                }

                '{' -> {
                    if (rootStarted && expectedClosers.isEmpty()) return null
                    rootStarted = true
                    expectedClosers.addLast('}')
                    output.append(current)
                }

                '[' -> {
                    if (!rootStarted || expectedClosers.isEmpty()) return null
                    expectedClosers.addLast(']')
                    output.append(current)
                }

                ',' -> {
                    // A property name cannot be an array element. If one appears after a comma,
                    // the model omitted the array's closing bracket before the parent property.
                    if (expectedClosers.lastOrNull() == ']' &&
                        looksLikeObjectProperty(input, index + 1)
                    ) {
                        output.append(']')
                        expectedClosers.removeLast()
                    }
                    output.append(current)
                }

                '}' -> {
                    when (expectedClosers.lastOrNull()) {
                        '}' -> {
                            expectedClosers.removeLast()
                            output.append(current)
                            if (expectedClosers.isEmpty()) rootClosed = true
                        }

                        ']' -> {
                            // The array close is missing immediately before this object close.
                            output.append(']')
                            expectedClosers.removeLast()
                            // Process this same character again as the parent object close.
                            continue
                        }

                        else -> return null
                    }
                }

                ']' -> {
                    if (expectedClosers.lastOrNull() != ']') {
                        if (removedExtraArrayClose || !hasStructuralBoundaryAfter(input, index + 1)) {
                            return null
                        }
                        removedExtraArrayClose = true
                        index += 1
                        continue
                    }
                    expectedClosers.removeLast()
                    output.append(current)
                }

                else -> {
                    if (!rootStarted && !current.isWhitespace()) return null
                    // A property value that starts with neither a JSON token nor a quote, such as
                    // Markdown text after `"text": `, lost only its opening quote; its closing
                    // quote still ends the string, and the final parse rejects any other shape.
                    if (output.trimEnd().lastOrNull() == ':' && !current.isWhitespace() && current !in JSON_VALUE_STARTS) {
                        output.append('"')
                        inString = true
                    }
                    output.append(current)
                }
            }
            index += 1
        }

        if (inString || !rootStarted) return null
        while (expectedClosers.isNotEmpty()) {
            output.append(expectedClosers.removeLast())
        }

        return runCatching {
            (Json.parseToJsonElement(output.toString()) as? JsonObject)?.toString()
        }.getOrNull()
    }

    private fun looksLikeObjectProperty(input: String, startIndex: Int): Boolean {
        var index = startIndex
        while (index < input.length && input[index].isWhitespace()) index += 1
        if (index >= input.length || input[index] != '"') return false
        index += 1
        var escaped = false
        while (index < input.length) {
            val current = input[index]
            if (escaped) {
                escaped = false
            } else if (current == '\\') {
                escaped = true
            } else if (current == '"') {
                index += 1
                while (index < input.length && input[index].isWhitespace()) index += 1
                return index < input.length && input[index] == ':'
            }
            index += 1
        }
        return false
    }

    private fun hasStructuralBoundaryAfter(input: String, startIndex: Int): Boolean {
        var index = startIndex
        while (index < input.length && input[index].isWhitespace()) index += 1
        return index < input.length && input[index] in setOf('}', ',')
    }

}
