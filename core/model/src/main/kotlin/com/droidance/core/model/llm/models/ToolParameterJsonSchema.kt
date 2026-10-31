// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun ToolParameter.toJsonSchema(): JsonObject = buildJsonObject {
    put("type", type)
    // Blank descriptions are omitted: they add request bytes to every round without guidance.
    (description + requiredWhen.entries.joinToString(separator = "", prefix = "") { (selector, values) ->
        " Required when $selector is ${values.joinToString(" or ")}."
    } + allowedWhen.entries.joinToString(separator = "") { (selector, values) ->
        " Only when $selector is ${values.joinToString(" or ")}."
    }).trim().takeIf(String::isNotEmpty)?.let { put("description", it) }
    enumValues?.let { values ->
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
    minLength?.let { put("minLength", it) }
    minItems?.let { put("minItems", it) }
    if (items != null) {
        put("items", items.toJsonSchema())
    } else if (type == "array") {
        put("items", buildJsonObject { put("type", "string") })
    }
    properties?.let { nested ->
        put(
            "properties",
            JsonObject(nested.toSortedMap().mapValues { (_, parameter) -> parameter.toJsonSchema() })
        )
        val requiredNames = nested.filterValues(ToolParameter::required).keys.sorted()
        if (requiredNames.isNotEmpty()) {
            put("required", JsonArray(requiredNames.map(::JsonPrimitive)))
        }
    }
    additionalPropertySchema?.let { schema ->
        put("additionalProperties", schema.toJsonSchema())
    } ?: additionalProperties?.let { allowed ->
        put("additionalProperties", allowed)
    }
}
