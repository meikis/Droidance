// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.tools

import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.tools.*
import com.droidance.core.device.tools.*

import com.droidance.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@kotlinx.serialization.Serializable
data class CompletionResult(
    val status: String,
    val summary: String,
    val checkpoint: String = "",
    val nextCheckSeconds: Long? = null,
    val goalComplete: Boolean = false,
    val notifyUser: Boolean? = null,
    val locallyGrounded: Boolean = false
)

class CompletionState {
    @Volatile
    var result: CompletionResult? = null
        private set

    @Synchronized
    fun record(candidate: CompletionResult): Boolean {
        if (result != null) return false
        result = candidate
        return true
    }

    @Synchronized
    fun reset() {
        result = null
    }
}

class CompletionToolRegistrar(
    private val registerTool: (Tool) -> Unit,
    private val stateProvider: () -> CompletionState,
    private val resultDescription: String,
    private val statusDescription: String,
    private val captureExperienceEnvironment: (Set<String>) -> Map<String, String>,
    private val recordCompletion: suspend (CompletionResult, CompletionState) -> ToolExecutionResult
) {
    fun registerAll() {
        registerTool(object : Tool {
            override val name = "done"
            override val tier = ToolTier.CORE
            override val category = ToolCategory.META
            override val definition = ToolDefinition(
                name = name,
                description = "Finish the current task once with a truthful status and the requested public answer.",
                parameters = mapOf(
                    "status" to ToolParameter(
                        "string",
                        statusDescription,
                        true,
                        enumValues = ALLOWED_STATUSES.toList()
                    ),
                    "results" to ToolParameter("array", resultDescription, true,
                        items = com.droidance.core.agent.harness.PublicResultEntry.schema(resultDescription, resultDescription)),
                    "unresolved_requirements" to ToolParameter("array", resultDescription, true,
                        items = ToolParameter("string", resultDescription)),
                    "experience_updates" to ToolParameter("array",
                        "Optional reusable method proposals from this task. Invalid proposals never alter task completion. Use stable semantic ids and exact expected_revision (0 for new). Methods are historical task-Agent assessments, not current device facts.", false,
                        items = ToolParameter("object", "Parameterized method with scoped assessment and original current-task evidence.", properties = mapOf(
                            "id" to ToolParameter("string", "Stable lowercase semantic id, independent of keyword/coordinates.", true),
                            "expected_revision" to ToolParameter("integer", "Exact retrieved revision; 0 for a new id.", true),
                            "title" to ToolParameter("string", "Short reusable task description.", true),
                            "package_name" to ToolParameter("string", "Applicability hint; empty for cross-app methods. Not a verified environment fact.", true),
                            "parameters" to ToolParameter("array", "Input names; never retain private values.", true, items = ToolParameter("string", "Parameter name")),
                            "preconditions" to ToolParameter("string", "English Markdown applicability and facts to recheck.", true),
                            "method" to ToolParameter("string", "English Markdown reusable method; no stale coordinates or volatile product facts.", true),
                            "decision_boundaries" to ToolParameter("string", "Where fresh inspection and Agent decisions are necessary.", true),
                            "expected_result" to ToolParameter("string", "Evidence required to assess the result.", true),
                            "recovery" to ToolParameter("string", "How to diagnose failure without replaying unknown effects.", true),
                            "requirement_id" to ToolParameter("string", "Exact supported requirement_checks requirement_id for succeeded; empty for unknown or failed assessments.", true),
                            "outcome" to ToolParameter("string", "Scoped assessment, not inferred from tool success.", true, listOf("succeeded", "failed", "unknown")),
                            "assessment" to ToolParameter("string", "What was checked and limitations of this assessment.", true),
                            "evidence_refs" to ToolParameter("array", "Original current-task event/observation refs supporting this assessment.", true, items = ToolParameter("string", "Evidence ref")),
                            "recall_phrases" to ToolParameter("array", "Short phrases a future user may type for this task, in the user's language; no private values.", false, items = ToolParameter("string", "Recall phrase"))
                        )), deferredValidation = true)
                ) + CompletionContinuationArguments.parameters
            )

            override suspend fun execute(arguments: String): ToolExecutionResult {
                val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                    .getOrElse { return ToolProtocolResult.invalidArguments() }
                val status = json["status"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
                if (status !in ALLOWED_STATUSES) {
                    return ToolProtocolResult.invalidArgument("status", "unsupported_value", status)
                }
                val resultsElem = json["results"] as? JsonArray
                    ?: return ToolProtocolResult.invalidArgument("results", "expected_array")
                val parsedEntries = runCatching { resultsElem.map(com.droidance.core.agent.harness.PublicResultEntry::parse) }
                    .getOrElse { return ToolProtocolResult.invalidArgument("results", it.message.orEmpty()) }
                val texts = parsedEntries.map { it.markdown() }
                val remaining = json["unresolved_requirements"] as? JsonArray
                    ?: return ToolProtocolResult.invalidArgument("unresolved_requirements", "explicit_array_required")
                val gaps = remaining.map { value ->
                        (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                            ?: return ToolProtocolResult.invalidArgument("unresolved_requirements", "expected_nonblank_text")
                }
                val summary = (texts + gaps).joinToString("\n\n")
                if (summary.isBlank()) return ToolProtocolResult.missingArgument("results")
                val candidate = try {
                    CompletionContinuationArguments.parse(json, status, summary)
                } catch (invalid: CompletionContinuationArguments.Invalid) {
                    return ToolProtocolResult.invalidArgument(invalid.field, invalid.reason)
                }
                val completed = recordCompletion(candidate, stateProvider())
                // Optional learning must never invalidate a valid task completion.
                val environment = runCatching {
                    val packages = (json["experience_updates"] as? JsonArray).orEmpty().take(8).mapNotNull {
                        (it as? JsonObject)?.get("package_name")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    }.toSet()
                    captureExperienceEnvironment(packages)
                }.getOrDefault(emptyMap())
                return if (!completed.isVerifiedSuccess || environment.isEmpty()) completed else completed.copy(
                    data = JsonObject((completed.data as? JsonObject).orEmpty() + ("experience_environment" to buildJsonObject {
                        environment.forEach { (name, version) -> put(name, version) }
                    })))
            }
        })
    }

    private companion object {
        val ALLOWED_STATUSES = linkedSetOf("done", "failed", "need_more_info")
    }

}
