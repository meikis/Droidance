// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Rejected note payloads are durable repair input, never part of the accepted object index. */
object ModelRecordRepairs {
    data class Pending(val receipt: JsonObject, val firstSequence: Long, val sourceRef: String?)
    data class Snapshot(val pending: Map<String, Pending>, val roundsSinceRejection: Int) {
        fun active(maxRounds: Int) = pending.isNotEmpty() && roundsSinceRejection < maxRounds
        fun context(maxChars: Int = 6000): JsonArray {
            require(maxChars >= 2)
            var remaining = maxChars - 2
            val selected = mutableListOf<JsonElement>()
            for ((ref, item) in pending) {
                val header = buildJsonObject {
                    put("repair_ref", ref)
                    put("round_ref", item.receipt.getValue("round_ref"))
                    item.sourceRef?.let { put("receipt_source_ref", it) }
                    item.receipt["reason"]?.let { put("reason", it) }
                    item.receipt["error_path"]?.let { put("error_path", it) }
                    put("semantics_verified", false)
                }
                val raw = item.receipt["rejected_records"]
                val full = if (raw == null) header else JsonObject(header + mapOf("rejected_records" to raw))
                val entry = if (full.toString().length + 1 <= remaining) full
                    else JsonObject(header + mapOf("read_original_receipt" to JsonPrimitive(true)))
                val size = entry.toString().length + 1
                if (size > remaining) break
                selected += entry; remaining -= size
            }
            return JsonArray(selected)
        }
    }

    fun project(events: List<AgentEventEnvelope>, threadId: String, turnId: String): Snapshot {
        val scoped = events.filter { it.threadId == threadId && it.turnId == turnId }
        val rounds = scoped.filter { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
            .distinctBy(AgentEventEnvelope::eventId).sortedBy(AgentEventEnvelope::sequence)
        val pending = linkedMapOf<String, Pending>()
        rounds.forEach { event ->
            val metadata = Json.decodeFromString<ModelRoundCompletedEventPayload>(event.payloadJson).finishMetadata
            metadata["model_record_receipts"]?.let { Json.parseToJsonElement(it).jsonObject }?.forEach receiptLoop@ { (providerCallId, value) ->
                val receipt = value.jsonObject
                val ref = receipt["repair_ref"]?.jsonPrimitive?.content ?: return@receiptLoop
                if (receipt.getValue("status").jsonPrimitive.content == "rejected") {
                    val previous = pending[ref]
                    val repairInput = if (previous == null) receipt else JsonObject(previous.receipt +
                        receipt.filterKeys { it in setOf("reason", "error_path") })
                    pending[ref] = Pending(repairInput, previous?.firstSequence ?: event.sequence,
                        previous?.sourceRef ?:
                        scoped.firstOrNull { it.eventKind == AgentEventKind.TOOL_CALL && it.roundId == event.roundId &&
                            Json.decodeFromString<ToolCallEventPayload>(it.payloadJson).providerCallId == providerCallId
                        }?.let { call -> scoped.lastOrNull { it.callId == call.callId &&
                            it.eventKind in setOf(AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED) }?.eventId })
                } else receipt["repair_of"]?.jsonPrimitive?.content?.let(pending::remove)
            }
        }
        val first = pending.values.minOfOrNull(Pending::firstSequence)
        return Snapshot(pending, if (first == null) 0 else rounds.count { it.sequence > first })
    }
}

@Serializable
data class ModelRecordRepairPolicy(val maxRepairRounds: Int) {
    fun validate() { require(maxRepairRounds > 0) }
}
