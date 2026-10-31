// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object DeepLinkParams {
    fun parse(element: JsonElement?): Map<String, String> {
        if (element == null) return emptyMap()
        return when (element) {
            is JsonObject -> element.mapNotNull { (key, value) ->
                val cleanKey = key.trim()
                val cleanValue = runCatching { value.jsonPrimitive.contentOrNull?.trim() }.getOrNull()
                if (cleanKey.isNotBlank() && !cleanValue.isNullOrBlank()) cleanKey to cleanValue else null
            }.toMap()
            else -> parseText(element)
        }
    }

    fun format(keyword: String?, params: Map<String, String>): String? {
        val cleanParams = params.mapKeys { it.key.trim() }.mapValues { it.value.trim() }
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        return cleanParams.entries
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { "${it.key}=${it.value}" }
            ?: keyword?.takeIf { it.isNotBlank() }
    }

    private fun parseText(element: JsonElement): Map<String, String> {
        val raw = runCatching { element.jsonPrimitive.contentOrNull?.trim() }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyMap()
        return if (raw.startsWith("{") && raw.endsWith("}")) {
            runCatching { parse(Json.parseToJsonElement(raw)) }.getOrDefault(emptyMap())
        } else {
            mapOf("keyword" to raw)
        }
    }
}
