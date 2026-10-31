// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** Rebuilds a bounded working view from durable facts, including after recovery/compaction. */
object TaskContinuationContext {
    data class ModelAuthoredMemo(val eventRef: String, val state: String)

    fun latestMemo(events: List<AgentEventEnvelope>, threadId: String, turnId: String): ModelAuthoredMemo? {
        val scoped = events.filter { it.threadId == threadId && it.turnId == turnId }
            .distinctBy(AgentEventEnvelope::eventId).sortedBy(AgentEventEnvelope::sequence)
        val calls = scoped.filter { it.eventKind == AgentEventKind.TOOL_CALL }.associateBy { it.callId }
        return scoped.asReversed().firstNotNullOfOrNull { settled ->
            if (settled.eventKind != AgentEventKind.TOOL_FINISHED) return@firstNotNullOfOrNull null
            val declaration = calls[settled.callId]?.takeIf { it.sequence < settled.sequence }
                ?: return@firstNotNullOfOrNull null
            val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
            val result = Json.decodeFromString<ToolFinishedEventPayload>(settled.payloadJson).result
            if (call.name != "task_memo" || call.source != ToolCallSource.MODEL_REQUESTED || !result.isVerifiedSuccess)
                return@firstNotNullOfOrNull null
            ModelAuthoredMemo(settled.eventId, result.outputText)
        }
    }

