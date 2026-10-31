// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.json.*
import com.droidance.core.agent.harness.RoundDiagnosticCode

/** Historical mechanical failures, never an inferred task failure or an action replay queue. */
object ExecutionIncidentProjector {
    fun project(events: List<AgentEventEnvelope>, threadId: String, turnId: String): List<JsonObject> {
        val scoped = events.filter { it.threadId == threadId && it.turnId == turnId }
            .distinctBy { it.eventId }.sortedBy { it.sequence }
        val calls = scoped.filter { it.eventKind == AgentEventKind.TOOL_CALL }.associateBy { it.callId }
        val contexts = scoped.filter { it.eventKind == AgentEventKind.EXECUTION_ATTEMPT_CREATED }
            .associate { event -> event.executionId to
                Json.decodeFromString<ExecutionAttemptCreatedEventPayload>(event.payloadJson).context }
        val schedulingRejections = setOf(RoundDiagnosticCode.QUERY_RESULT_REQUIRED,
            RoundDiagnosticCode.PRIOR_CALL_REJECTED, "PRIOR_CALL_FAILED", RoundDiagnosticCode.INTERVENTION_EXCLUSIVE,
            RoundDiagnosticCode.COMPLETION_WITH_ACTION, RoundDiagnosticCode.ONE_COMPLETION_PER_ROUND)
        val settledCalls = mutableSetOf<String>()
        val counts = mutableMapOf<List<String?>, Int>()
        val rows = mutableListOf<JsonObject>()
        val archived = scoped.any { it.eventKind == AgentEventKind.TURN_COMPLETED }
        for (event in scoped) {
            if (event.eventKind != AgentEventKind.TOOL_FINISHED &&
                event.eventKind != AgentEventKind.TOOL_CALL_REJECTED) continue
            val declaration = calls[event.callId]?.takeIf { it.sequence < event.sequence } ?: continue
            val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
            if (call.source != ToolCallSource.MODEL_REQUESTED || call.binding != ToolBinding.DEVICE_BOUND) continue
            if (!settledCalls.add(requireNotNull(event.callId))) continue
            val result = if (event.eventKind == AgentEventKind.TOOL_FINISHED)
                Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).result
            else Json.decodeFromString<ToolCallRejectedEventPayload>(event.payloadJson).result
            if (result.diagnosticCode in schedulingRejections) continue
            val scope = listOf(event.attemptId, event.executionId, call.name)
            val delivered = result.executionStatus == ToolExecutionStatus.SUCCEEDED &&
                result.actionEvidence?.delivery == ActionDeliveryEvidence.ACTION_DELIVERED
            if (result.isVerifiedSuccess || delivered) {
                counts.keys.filter { it.take(3) == scope }.toList().forEach { counts.remove(it) }
                rows.indices.filter { index ->
                    val row = rows[index]
                    delivered && row["attempt_ref"]?.jsonPrimitive?.contentOrNull == event.attemptId &&
                        row["execution_ref"]?.jsonPrimitive?.contentOrNull == event.executionId &&
                        row["tool"]?.jsonPrimitive?.contentOrNull == call.name && "later_delivery_ref" !in row
                }.forEach { index ->
                    rows[index] = JsonObject(rows[index] + ("later_delivery_ref" to JsonPrimitive(event.eventId)))
                }
                continue // A later success is not proof that an earlier failed intent was fulfilled.
            }
            val category = when {
                result.executionStatus in setOf(ToolExecutionStatus.INTERRUPTED, ToolExecutionStatus.CANCELED) -> "EFFECT_UNKNOWN"
                result.executionStatus == ToolExecutionStatus.NOT_EXECUTED -> "NOT_EXECUTED"
                result.actionEvidence?.delivery == ActionDeliveryEvidence.UNKNOWN -> "EFFECT_UNKNOWN"
                result.executionStatus == ToolExecutionStatus.FAILED -> "EXECUTION_FAILED"
                else -> "EVIDENCE_UNAVAILABLE"
            }
            val diagnostic = result.diagnosticCode?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }
            val key = scope + listOf(category, diagnostic)
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            rows += buildJsonObject {
                put("incident_ref", event.eventId)
                put("sequence", event.sequence)
                put("call_ref", event.callId)
                put("tool", call.name)
                put("category", category)
                put("diagnostic_code", diagnostic)
                put("execution_status", result.executionStatus.name)
                put("evidence_status", result.evidenceStatus.name)
                put("observation_ref", result.observationId)
                put("attempt_ref", event.attemptId)
                put("execution_ref", event.executionId)
                val context = contexts[event.executionId]
                put("display_id", context?.displayId?.let(::JsonPrimitive) ?: JsonNull)
                put("backend_session_ref", context?.backendSessionId)
                put("execution_space", context?.executionSpace?.name)
                put("consecutive_failures", count)
                put("status", if (archived) "ARCHIVED_UNRESOLVED" else "HISTORICAL_UNRESOLVED")
            }
        }
        return rows
    }

    fun page(events: List<AgentEventEnvelope>, threadId: String, turnId: String, after: Long = 0, limit: Int = 8): JsonObject {
        require(after >= 0 && limit in 1..32)
        val all = project(events, threadId, turnId)
        val remaining = all.filter { it.getValue("sequence").jsonPrimitive.long > after }
        val shown = remaining.take(limit)
        return buildJsonObject {
            put("total", all.size)
            put("items", JsonArray(shown))
            put("remaining", remaining.size - shown.size)
            put("next_after", if (remaining.size > shown.size) shown.last().getValue("sequence") else JsonNull)
        }
    }
}
