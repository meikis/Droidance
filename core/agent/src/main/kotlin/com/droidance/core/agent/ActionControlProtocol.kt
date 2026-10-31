// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Carries the mobile-use control loop in tool arguments instead of public text or hidden
 * reasoning. Protocol fields are archived with the ModelRound before execution strips them.
 */
object ActionControlProtocol {
    const val EVIDENCE_REFS_ARGUMENT = "evidence_refs"
    const val TERMINAL_OBSERVATION_REF_ARGUMENT = "terminal_observation_ref"
    const val RESULTS_ARGUMENT = "results"
    const val UNRESOLVED_ARGUMENT = "unresolved_requirements"
    const val CLAIMS_METADATA = "completion_claims"
    const val CONTENT_ARGUMENTS_METADATA = "completion_content_arguments_by_order"

    data class Descriptions(
        val evidenceRefs: String,
        val terminalObservationRef: String,
        val claims: String,
        val claimResult: String,
        val unresolvedRequirements: String,
        val requirementChecks: String
    )

    data class Control(
        val toolCallId: String,
        val evidenceRefs: String?,
        val terminalObservationRef: String? = null,
        val claims: List<Claim>? = null,
        val rawClaims: String? = null,
        val invalidArgument: String? = null,
        val requirementChecks: List<CompletionCoverage.Check> = emptyList(),
        val invalidReason: String = "invalid_value",
        val originalArguments: String? = null,
        val requirementChecksDeclared: Boolean = true,
        val invalidDetails: JsonObject? = null
    )

    data class Claim(val result: String, val evidenceRefs: List<String>)

    data class Result(
        val controls: Map<String, Control>,
        val toolCalls: List<ToolCall>,
        val finishMetadata: Map<String, String>
    )

    /** Read the exact archived call through the current completion protocol, including result-source mapping. */
    fun archivedControl(finishMetadata: Map<String, String>, tool: String, providerOrder: Int,
                        providerCallId: String?): Control? = runCatching {
        val archived = Json.parseToJsonElement(finishMetadata.getValue(CLAIMS_METADATA)) as? JsonArray
            ?: return@runCatching null
        val call = archived.mapNotNull { it as? JsonObject }.singleOrNull {
            (it["tool"] as? JsonPrimitive)?.contentOrNull == tool &&
                (it["providerOrder"] as? JsonPrimitive)?.intOrNull == providerOrder &&
                (providerCallId == null || (it["providerCallId"] as? JsonPrimitive)?.contentOrNull == providerCallId)
        } ?: return@runCatching null
        val arguments = call["control"] as? JsonObject ?: return@runCatching null
        extract(listOf(ToolCall(providerCallId.orEmpty(), tool, arguments.toString())))
            .controls[providerCallId.orEmpty()]?.takeIf { it.invalidArgument == null }
    }.getOrNull()

    fun addControlParameters(
        tools: List<ToolDefinition>,
        descriptions: Descriptions,
        completionTools: Set<String>
    ): List<ToolDefinition> = tools.map { tool ->
        val parameters = linkedMapOf<String, ToolParameter>()
        parameters.putAll(tool.parameters)
        if (tool.name in completionTools) {
            parameters[TERMINAL_OBSERVATION_REF_ARGUMENT] = ToolParameter(
                type = "string",
                description = descriptions.terminalObservationRef,
                required = false
            )
            parameters[CompletionCoverage.ARGUMENT] = CompletionCoverage.schema(descriptions.requirementChecks, descriptions.evidenceRefs)
            parameters[UNRESOLVED_ARGUMENT] = ToolParameter(
                type = "array", description = descriptions.unresolvedRequirements, required = true,
                items = ToolParameter("string", descriptions.unresolvedRequirements)
            )
            val key = if (tool.name == "done") RESULTS_ARGUMENT else "items"
            parameters[key] = ToolParameter("array", descriptions.claims, required = true,
                items = com.droidance.core.agent.harness.PublicResultEntry.schema(descriptions.claimResult, descriptions.evidenceRefs))
        }
        tool.copy(parameters = parameters)
    }

    /** Only model-submitted references may support a completion claim. */
    fun completionEvidenceReferences(control: Control?): String {
        if (control == null || control.invalidArgument != null) return ""
        return (control.claims.orEmpty().flatMap { it.evidenceRefs } +
            control.requirementChecks.filter { it.status == "supported" && it.resultIndices.isEmpty() }.flatMap { it.evidenceRefs })
            .map(String::trim).filter(String::isNotBlank).distinct().joinToString(",")
    }

