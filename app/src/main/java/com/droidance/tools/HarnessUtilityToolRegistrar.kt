// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.harness.ResultCardToolArguments
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HumanAssistanceRequest(
    val boundaryType: String,
    val reason: String,
    val instruction: String?
)


class HarnessUtilityState {
    @Volatile
    var humanAssistanceRequest: HumanAssistanceRequest? = null
        private set

    @Volatile
    var resultCard: ResultCard? = null
        private set

    fun requestHuman(request: HumanAssistanceRequest) {
        humanAssistanceRequest = request
    }

    fun present(card: ResultCard) {
        resultCard = card
    }

    fun reset() {
        humanAssistanceRequest = null
        resultCard = null
    }
}

class HarnessUtilityToolRegistrar(
    private val registerTool: (Tool) -> Unit,
    private val stateProvider: () -> HarnessUtilityState,
    private val requestHuman: (reason: String, instruction: String?) -> Unit,
    private val allowedHumanBoundaryTypes: List<String>,
    private val recordCompletion: suspend (CompletionResult) -> ToolExecutionResult
) {
    fun registerAll() {
        registerAskHuman()
        registerPresentResultCard()
    }

    private fun registerAskHuman() = registerTool(object : Tool {
        override val name = "ask_human"
        override val tier = ToolTier.CORE
        override val category = ToolCategory.META
        override val definition = ToolDefinition(
            name = name,
            description = "Pause and ask the user to handle the current app only after an active device workflow reaches a boundary configured to require human action or judgment. For ordinary clarification, reply directly instead.",
            parameters = mapOf(
                "boundary_type" to ToolParameter(
                    "string",
                    "Configured human-only boundary reached by the active device workflow.",
                    true,
                    enumValues = allowedHumanBoundaryTypes
                ),
                "reason" to ToolParameter("string", "Why human assistance is required.", true),
                "instruction" to ToolParameter("string", "Optional concise instruction for the user.", false)
            )
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            val json = arguments.jsonOrError() ?: return ToolProtocolResult.invalidArguments()
            val boundaryType = json.string("boundary_type")?.trim().orEmpty()
            val reason = json.string("reason")?.trim().orEmpty()
            val instruction = json.string("instruction")?.trim()?.takeIf(String::isNotBlank)
            if (boundaryType !in allowedHumanBoundaryTypes) {
                return ToolProtocolResult.notExecuted(
                    code = "HUMAN_BOUNDARY_REJECTED",
                    retryable = true,
                    details = buildJsonObject {
                        put("boundary_type", JsonPrimitive(boundaryType))
                        put("allowed_boundary_types", JsonArray(allowedHumanBoundaryTypes.map(::JsonPrimitive)))
                    }
                )
            }
            if (reason.isBlank()) return ToolProtocolResult.missingArgument("reason")
            val request = HumanAssistanceRequest(boundaryType, reason, instruction)
            stateProvider().requestHuman(request)
            requestHuman(reason, instruction)
            return ToolProtocolResult.success("Human assistance requested. The task is paused until the user resumes it.")
        }
    })

    private fun registerPresentResultCard() = registerTool(object : Tool {
        override val name = "present_result_card"
        override val tier = ToolTier.CORE
        override val category = ToolCategory.META
        override val definition = ToolDefinition(
            name = name,
            description = "Store a concise structured result card when tabular or repeated information is clearer than prose.",
            parameters = mapOf(
                "title" to ToolParameter("string", "Card title.", true),
                "items" to ToolParameter(
                    "array",
                    "Public result entries.",
                    true,
                    items = com.droidance.core.agent.harness.PublicResultEntry.schema("Markdown text.", "Original source references.")
                )
            ) + CompletionContinuationArguments.parameters
        )

        override suspend fun execute(arguments: String): ToolExecutionResult {
            return try {
                val card = ResultCardToolArguments.parseCompletion(arguments)
                val resultCard = ResultCard(card.title, card.items)
                val candidate = CompletionContinuationArguments.parse(
                    Json.parseToJsonElement(arguments).jsonObject, "done", resultCard.toUserFacingText())
                val result = recordCompletion(candidate)
                if (result.isVerifiedSuccess) stateProvider().present(resultCard)
                result
            } catch (invalid: CompletionContinuationArguments.Invalid) {
                ToolProtocolResult.invalidArgument(invalid.field, invalid.reason)
            } catch (error: IllegalArgumentException) {
                ToolProtocolResult.failed("RESULT_CARD_ARGUMENT_INVALID", retryable = true, error)
            }
        }
    })

    private fun String.jsonOrError(): JsonObject? =
        runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        strictBooleanOrNull(key) == true

}
