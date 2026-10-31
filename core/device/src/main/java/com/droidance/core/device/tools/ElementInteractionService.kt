// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ElementInteractionService(
    private val refreshScreenData: suspend () -> String?,
    private val searchElements: (String) -> List<DumpElement>,
    private val performVerifiedTap: suspend (String, String, String, Int, Int, DumpElement?, TapPrecision) -> ToolExecutionResult
) {
    suspend fun findAndClick(arguments: String): ToolExecutionResult {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }
            .getOrElse { return ToolProtocolResult.invalidArguments() }
        val query = json["query"]?.jsonPrimitive?.content
            ?: return ToolProtocolResult.missingArgument("query")
        val index = json["index"]?.jsonPrimitive?.intOrNull ?: 1
        refreshScreenData() ?: return ToolProtocolResult.notExecuted(
            code = "SCREEN_DATA_UNAVAILABLE",
            retryable = true
        )
        val matches = searchElements(query)
        if (matches.isEmpty()) {
            return ToolProtocolResult.notExecuted(
                code = "ELEMENT_NOT_FOUND",
                retryable = true,
                details = buildJsonObject {
                    put("query", JsonPrimitive(query.take(200)))
                }
            )
        }
        if (index !in 1..matches.size) {
            return ToolProtocolResult.notExecuted(
                code = "ELEMENT_INDEX_OUT_OF_RANGE",
                retryable = true,
                details = buildJsonObject {
                    put("query", JsonPrimitive(query.take(200)))
                    put("requested_index", JsonPrimitive(index))
                    put("match_count", JsonPrimitive(matches.size))
                }
            )
        }
        val target = matches[index - 1]
        val label = target.text.ifBlank { target.desc }.ifBlank { target.semanticLabel }
            .ifBlank { target.resourceId.substringAfterLast('/') }
        val result = performVerifiedTap(
            "find_and_click",
            arguments,
            "\"$label\"",
            target.cx,
            target.cy,
            target,
            TapPrecision.PRECISE
        )
        return if (matches.size > 1) {
            result.copy(outputText = "${result.outputText} (${matches.size} matches; tapped #$index)")
        } else {
            result
        }
    }
}
