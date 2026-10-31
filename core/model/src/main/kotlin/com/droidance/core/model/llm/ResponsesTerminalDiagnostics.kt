// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.model.llm

import kotlinx.serialization.json.*

/** Terminal payload shape only; never exposes response text or tool arguments. */
object ResponsesTerminalDiagnostics {
    fun summarize(event: JsonObject, emittedContentChars: Int): String {
        val response = event["response"] as? JsonObject
        val output = (response?.get("output") as? JsonArray).orEmpty()
        val messages = output.mapNotNull { it as? JsonObject }
        val textChars = messages.sumOf { item ->
            (item["content"] as? JsonArray).orEmpty().sumOf { part ->
                val content = part as? JsonObject
                if ((content?.get("type") as? JsonPrimitive)?.contentOrNull == "output_text") {
                    (content["text"] as? JsonPrimitive)?.contentOrNull?.length ?: 0
                } else 0
            }
        }
        return buildJsonObject {
            put("type", (event["type"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            put("emittedContentChars", emittedContentChars)
            put("terminalOutputTextChars", textChars)
            put("outputItems", output.size)
            put("outputTypes", JsonArray(messages.map {
                JsonPrimitive((it["type"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }))
        }.toString()
    }
}
