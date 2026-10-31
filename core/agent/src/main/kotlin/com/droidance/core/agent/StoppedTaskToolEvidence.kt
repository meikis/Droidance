// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import com.droidance.core.agent.event.*
import kotlinx.serialization.json.*

/** Retain chronological results, including recovery successes, without deciding task semantics. */
object StoppedTaskToolEvidence {
    /** Preserve earlier raw receipts independently of later repair failures; never infer goal success. */
    fun retainedReceipts(events: List<AgentEventEnvelope>, maxChars: Int): JsonArray {
        require(maxChars >= 2)
        val calls = events.filter { it.eventKind == AgentEventKind.TOOL_CALL }.associate {
            it.callId to Json.decodeFromString<ToolCallEventPayload>(it.payloadJson)
        }
        val selected = mutableSetOf<String>()
        val rows = mutableListOf<JsonElement>()
        for (event in events.sortedByDescending { it.sequence }) {
            if (event.eventKind != AgentEventKind.TOOL_FINISHED) continue
            val result = Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).result
            val call = calls[event.callId] ?: continue
            if (result.executionStatus != ToolExecutionStatus.SUCCEEDED ||
                !selected.add("${call.name}:${call.argumentsJson}")) continue
            val structured = result.data ?: runCatching { Json.parseToJsonElement(result.outputText) }.getOrNull()
                ?: runCatching { Json.parseToJsonElement(result.outputText.substringAfter('\n', result.outputText)) }.getOrNull()
            val content = (structured as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key != "command" }) }
                ?.toString() ?: result.outputText
            val rowBudget = minOf(2048, (maxChars - JsonArray(rows).toString().length - 400).coerceAtLeast(0))
            val row = buildJsonObject {
                put("sequence", event.sequence); put("call_id", event.callId); put("tool", call.name)
                put("execution_status", result.executionStatus.name); put("evidence_status", result.evidenceStatus.name)
                put("source_ref", result.sourceRef); put("data_ref", result.dataRef)
                put("receipt_excerpt", content.take(rowBudget)); put("truncated", content.length > rowBudget)
                put("execution_success_is_not_goal_success", true)
            }
            if (JsonArray(rows + row).toString().length <= maxChars) rows += row
        }
        return JsonArray(rows.reversed())
    }

    fun project(events: List<AgentEventEnvelope>, maxChars: Int): JsonArray {
        require(maxChars >= 2)
        val calls = mutableMapOf<String, ToolCallEventPayload>()
        val results = ArrayDeque<JsonObject>()
        for (event in events.sortedBy { it.sequence }) {
            if (event.eventKind == AgentEventKind.TOOL_CALL) {
                event.callId?.let { calls[it] = Json.decodeFromString<ToolCallEventPayload>(event.payloadJson) }
                continue
            }
            val result = when (event.eventKind) {
                AgentEventKind.TOOL_FINISHED -> Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).result
                AgentEventKind.TOOL_CALL_REJECTED -> Json.decodeFromString<ToolCallRejectedEventPayload>(event.payloadJson).result
                else -> continue
            }
            results.addLast(buildJsonObject {
                put("sequence", event.sequence)
                put("call_id", event.callId)
                put("tool", calls[event.callId]?.name)
                put("execution_status", result.executionStatus.name)
                put("diagnostic_code", result.diagnosticCode)
                put("receipt_excerpt", result.outputText.take(640))
            })
            while (results.isNotEmpty() && JsonArray(results.toList()).toString().length > maxChars) results.removeFirst()
        }
        return JsonArray(results.toList())
    }
}