    /** Validates supplied references without requiring citations or certifying semantic success. */
    fun completionReferencesValid(control: Control?, isGrounded: (String) -> Boolean): Boolean {
        if (control?.invalidArgument != null) return false
        val claims = control?.claims?.takeIf { it.isNotEmpty() } ?: return false
        val references = completionEvidenceReferences(control)
        return CompletionCoverage.suppliedReferencesValid(control.requirementChecks, isGrounded) &&
            CompletionCoverage.unboundResults(control.requirementChecks, claims).isEmpty() && claims.all { claim ->
            claim.result.isNotBlank() && (claim.evidenceRefs.isEmpty() ||
                (claim.evidenceRefs.all { ref ->
                    ref.isNotBlank() && referencesContain(references.split(','), ref)
                } && isGrounded(claim.evidenceRefs.joinToString(","))))
        }
    }

    /** Machine-readable repair diagnostics; never invents or substitutes evidence. */
    fun completionClaimIssues(
        control: Control?,
        availableRefs: List<String> = emptyList(),
        unresolved: (String) -> List<String>
    ): JsonObject {
        val aggregate = completionEvidenceReferences(control)
        val claims = control?.claims.orEmpty()
        val checks = control?.requirementChecks.orEmpty()
        val invalidRequirementIssues = checks.mapIndexedNotNull { index, check ->
            if (check.status != "supported") return@mapIndexedNotNull null
            val missing = check.evidenceRefs.isNotEmpty() && check.evidenceRefs.any(String::isBlank)
            val unavailable = if (check.evidenceRefs.isEmpty()) emptyList() else unresolved(check.evidenceRefs.joinToString(","))
            if (!missing && unavailable.isEmpty()) return@mapIndexedNotNull null
            index to buildJsonObject {
                put("requirement_index", JsonPrimitive(index))
                put("requirement_id", JsonPrimitive(check.requirement))
                put("field", JsonPrimitive(if (check.resultIndices.isEmpty())
                    "requirement_checks[$index].evidence_refs" else "results"))
                put("result_indices", JsonArray(check.resultIndices.map(::JsonPrimitive)))
                put("blank_reference", JsonPrimitive(missing))
                put("unavailable_references", JsonArray(unavailable.map(::JsonPrimitive)))
            }
        }
        val invalidRequirementIndices = invalidRequirementIssues.map { it.first }.toSet()
        return kotlinx.serialization.json.buildJsonObject {
            put("results_missing", JsonPrimitive(claims.isEmpty()))
            put("invalid_requirement_indices", JsonArray(invalidRequirementIndices.map(::JsonPrimitive)))
            put("invalid_requirement_issues", JsonArray(invalidRequirementIssues.map { it.second }))
            put("unresolved_requirement_evidence_refs", JsonArray(checks
                .filter { it.status == "supported" && it.evidenceRefs.isNotEmpty() }
                .flatMap { unresolved(it.evidenceRefs.joinToString(",")) }.distinct().map(::JsonPrimitive)))
            if (availableRefs.isNotEmpty()) {
                put("available_evidence_refs", JsonArray(
                    availableRefs.filter(String::isNotBlank).distinct().map(::JsonPrimitive)
                ))
            }
            val unbound = CompletionCoverage.unboundResults(checks, claims)
            put("unbound_requirement_indices", JsonArray(unbound.map(::JsonPrimitive)))
            // A rejected source is a field to repair, never a required citation to propagate.
            val bindingIssues = unbound.filter {
                checks[it].status == "supported" && it !in invalidRequirementIndices
            }
            put("source_binding_issues", JsonArray(bindingIssues.map { index ->
                val check = checks[index]
                kotlinx.serialization.json.buildJsonObject {
                    put("requirement_index", JsonPrimitive(index))
                    put("requirement_id", JsonPrimitive(check.requirement))
                    put("result_indices", JsonArray(check.resultIndices.map(::JsonPrimitive)))
                    put("required_evidence_refs", JsonArray(check.evidenceRefs.map(::JsonPrimitive)))
                    val selectedReferences = check.resultIndices.mapNotNull(claims::getOrNull)
                        .flatMap { it.evidenceRefs }
                    put("missing_evidence_refs", JsonArray(check.evidenceRefs.filter {
                        !referencesContain(selectedReferences, it)
                    }.map { JsonPrimitive(it.trim()) }))
                    put("result_matches", JsonArray(claims.mapIndexed { resultIndex, claim ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("result_index", JsonPrimitive(resultIndex))
                            put("selected", JsonPrimitive(resultIndex in check.resultIndices))
                            put("entry_evidence_refs", JsonArray(claim.evidenceRefs.map(::JsonPrimitive)))
                            put("missing_evidence_refs", JsonArray(check.evidenceRefs.filter {
                                !referencesContain(claim.evidenceRefs, it)
                            }.map { JsonPrimitive(it.trim()) }))
                            put("references_present", JsonPrimitive(check.evidenceRefs.all {
                                referencesContain(claim.evidenceRefs, it)
                            }))
                        }
                    }))
                }
            }))
            control?.invalidArgument?.let { put("invalid_argument", JsonPrimitive(it)) }
            put("invalid_result_indices", JsonArray(claims.mapIndexedNotNull { index, claim ->
                JsonPrimitive(index).takeIf {
                    claim.result.isBlank() || (claim.evidenceRefs.isNotEmpty() &&
                        claim.evidenceRefs.any { ref -> ref.isBlank() || !referencesContain(aggregate.split(','), ref) })
                }
            }))
            put("unresolved_evidence_refs", JsonArray(
                claims.filter { it.evidenceRefs.isNotEmpty() }
                    .flatMap { unresolved(it.evidenceRefs.joinToString(",")) }.distinct().map(::JsonPrimitive)
            ))
        }
    }

    /** Native structured draft fields and explicit same-candidate review. */
    fun submissionTools(tools: List<ToolDefinition>): List<ToolDefinition> = tools.map { tool ->
        if (tool.name !in setOf("done", "present_result_card")) tool else tool.copy(
            description = tool.description + " Submit the public content fields directly. Files, citations and coverage notes are optional. Never wrap fields or encode objects as strings.",
            parameters = CompletionSubmissionProtocol.parameters(tool.parameters)
        )
    }

    fun repairsSubmission(control: Control): Boolean =
        control.invalidArgument == CompletionSubmissionProtocol.ARGUMENT

    fun argumentFailure(control: Control, parameters: Map<String, ToolParameter>, fileReference: JsonObject? = null): com.droidance.core.agent.event.ToolExecutionResult {
        val repair = parameters
        val args = control.originalArguments?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        return com.droidance.core.agent.tools.ToolProtocolResult.notExecuted("ARGUMENT_SCHEMA_MISMATCH", retryable = true,
            details = kotlinx.serialization.json.buildJsonObject {
                put("repair_target", JsonPrimitive(if (repairsSubmission(control))
                    "tool_arguments" else "draft"))
                put("parameter", JsonPrimitive(control.invalidArgument))
                put("reason", JsonPrimitive(control.invalidReason))
                control.invalidDetails?.forEach { (name, value) -> put(name, value) }
                fileReference?.let { put("file_reference", it) }
                put("schema_issues", JsonArray(repair.mapNotNull { (name, schema) ->
                    com.droidance.core.agent.harness.ToolArgumentSchemaValidator.mismatchDetail(
                        mapOf(name to schema), control.originalArguments ?: "{}")?.let(::JsonPrimitive)
                }.distinct()))
                put("expected_schema", CompletionSubmissionProtocol.contentSchema(repair))
                put("required_parameters", JsonArray(repair.filterValues { it.required }.keys.map(::JsonPrimitive)))
                put("missing_parameters", JsonArray(repair.filter { (name, field) ->
                    args != null && field.required && (args[name] == null || args[name] is JsonNull)
                }.keys.map(::JsonPrimitive)))
            })
    }

    fun referencesContain(references: List<String>, reference: String?): Boolean =
        reference?.trim()?.takeIf(String::isNotBlank)?.let { it in references } == true

    /** Compile the single model-authored public body to the executor's durable arguments. */
    fun extract(toolCalls: List<ToolCall>, completionSubmissionErrors: Map<String, String> = emptyMap(),
                completionSubmissionErrorDetails: Map<String, JsonObject> = emptyMap()): Result {
        val controls = linkedMapOf<String, Control>()
        val archived = mutableListOf<JsonObject>()
        val sanitized = toolCalls.mapIndexed { order, call ->
            if (call.name !in setOf("done", "present_result_card")) return@mapIndexed call
            val args = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
                ?: run {
                    controls[call.id] = Control(call.id, null,
                        invalidArgument = CompletionSubmissionProtocol.ARGUMENT,
                        invalidReason = completionSubmissionErrors[call.id] ?: "completion_arguments_must_be_json_object",
                        originalArguments = call.arguments, requirementChecksDeclared = false)
                    return@mapIndexed call
                }
            val bodyKey = if (call.name == "done") RESULTS_ARGUMENT else "items"
            val rawEntries = args[bodyKey]
            val entries = rawEntries as? JsonArray
            val remaining = if (UNRESOLVED_ARGUMENT in args) args[UNRESOLVED_ARGUMENT] as? JsonArray else JsonArray(emptyList())
            val remainingValid = remaining != null && remaining.all {
                it is JsonPrimitive && it.isString && it.content.isNotBlank()
            }
            val entryResults = entries?.map { element ->
                runCatching { com.droidance.core.agent.harness.PublicResultEntry.parse(element) }
            }
            val parsedClaims = entryResults?.takeIf { results -> results.all { it.isSuccess } }?.map {
                val entry = it.getOrThrow()
                Claim(entry.text, entry.evidenceRefs)
            }
            val checksDeclared = CompletionCoverage.ARGUMENT in args
            val repeatedResultSources = (args[CompletionCoverage.ARGUMENT] as? JsonArray).orEmpty().any { element ->
                val row = element as? JsonObject
                row != null && EVIDENCE_REFS_ARGUMENT in row &&
                    !(row["result_indices"] as? JsonArray).isNullOrEmpty()
            }
            val checks = if (checksDeclared) {
                CompletionCoverage.parse(args[CompletionCoverage.ARGUMENT], remaining?.size ?: 0, parsedClaims.orEmpty())
            } else emptyList()
            val invalid = when {
                call.id in completionSubmissionErrors || CompletionSubmissionProtocol.ARGUMENT in args -> CompletionSubmissionProtocol.ARGUMENT
                "claims" in args -> "claims"
                EVIDENCE_REFS_ARGUMENT in args -> EVIDENCE_REFS_ARGUMENT
                "summary" in args && bodyKey in args -> "summary"
                "summary" in args && call.name != "done" -> "summary"
                listOf(TERMINAL_OBSERVATION_REF_ARGUMENT).any {
                    it in args && (args[it] as? JsonPrimitive)?.isString != true
                } -> "evidence_controls"
                // The model's status is its own judgment; listed gaps are delivered with the answer.
                !remainingValid -> UNRESOLVED_ARGUMENT
                entries == null || (entries.isEmpty() && remaining.isNullOrEmpty()) -> bodyKey
                parsedClaims == null -> "$bodyKey[${entryResults?.indexOfFirst { it.isFailure }}]"
                checks == null -> CompletionCoverage.ARGUMENT
                else -> null
            }
            val claims = parsedClaims.takeIf { invalid == null }
            controls[call.id] = Control(call.id, null,
                args.string(TERMINAL_OBSERVATION_REF_ARGUMENT), claims, entries?.toString(), invalid, checks.orEmpty(),
                invalidReason = when {
                    invalid == CompletionSubmissionProtocol.ARGUMENT -> completionSubmissionErrors[call.id] ?: "completion_submission_not_resolved"
                    invalid == CompletionCoverage.ARGUMENT && !checksDeclared -> "required_parameter_missing"
                    invalid == CompletionCoverage.ARGUMENT && repeatedResultSources -> "result_bound_check_sources_must_match_selected_results"
                    invalid in setOf("summary", "claims", EVIDENCE_REFS_ARGUMENT) -> "prohibited_parameter"
                    else -> "invalid_value"
                }, originalArguments = call.arguments, requirementChecksDeclared = checksDeclared,
                invalidDetails = completionSubmissionErrorDetails[call.id])
            archived += JsonObject(mapOf(
                "providerOrder" to JsonPrimitive(order), "providerCallId" to JsonPrimitive(call.id),
                "tool" to JsonPrimitive(call.name),
                "control" to JsonObject(args.filterKeys {
                    it == bodyKey || it == UNRESOLVED_ARGUMENT || it == CompletionCoverage.ARGUMENT || it in setOf(EVIDENCE_REFS_ARGUMENT, TERMINAL_OBSERVATION_REF_ARGUMENT, "claims", "summary")
                })
            ))
            val execution = args.filterKeys {
                it !in setOf(CompletionSubmissionProtocol.ARGUMENT, CompletionCoverage.ARGUMENT, EVIDENCE_REFS_ARGUMENT, TERMINAL_OBSERVATION_REF_ARGUMENT, "claims")
            }.toMutableMap()
            if (invalid == null && entries != null) {
                if (call.name == "present_result_card") {
                    execution.remove(UNRESOLVED_ARGUMENT)
                    execution["items"] = entries
                } else if (call.name == "done") {
                    execution["results"] = entries
                }
            }
            call.copy(arguments = JsonObject(execution).toString())
        }
        return Result(controls, sanitized, if (archived.isEmpty()) emptyMap()
            else mapOf(
                CLAIMS_METADATA to JsonArray(archived).toString(),
                CONTENT_ARGUMENTS_METADATA to JsonObject(
                    toolCalls.mapIndexedNotNull { index, call ->
                        if (call.name in setOf("done", "present_result_card")) index.toString() to JsonPrimitive(call.arguments)
                        else null
                    }.toMap()
                ).toString()
            ))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
}
