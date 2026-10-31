// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Hands a finished result to the user mid-task. The settled call is the durable record the conversation
 * shows; ordinary model text stays a live status. Not completion evidence and grants no authority.
 */
class ReportResultTool(private val rules: (String) -> String) : Tool {
    override val name = NAME
    override val tier = ToolTier.CORE
    override val category = ToolCategory.META
    override val definition get() = ToolDefinition(NAME, rules("report_result_tool"), linkedMapOf(
        TEXT to ToolParameter("string", rules("report_result_text_parameter"))
    ))

    override suspend fun execute(arguments: String): ToolExecutionResult {
        val text = runCatching {
            (Json.parseToJsonElement(arguments).jsonObject[TEXT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()
        if (text.isNullOrBlank()) return ToolProtocolResult.invalidArgument(TEXT, "expected_nonblank_text")
        return ToolProtocolResult.success("Shown to the user in the conversation.")
    }

    companion object {
        const val NAME = "report_result"
        const val TEXT = "text"

        /** The public text of a settled call, or null when the arguments carry none. */
        fun text(argumentsJson: String): String? = runCatching {
            (Json.parseToJsonElement(argumentsJson).jsonObject[TEXT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()?.takeIf(String::isNotBlank)
    }
}
