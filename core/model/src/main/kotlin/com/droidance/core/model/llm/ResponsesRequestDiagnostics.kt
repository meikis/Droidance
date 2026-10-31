// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Describes the final wire request without retaining prompts, images, or credentials. */
object ResponsesRequestDiagnostics {
    fun summarize(request: JsonObject): String {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return buildJsonObject {
            put("requestHash", hash(request.toString()))
            put("continuation", "previous_response_id" in request)
            put("instructionsHash", hash((request["instructions"] as? JsonPrimitive)?.contentOrNull.orEmpty()))
            put("input", buildJsonArray {
                (request["input"] as? JsonArray).orEmpty().forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    add(buildJsonObject {
                        listOf("type", "role").forEach { key -> obj[key]?.let { put(key, it) } }
                        put("itemHash", hash(obj.toString()))
                        put("content", buildJsonArray {
                            (obj["content"] as? JsonArray).orEmpty().forEach partLoop@ { part ->
                                val content = part as? JsonObject ?: return@partLoop
                                add(buildJsonObject {
                                    content["type"]?.let { put("type", it) }
                                    listOf("text", "image_url").forEach { key ->
                                        (content[key] as? JsonPrimitive)?.contentOrNull?.let { value ->
                                            put("${key}Chars", value.length)
                                            put("${key}Hash", hash(value))
                                        }
                                    }
                                })
                            }
                        })
                    })
                }
            })
        }.toString()
    }
}