    /**
     * [historyRetained] means the request already carries every settled call and receipt of
     * this Turn. Recent calls and resolved incidents are then omitted instead of repeated.
     */
    fun render(events: List<AgentEventEnvelope>, threadId: String, turnId: String,
               includeModelAuthoredMemo: Boolean = true, historyRetained: Boolean = false): String {
        val scoped = events.filter { it.threadId == threadId && it.turnId == turnId }
            .distinctBy(AgentEventEnvelope::eventId).sortedBy(AgentEventEnvelope::sequence)
        val repairs = ModelRecordRepairs.project(scoped, threadId, turnId)
        val snapshot = ModelObjectRecords.project(scoped, threadId, turnId)
        val objects = snapshot.objects
        val updated = snapshot.updatedSequences
        // Object records are cumulative task state, not a bounded history excerpt.
        // Keep every field and original source visible; model context capacity is
        // handled at the request boundary, never by silently dropping objects here.
        val index = snapshot.page(emptyList(), 0, Int.MAX_VALUE)
        val calls = scoped.filter { it.eventKind == AgentEventKind.TOOL_CALL }.associateBy { it.callId }
        val lastRound = scoped.lastOrNull { it.eventKind == AgentEventKind.MODEL_ROUND_COMPLETED }
        val retention = lastRound?.let { Json.decodeFromString<ModelRoundCompletedEventPayload>(it.payloadJson)
            .finishMetadata["model_record_receipts"]?.let { text -> Json.parseToJsonElement(text).jsonObject } }.orEmpty()
        // Native output may be folded into an artifact reference. Keep the independent
        // persistence acknowledgement visible without replaying that large output or values.
        val retentionSummary = retention.map { (id, value) ->
            val receipt = value.jsonObject
            val declaration = calls.values.firstOrNull { event -> event.roundId == lastRound?.roundId &&
                Json.decodeFromString<ToolCallEventPayload>(event.payloadJson).providerCallId == id }
            val settled = declaration?.let { call -> scoped.lastOrNull { event -> event.callId == call.callId &&
                event.eventKind in setOf(AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED) } }
            buildJsonObject {
                put("provider_call_id", id); put("status", receipt.getValue("status"))
                put("round_ref", receipt.getValue("round_ref")); put("semantics_verified", false)
                receipt["reason"]?.let { put("reason", it) }
                put("saved_record_count", (receipt["updates"] as? JsonArray).orEmpty().size)
                settled?.let { put("receipt_source_ref", it.eventId) }
            }
        }
        val memo = latestMemo(scoped, threadId, turnId)?.takeIf { includeModelAuthoredMemo }?.let {
            buildJsonObject {
                put("event_ref", it.eventRef)
                put("state", it.state)
            }
        }
        val recent = scoped.filter {
            it.eventKind == AgentEventKind.TOOL_FINISHED || it.eventKind == AgentEventKind.TOOL_CALL_REJECTED
        }.mapNotNull { settled ->
            val declaration = calls[settled.callId]?.takeIf { it.sequence < settled.sequence }
                ?: return@mapNotNull null
            val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
            if (call.source != ToolCallSource.MODEL_REQUESTED) return@mapNotNull null
            if (!includeModelAuthoredMemo && call.binding != ToolBinding.DEVICE_BOUND) return@mapNotNull null
            val result = if (settled.eventKind == AgentEventKind.TOOL_FINISHED) {
                Json.decodeFromString<ToolFinishedEventPayload>(settled.payloadJson).result
            } else Json.decodeFromString<ToolCallRejectedEventPayload>(settled.payloadJson).result
            buildJsonObject {
                put("tool", call.name)
                if (call.argumentsJson.length <= 480) put("arguments", runCatching {
                    Json.parseToJsonElement(call.argumentsJson)
                }.getOrElse { JsonPrimitive(call.argumentsJson) })
                else {
                    put("arguments_excerpt", call.argumentsJson.take(480))
                    put("arguments_event_ref", "event:${declaration.sequence}")
                }
                put("execution_status", result.executionStatus.name)
                put("evidence_status", result.evidenceStatus.name)
                result.observationId?.takeIf { result.isVerifiedSuccess }?.let {
                    put("evidence_ref", it)
                }
                put("result_excerpt", result.outputText.take(600))
            }
        }.takeLast(6).takeUnless { historyRetained }.orEmpty()
        val incidents = ExecutionIncidentProjector.page(scoped, threadId, turnId).let { page ->
            if (!historyRetained) page else {
                val open = page.getValue("items").jsonArray.filter { "later_delivery_ref" !in it.jsonObject }
                JsonObject(page + mapOf("items" to JsonArray(open), "total" to JsonPrimitive(open.size)))
            }
        }
        // Exact receipts belong to this Agent's private continuation, not the incident index.
        val eventsById = scoped.associateBy { it.eventId }
        val failedDeviceCalls = if (historyRetained) emptyList() else incidents.getValue("items").jsonArray.mapNotNull { item ->
            val incident = item.jsonObject
            val settled = eventsById[incident.getValue("incident_ref").jsonPrimitive.content]
                ?: return@mapNotNull null
            val declaration = calls[settled.callId] ?: return@mapNotNull null
            val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
            val result = if (settled.eventKind == AgentEventKind.TOOL_FINISHED)
                Json.decodeFromString<ToolFinishedEventPayload>(settled.payloadJson).result
            else Json.decodeFromString<ToolCallRejectedEventPayload>(settled.payloadJson).result
            buildJsonObject {
                put("incident_ref", settled.eventId)
                put("tool", call.name)
                put("arguments", runCatching { Json.parseToJsonElement(call.argumentsJson) }
                    .getOrElse { JsonPrimitive(call.argumentsJson) })
                put("receipt_excerpt", result.outputText.take(800))
            }
        }
        if (recent.isEmpty() && objects.isEmpty() && memo == null && retentionSummary.isEmpty() && repairs.pending.isEmpty() &&
            incidents.getValue("total").jsonPrimitive.int == 0) return ""
        val body = buildJsonObject {
            if (!historyRetained || incidents.getValue("total").jsonPrimitive.int > 0) put("execution_incidents", incidents)
            if (!historyRetained) put("failed_device_calls", JsonArray(failedDeviceCalls))
            if (retentionSummary.isNotEmpty()) put("latest_model_record_receipts", JsonArray(retentionSummary))
            if (repairs.pending.isNotEmpty()) {
                val repairContext = repairs.context()
                put("pending_model_record_repairs", repairContext)
                put("omitted_pending_repair_count", repairs.pending.size - repairContext.size)
            }
            if (objects.isNotEmpty()) {
                put("model_authored_objects", JsonArray(objects.values.toList()))
                put("model_authored_object_index", index)
                put("objects_journal_through_sequence", scoped.maxOf { it.sequence })
            }
            memo?.let { put("model_authored_task_memo", it) }
            if (!historyRetained) put("recent_calls_oldest_first", JsonArray(recent))
        }
        if (body.isEmpty()) return ""
        return "<task_continuation_context>\n$body\n</task_continuation_context>"
    }
}
