// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import com.droidance.core.agent.workspace.TaskObservationJournal
import com.droidance.core.agent.tools.WorkspaceRecordProtocol
import kotlinx.serialization.json.*

/** Exact-ID working records reconstructed from committed rounds, never semantic evidence. */
object ModelObjectRecords {
    data class Snapshot(
        val objects: Map<String, JsonObject>,
        val updatedSequences: Map<String, Long>,
        val throughSequence: Long
    ) {
        fun page(recordIds: List<String>, offset: Int, pageChars: Int): JsonObject {
            require(recordIds.distinct().size == recordIds.size) { "MODEL_OBJECT_DUPLICATE_ID" }
            require(recordIds.all { it in objects }) { "MODEL_OBJECT_NOT_FOUND" }
            val index = recordIds.isEmpty()
            val body = if (index) JsonArray(objects.map { (id, record) -> buildJsonObject {
                put("record_id", id)
                put("updated_sequence", updatedSequences.getValue(id))
                put("fields", JsonObject(record.getValue("fields").jsonObject.mapValues { (_, value) ->
                    value.jsonObject.getValue("source_ref")
                }))
            } }) else JsonArray(recordIds.map(objects::getValue))
            return JsonObject(TaskObservationJournal.page(body.toString(), throughSequence, offset, pageChars) + buildJsonObject {
                put("kind", if (index) "model_authored_object_index" else "model_authored_objects")
                put("collection_scope", "turn_model_records")
                put("total_objects", objects.size)
                put("offset", offset)
                put("semantics_verified", false)
                put("read_tool", "workspace")
                put("read_arguments", buildJsonObject {
                    put("op", "objects"); put("through_sequence", throughSequence); put("offset", offset)
                    if (!index) put("record_ids", JsonArray(recordIds.map(::JsonPrimitive)))
                })
            })
        }
    }

    fun project(events: List<AgentEventEnvelope>, threadId: String, turnId: String,
                throughSequence: Long? = null): Snapshot {
        val scoped = events.filter { it.threadId == threadId && it.turnId == turnId &&
            (throughSequence == null || it.sequence <= throughSequence) }
            .distinctBy(AgentEventEnvelope::eventId).sortedBy(AgentEventEnvelope::sequence)
        val objects = linkedMapOf<String, JsonObject>()
        val updated = linkedMapOf<String, Long>()
        val calls = scoped.filter { it.eventKind == AgentEventKind.TOOL_CALL }.associateBy { it.callId }
        fun retain(records: JsonArray, sequence: Long) {
            records.forEach { item ->
                val record = item.jsonObject
                val id = record.getValue("record_id").jsonPrimitive.content
                objects[id] = WorkspaceRecordProtocol.mergeRecord(objects[id], record)
                updated[id] = sequence
            }
        }
        scoped.forEach { event ->
            when (event.eventKind) {
                AgentEventKind.MODEL_ROUND_COMPLETED -> {
                    val metadata = Json.decodeFromString<ModelRoundCompletedEventPayload>(event.payloadJson).finishMetadata
                    val notes = metadata["model_observations"]?.let { Json.parseToJsonElement(it).jsonArray }.orEmpty()
                    notes.forEach { retain(it.jsonObject.getValue("records").jsonArray, event.sequence) }
                }
                AgentEventKind.TOOL_FINISHED -> {
                    val declaration = calls[event.callId]?.takeIf { it.sequence < event.sequence } ?: return@forEach
                    val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
                    if (call.source != ToolCallSource.MODEL_REQUESTED || call.name != "workspace") return@forEach
                    val result = Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).result
                    // The workspace tool has already validated and committed these updates.
                    // A rejected/conflicting file write must never enter the accepted index.
                    if (!result.isVerifiedSuccess) return@forEach
                    val arguments = Json.parseToJsonElement(call.argumentsJson).jsonObject
                    if (arguments["op"]?.jsonPrimitive?.content == "records")
                        retain(arguments.getValue("records").jsonArray, event.sequence)
                }
                else -> Unit
            }
        }
        return Snapshot(objects, updated, throughSequence ?: (scoped.maxOfOrNull { it.sequence } ?: 0L))
    }
}
