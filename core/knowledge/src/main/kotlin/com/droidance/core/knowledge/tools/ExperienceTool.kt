// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.knowledge.tools.*

import com.droidance.core.agent.event.*
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import com.droidance.core.agent.memory.*
import com.droidance.core.knowledge.memory.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** The current task Agent records learning; this tool only validates provenance and persists facts. */
class ExperienceTool(
    private val store: ExperienceLearningStore,
    private val events: AgentEventStore,
    private val scope: () -> TurnEventScope,
    private val invocationId: suspend () -> String,
    private val maxChars: () -> Int,
    private val installedVersions: (Set<String>) -> Map<String, String>,
    description: String
) : Tool {
    override val name = "experience"
    override val tier = ToolTier.CORE
    override val category = ToolCategory.MEMORY
    override val definition = ToolDefinition(name, description, mapOf(
        "op" to ToolParameter("string", "Save a method, record actual adoption, or assess a prior adoption.", true, listOf("save", "use", "assess")),
        "update" to ToolParameter("object", "For save: the complete parameterized method, replacing the exact expected revision.", false, properties = updateSchema()),
        "ref" to ToolParameter("string", "For use: exact experience:id:revision just expanded in this task.", false),
        "use_id" to ToolParameter("string", "For assess: use_id returned by use in this task.", false),
        "source_use_id" to ToolParameter("string", "For save: adoption whose failure or assessment informs this same method.", false),
        "source_quote" to ToolParameter("string", "For save: exact current user message or steer excerpt correcting this method. Not screen or tool text.", false),
        "outcome" to ToolParameter("string", "For assess: the task Agent's scoped result after checking fresh evidence.", false, listOf("succeeded", "failed", "unknown")),
        "summary" to ToolParameter("string", "For use/assess: why the method was adopted, or what was checked and remains uncertain.", false),
        "evidence_refs" to ToolParameter("array", "For assess: original current-task evidence obtained after use. Unknown may omit evidence.", false, items = ToolParameter("string", "Evidence reference"))
    ))

    override suspend fun execute(arguments: String): ToolExecutionResult = try {
        require(arguments.length <= maxChars() + 4000) { "EXPERIENCE_SIZE_LIMIT" }
        val original = Json.parseToJsonElement(arguments).jsonObject
        val args = Json.parseToJsonElement(MemorySecretRedactor.redactJson(arguments)).jsonObject
        val owner = scope()
        val callId = invocationId()
        val history = events.eventsForTurn(owner.turnId)
        val call = history.single { it.threadId == owner.threadId && it.callId == callId && it.eventKind == AgentEventKind.TOOL_CALL }
        val prior = history.filter { it.threadId == owner.threadId && it.turnId == owner.turnId && it.sequence < call.sequence }
        when (args.required("op")) {
            "save" -> {
                require(args.getValue("update").toString().length <= maxChars()) { "EXPERIENCE_SIZE_LIMIT" }
                val update = JSON.decodeFromJsonElement<ExperienceUpdate>(args.getValue("update"))
                ExperienceProjection.validateUpdate(update)
                val quote = original.string("source_quote")
                val correction = quote?.let { text ->
                    require(text.isNotBlank()) { "EXPERIENCE_CORRECTION_SOURCE_REQUIRED" }
                    requireNotNull(prior.lastOrNull { event ->
                        event.eventKind in setOf(AgentEventKind.USER_MESSAGE, AgentEventKind.USER_STEER) &&
                            JSON.decodeFromString<UserTextEventPayload>(event.payloadJson).text.contains(text)
                    }) { "EXPERIENCE_CORRECTION_SOURCE_REQUIRED" }
                }
                require(update.evidence_refs.isNotEmpty() || correction != null) { "EXPERIENCE_EVIDENCE_REQUIRED" }
                val sources = ExperienceEvidenceSources(prior, owner.threadId, owner.turnId, call.sequence)
                    .requireAll(update.evidence_refs)
                val sourceUse = args.string("source_use_id")?.let {
                    requireNotNull(store.use(it)) { "EXPERIENCE_USE_NOT_FOUND" }.also { use ->
                        require(use.threadId == owner.threadId && use.turnId == owner.turnId &&
                            use.experienceRef.substringBeforeLast(':') == "experience:${update.id}" &&
                            use.experienceRef.substringAfterLast(':').toLong() <= update.expected_revision) { "EXPERIENCE_USE_MISMATCH" }
                    }
                }
                // Early discoveries may remain unknown; a prior assessed use can support a scoped success.
                if (update.outcome == "succeeded") {
                    val originalMethod = store.current("experience:${update.id}:${update.expected_revision}")?.update
                    require(sourceUse?.outcome == "succeeded" &&
                        sourceUse.experienceRef == "experience:${update.id}:${update.expected_revision}" &&
                        update.evidence_refs.isNotEmpty() && update.evidence_refs.all { it in sourceUse.evidenceRefs }) {
                        "EXPERIENCE_ASSESSED_USE_REQUIRED"
                    }
                    require(originalMethod != null && update.copy(
                        expected_revision = originalMethod.expected_revision, requirement_id = originalMethod.requirement_id,
                        outcome = originalMethod.outcome, assessment = originalMethod.assessment,
                        evidence_refs = originalMethod.evidence_refs) == originalMethod) { "EXPERIENCE_CHANGED_METHOD_NOT_VERIFIED" }
                }
                if (sources.isEmpty()) require(update.outcome == "unknown") { "EXPERIENCE_CORRECTION_NOT_RESULT_PROOF" }
                val record = ExperienceRecord(update, update.expected_revision + 1, owner.threadId, owner.turnId,
                    call.sequence, sources.map { it.first } + listOfNotNull(correction?.eventId),
                    sources.mapNotNull { it.second }.distinct(), call.occurredAt,
                    installedVersions(setOf(update.package_name).filter(String::isNotBlank).toSet()),
                    sourceUseId = sourceUse?.useId, userCorrectionEventId = correction?.eventId,
                    userCorrectionQuote = args.string("source_quote"))
                receipt(store.save("experience-save:$callId", record), buildJsonObject {
                    put("ref", record.ref); put("outcome", update.outcome)
                })
            }
            "use" -> {
                val ref = args.required("ref")
                requireNotNull(store.current(ref)) { "EXPERIENCE_REFERENCE_UNAVAILABLE" }
                val expanded = SessionEvidenceProjector.steps(prior, owner.threadId, owner.turnId).any { step ->
                    step.call.name == "session_search" && step.result.isVerifiedSuccess &&
                        runCatching { JSON.parseToJsonElement(step.result.outputText).jsonObject["ref"]?.jsonPrimitive?.contentOrNull == ref }.getOrDefault(false)
                }
                require(expanded) { "EXPERIENCE_EXPAND_BEFORE_USE" }
                val use = ExperienceUseRecord("experience-use:$callId", ref, owner.threadId, owner.turnId,
                    call.sequence, "adopted", args.summary(), recordedAt = call.occurredAt)
                receipt(store.beginUse(use), buildJsonObject { put("use_id", use.useId); put("ref", ref); put("outcome", "adopted") })
            }
            "assess" -> {
                val use = requireNotNull(store.use(args.required("use_id"))) { "EXPERIENCE_USE_NOT_FOUND" }
                require(use.threadId == owner.threadId && use.turnId == owner.turnId) { "EXPERIENCE_USE_MISMATCH" }
                val outcome = args.required("outcome")
                require(outcome in setOf("succeeded", "failed", "unknown")) { "EXPERIENCE_OUTCOME_INVALID" }
                val refs = (args["evidence_refs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                require(refs.size <= 32 && (outcome == "unknown" || refs.isNotEmpty())) { "EXPERIENCE_EVIDENCE_REQUIRED" }
                val sources = ExperienceEvidenceSources(prior, owner.threadId, owner.turnId, call.sequence, use.throughSequence).requireAll(refs)
                val assessed = use.copy(outcome = outcome, summary = args.summary(), evidenceRefs = refs,
                    sourceEvents = sources.map { it.first }, assessedAt = call.occurredAt)
                receipt(store.assessUse("experience-assess:$callId", assessed), buildJsonObject {
                    put("use_id", use.useId); put("ref", use.experienceRef); put("outcome", outcome)
                    put("assessed_by", "task_agent")
                })
            }
            else -> ToolProtocolResult.invalidArgument("op", "unsupported_operation")
        }
    } catch (cancel: CancellationException) { throw cancel }
      catch (error: IllegalArgumentException) { ToolProtocolResult.notExecuted(
          error.message?.takeIf { it.matches(Regex("EXPERIENCE_[A-Z_]+")) } ?: "EXPERIENCE_ARGUMENT_INVALID", false)
      } catch (error: Exception) { ToolProtocolResult.failed("EXPERIENCE_OPERATION_FAILED", false, error) }

    private fun receipt(status: String, values: JsonObject): ToolExecutionResult =
        if (status == "APPLIED") ToolProtocolResult.success(buildJsonObject {
            put("persistence_confirmed", true); values.forEach { (key, value) -> put(key, value) }
        }.toString()).copy(stateChanged = true)
        else ToolProtocolResult.notExecuted(status, false)

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.required(key: String) = requireNotNull(string(key)?.takeIf(String::isNotBlank)) { "EXPERIENCE_ARGUMENT_INVALID" }
    private fun JsonObject.summary() = required("summary").also { require(it.length <= 2000) { "EXPERIENCE_SIZE_LIMIT" } }

    companion object {
        private val JSON = Json { encodeDefaults = true }
        private fun updateSchema(): Map<String, ToolParameter> = mapOf(
            "id" to ToolParameter("string", "Stable semantic method id; reuse it for corrections.", true),
            "expected_revision" to ToolParameter("integer", "Exact current revision; zero only for a new method.", true),
            "title" to ToolParameter("string", "Reusable task description.", true),
            "package_name" to ToolParameter("string", "App applicability hint; empty for cross-app methods.", true),
            "parameters" to ToolParameter("array", "Input names, never historical private values.", true, items = ToolParameter("string", "Parameter name")),
            "preconditions" to ToolParameter("string", "English Markdown conditions to recheck.", true),
            "method" to ToolParameter("string", "English Markdown correct route and scoped pitfalls.", true),
            "decision_boundaries" to ToolParameter("string", "Fresh observation and decision points.", true),
            "expected_result" to ToolParameter("string", "Result evidence to inspect.", true),
            "recovery" to ToolParameter("string", "How to diagnose failures and uncertain effects.", true),
            "requirement_id" to ToolParameter("string", "Completion requirement id when applicable; otherwise empty.", true),
            "outcome" to ToolParameter("string", "Scoped method assessment; early findings normally remain unknown.", true, listOf("succeeded", "failed", "unknown")),
            "assessment" to ToolParameter("string", "What the evidence supports and unverified limits.", true),
            "evidence_refs" to ToolParameter("array", "Original current-task observation refs; human-only corrections may be empty.", true, items = ToolParameter("string", "Evidence ref")),
            "recall_phrases" to ToolParameter("array", "Short phrases a future user may type for this task, in the user's language; no private values.", false, items = ToolParameter("string", "Recall phrase"))
        )
    }
}
