// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.agent.config

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Platform contracts are supplied by Markdown configuration, never inferred from a task. */
@Serializable
data class IntentExtraContract(
    @SerialName("intent_action") val intentAction: String,
    @SerialName("uri_prefix") val uriPrefix: String? = null,
    val extras: Map<String, IntentExtraField>
) {
    fun validate() {
        require(intentAction.isNotBlank())
        require(uriPrefix == null || uriPrefix.isNotBlank())
        extras.forEach { (name, field) ->
            require(name.isNotBlank() && field.type in setOf("string", "int", "long", "boolean"))
            require(field.min == null || field.max == null || field.min <= field.max)
            require(field.type in setOf("int", "long") || field.min == null && field.max == null)
        }
    }

    fun rejection(input: JsonObject): ToolExecutionResult? {
        validate()
        if (input["intent_action"]?.jsonPrimitive?.contentOrNull != intentAction ||
            uriPrefix != null && input["uri"]?.jsonPrimitive?.contentOrNull?.startsWith(uriPrefix) != true) return null
        extras.forEach { (name, field) ->
            val supplied = listOf("string", "int", "long", "boolean").filter { type ->
                (input["${type}_extras"] as? JsonObject)?.containsKey(name) == true
            }
            if (supplied.isEmpty()) return@forEach
            val expectedMap = "${field.type}_extras"
            val value = (input[expectedMap] as? JsonObject)?.get(name) as? JsonPrimitive
            val typeMatches = when (field.type) {
                "string" -> value?.isString == true
                "boolean" -> value?.isString == false && value.booleanOrNull != null
                "int" -> value?.isString == false && value.intOrNull != null
                "long" -> value?.isString == false && value.longOrNull != null
                else -> false
            }
            val numeric = value?.longOrNull
            val inRange = (field.min == null || numeric != null && numeric >= field.min) &&
                (field.max == null || numeric != null && numeric <= field.max)
            if (supplied != listOf(field.type) || !typeMatches || !inRange) {
                return ToolProtocolResult.notExecuted("INTENT_EXTRA_CONTRACT_MISMATCH", true, buildJsonObject {
                    put("intent_action", intentAction); put("extra", name); put("expected_map", expectedMap)
                    put("expected_type", field.type)
                    field.min?.let { put("minimum", it) }; field.max?.let { put("maximum", it) }
                    put("recovery_condition", "Repair this typed extra before dispatch; no Android action was executed.")
                })
            }
        }
        return null
    }
}

@Serializable
data class IntentExtraField(val type: String, val min: Long? = null, val max: Long? = null)
