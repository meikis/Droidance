// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.knowledge.memory

import com.droidance.core.agent.memory.*

import com.droidance.core.knowledge.memory.*

import com.droidance.core.agent.event.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ExperienceUseRecord(
    val useId: String,
    val experienceRef: String,
    val threadId: String,
    val turnId: String,
    val throughSequence: Long,
    val outcome: String,
    val summary: String,
    val evidenceRefs: List<String> = emptyList(),
    val sourceEvents: List<String> = emptyList(),
    val recordedAt: Long,
    val assessedAt: Long? = null
)

/** Storage receipts and compare-and-set rules belong to the persistent implementation. */
interface ExperienceLearningStore {
    suspend fun current(ref: String): ExperienceRecord?
    suspend fun save(key: String, record: ExperienceRecord): String
    suspend fun use(useId: String): ExperienceUseRecord?
    suspend fun beginUse(record: ExperienceUseRecord): String
    suspend fun assessUse(key: String, record: ExperienceUseRecord): String
}

/** Original evidence only. Neither a local write receipt nor historical pixels prove a result. */
class ExperienceEvidenceSources(
    events: List<AgentEventEnvelope>, threadId: String, turnId: String,
    beforeSequence: Long, afterSequence: Long = 0
) {
    private val prior = events.filter { it.threadId == threadId && it.turnId == turnId &&
        it.sequence < beforeSequence }
    private val steps = SessionEvidenceProjector.steps(prior, threadId, turnId)
        .filter { it.event.sequence > afterSequence }
    private val programs = ProgramEvidence.events(prior, threadId, turnId)
        .filter { it.sequence > afterSequence }

    fun resolve(ref: String): Pair<String, ExecutionContextSnapshotPayload?>? {
        val raw = ref.removePrefix("event:").removePrefix("observation:")
        SessionEvidenceProjector.find(steps, raw)?.let { step ->
            if (step.call.name == "done" || step.result.historicalAttachment != null ||
                !(step.attachments.isNotEmpty() || (step.call.binding != ToolBinding.TURN_LOCAL &&
                    step.call.effect == ToolEffect.READ_ONLY && step.result.isVerifiedSuccess &&
                    step.result.outputText.isNotBlank()))) return null
            return step.event.eventId to step.executionContext
        }
        val program = programs.firstOrNull { event ->
            val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
            val data = payload["data"] as? JsonObject
            val image = (data?.get("image_ref") as? JsonPrimitive)?.contentOrNull
            payload["phase"]?.jsonPrimitive?.contentOrNull == "device_finished" && image != null &&
                ProgramEvidence.artifacts(event).any { it["content_ref"]?.jsonPrimitive?.contentOrNull == image &&
                    it["mime_type"]?.jsonPrimitive?.contentOrNull?.startsWith("image/") == true } &&
                (event.eventId == raw || image == raw || data?.get("observation_id")?.jsonPrimitive?.contentOrNull == raw)
        }
        return program?.let { it.eventId to null }
    }

    fun requireAll(refs: List<String>): List<Pair<String, ExecutionContextSnapshotPayload?>> =
        refs.map { requireNotNull(resolve(it)) { "EXPERIENCE_SOURCE_NOT_CURRENT_EXTERNAL_EVIDENCE" } }
            .distinctBy { it.first }
}
