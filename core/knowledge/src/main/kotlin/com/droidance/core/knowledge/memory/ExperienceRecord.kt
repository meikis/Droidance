// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import com.droidance.core.agent.event.*
import com.droidance.core.agent.ActionControlProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** A reusable method is an Agent assessment, never proof of a current screen or task result. */
@Serializable
data class ExperienceUpdate(
    val id: String,
    val expected_revision: Long,
    val title: String,
    val package_name: String,
    val parameters: List<String>,
    val preconditions: String,
    val method: String,
    val decision_boundaries: String,
    val expected_result: String,
    val recovery: String,
    val requirement_id: String,
    val outcome: String,
    val assessment: String,
    val evidence_refs: List<String>,
    val recall_phrases: List<String> = emptyList()
)

@Serializable
data class ExperienceRecord(
    val update: ExperienceUpdate,
    val revision: Long,
    val threadId: String,
    val turnId: String,
    val throughSequence: Long,
    val sourceEvents: List<String>,
    val executionContexts: List<ExecutionContextSnapshotPayload>,
    val recordedAt: Long,
    val installedPackageVersions: Map<String, String> = emptyMap(),
    val assessmentAuthority: String = "task_agent",
    val applicabilityAuthority: String = "task_agent_requires_current_verification",
    val sourceUseId: String? = null,
    val userCorrectionEventId: String? = null,
    val userCorrectionQuote: String? = null
) {
    val ref: String get() = "experience:${update.id}:$revision"
}

object ExperienceProjection {
    data class Proposal(val key: String, val record: ExperienceRecord?, val error: String?)
    private val json = Json { encodeDefaults = true }

    fun validateUpdate(update: ExperienceUpdate) {
        require(update.title.length <= 500 && update.package_name.length <= 255) { "EXPERIENCE_SUMMARY_LIMIT" }
        require(update.id.matches(Regex("[a-z][a-z0-9_-]{2,79}"))) { "EXPERIENCE_ID_INVALID" }
        require(update.expected_revision in 0 until Long.MAX_VALUE && update.parameters.size <= 32 &&
            update.parameters.all { it.isNotBlank() && it.length <= 80 }) { "EXPERIENCE_PARAMETERS_INVALID" }
        require(listOf(update.title, update.preconditions, update.method, update.decision_boundaries,
            update.expected_result, update.recovery, update.assessment).all { it.isNotBlank() }) { "EXPERIENCE_METHOD_INCOMPLETE" }
        require(update.outcome in setOf("succeeded", "failed", "unknown")) { "EXPERIENCE_OUTCOME_INVALID" }
        require(update.evidence_refs.size <= 32 && update.evidence_refs.all(String::isNotBlank)) { "EXPERIENCE_EVIDENCE_REQUIRED" }
        require(update.recall_phrases.size <= 16 && update.recall_phrases.all { it.isNotBlank() && it.length <= 80 }) { "EXPERIENCE_RECALL_PHRASES_INVALID" }
    }

    fun project(events: List<AgentEventEnvelope>, turnId: String, maxUpdates: Int, maxChars: Int): List<Proposal> {
        val scoped = events.filter { it.turnId == turnId }.sortedBy { it.sequence }
        val completed = scoped.singleOrNull { it.eventKind == AgentEventKind.TURN_COMPLETED } ?: return emptyList()
        val steps = SessionEvidenceProjector.steps(scoped, completed.threadId, turnId)
        return steps.filter { it.call.name == "done" && it.result.isVerifiedSuccess }.flatMap { done ->
            val value = Json.parseToJsonElement(done.call.argumentsJson).jsonObject["experience_updates"] ?: return@flatMap emptyList()
            if (value == JsonNull) return@flatMap emptyList()
            val updates = value as? JsonArray
            if (updates == null || updates.size > maxUpdates) return@flatMap listOf(Proposal("${done.event.eventId}:invalid", null, "EXPERIENCE_UPDATE_LIMIT_OR_FORMAT"))
            updates.mapIndexed { index, element ->
                val key = "${done.event.eventId}:$index"
                try {
                    require(element.toString().length <= maxChars) { "EXPERIENCE_SIZE_LIMIT" }
                    val update = json.decodeFromJsonElement<ExperienceUpdate>(Json.parseToJsonElement(MemorySecretRedactor.redactJson(element.toString())))
                    validateUpdate(update)
                    require(update.evidence_refs.isNotEmpty()) { "EXPERIENCE_EVIDENCE_REQUIRED" }
                    val prior = scoped.filter { it.sequence < done.event.sequence }
                    val evidence = ExperienceEvidenceSources(prior, completed.threadId, turnId, done.event.sequence)
                    fun resolve(ref: String) = evidence.resolve(ref)
                    val sources = update.evidence_refs.map { ref ->
                        requireNotNull(resolve(ref)) { "EXPERIENCE_SOURCE_NOT_CURRENT_EXTERNAL_EVIDENCE" }
                    }.distinctBy { it.first }
                    if (update.outcome == "succeeded") {
                        val controls = scoped.filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED && it.roundId == done.event.roundId }
                            .mapNotNull { event ->
                                val metadata = json.decodeFromString<ModelRoundCompletedEventPayload>(event.payloadJson).finishMetadata
                                ActionControlProtocol.archivedControl(metadata, done.call.name,
                                    done.call.providerOrder, done.call.providerCallId)
                            }
                        val check = controls.singleOrNull()?.requirementChecks?.singleOrNull {
                            it.requirement == update.requirement_id && it.status == "supported"
                        }
                        require(check != null) { "EXPERIENCE_SUPPORTED_REQUIREMENT_REQUIRED" }
                        val checkRefs = check.evidenceRefs
                        require(sources.all { source -> checkRefs.any { ref ->
                            resolve(ref)?.first == source.first
                        } }) { "EXPERIENCE_REQUIREMENT_EVIDENCE_MISMATCH" }
                    }
                    // Preserve the Agent's explicitly scoped assessment. A tool success never upgrades it.
                    Proposal(key, ExperienceRecord(update, update.expected_revision + 1, completed.threadId, turnId,
                        completed.sequence, sources.map { it.first }, sources.mapNotNull { it.second }.distinct(), completed.occurredAt,
                        installedPackageVersions = ((done.result.data as? JsonObject)?.get("experience_environment") as? JsonObject).orEmpty()
                            .mapNotNull { (name, version) -> (version as? JsonPrimitive)?.contentOrNull?.let { name to it } }.toMap()), null)
                } catch (error: IllegalArgumentException) {
                    Proposal(key, null, error.message?.takeIf { it.startsWith("EXPERIENCE_") } ?: "EXPERIENCE_INVALID")
                }
            }
        }
    }
}
