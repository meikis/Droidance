// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

object ToolJson {
    fun sanitizeArgumentsJson(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.removePrefix("```json").removeSuffix("```").trim()
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```").removeSuffix("```").trim()
        }
        if (cleaned.contains("<parameter") || cleaned.contains("<call:")) {
            cleaned = cleaned.replace(Regex(""""><parameter[^:]*:\s*"""), "\": ")
            cleaned = cleaned.replace(Regex("""</?parameter[^>]*>"""), "")
            cleaned = cleaned.replace(Regex("""</?call:[^>]*>"""), "")
        }
        return cleaned
    }
}

fun JsonPrimitive.lenientBooleanOrNull(): Boolean? =
    if (isString) {
        when (content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    } else {
        booleanOrNull
    }

fun JsonObject.strictBooleanOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
