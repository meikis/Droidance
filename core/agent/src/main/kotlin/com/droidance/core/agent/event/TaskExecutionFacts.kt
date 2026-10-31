// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** A disposable projection of the full Turn log, independent of retained context units. */
object TaskExecutionFacts {
    private val json = Json

    @Serializable
    data class Count(
        val tool: String,
        val effect: ToolEffect,
        val source: ToolCallSource,
        val status: ToolExecutionStatus,
        val evidence: ToolEvidenceStatus,
        val calls: Int,
        val inputObservations: Int,
        val latestEvidenceRef: String?
    )

    fun project(events: List<AgentEventEnvelope>, threadId: String, turnId: String): List<Count> {
        data class Key(val name: String, val effect: ToolEffect, val source: ToolCallSource,
            val status: ToolExecutionStatus, val evidence: ToolEvidenceStatus)
        data class Receipt(val observationId: String?)
        val groups = linkedMapOf<Key, MutableList<Receipt>>()
        events.asSequence()
            .filter { it.threadId == threadId && it.turnId == turnId && it.callId != null }
            .distinctBy(AgentEventEnvelope::eventId)
            .sortedBy(AgentEventEnvelope::sequence)
            .groupBy { requireNotNull(it.callId) }
            .forEach { (_, entries) ->
                val declaration = entries.singleOrNull { it.eventKind == AgentEventKind.TOOL_CALL }
                    ?: return@forEach
                val settled = entries.singleOrNull {
                    it.eventKind == AgentEventKind.TOOL_FINISHED || it.eventKind == AgentEventKind.TOOL_CALL_REJECTED
                } ?: return@forEach
                if (settled.sequence <= declaration.sequence) return@forEach
                val call = json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
                val result = when (settled.eventKind) {
                    AgentEventKind.TOOL_FINISHED -> json.decodeFromString<ToolFinishedEventPayload>(settled.payloadJson).result
                    else -> json.decodeFromString<ToolCallRejectedEventPayload>(settled.payloadJson).result
                }
                val observation = result.observationId?.takeIf { id ->
                    result.isVerifiedSuccess && entries.any { entry ->
                        entry.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED &&
                            entry.sequence > settled.sequence &&
                            json.decodeFromString<DetailAttachmentAddedEventPayload>(entry.payloadJson).let {
                                it.role == DetailAttachmentRole.INPUT_OBSERVATION && it.observationId == id
                            }
                    }
                }
                val key = Key(call.name, call.effect, call.source, result.executionStatus, result.evidenceStatus)
                groups.getOrPut(key) { mutableListOf() }.add(Receipt(observation))
            }
        return groups.map { (key, receipts) ->
            val observations = receipts.mapNotNull { it.observationId }.distinct()
            Count(key.name, key.effect, key.source, key.status, key.evidence, receipts.size,
                observations.size, observations.lastOrNull())
        }
    }

    /** Recent factual outcomes survive compaction; no executor-generated business verdict. */
    fun recent(events: List<AgentEventEnvelope>, threadId: String, turnId: String, limit: Int): JsonArray {
        require(limit > 0)
        val scoped = events.filter { it.threadId == threadId && it.turnId == turnId }
            .distinctBy { it.eventId }.sortedBy { it.sequence }
        val calls = scoped.filter { it.eventKind == AgentEventKind.TOOL_CALL && it.callId != null }.associateBy { it.callId }
        val attachments = scoped.filter { it.eventKind == AgentEventKind.DETAIL_ATTACHMENT_ADDED }.groupBy { it.callId }
        return JsonArray(scoped.filter { it.eventKind in setOf(AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED) }
            .filter { it.callId != null }.distinctBy { it.callId }.takeLast(limit).mapNotNull { settled ->
                val declared = calls[settled.callId]?.takeIf { it.sequence < settled.sequence } ?: return@mapNotNull null
                val call = json.decodeFromString<ToolCallEventPayload>(declared.payloadJson)
                val result = if (settled.eventKind == AgentEventKind.TOOL_FINISHED)
                    json.decodeFromString<ToolFinishedEventPayload>(settled.payloadJson).result
                else json.decodeFromString<ToolCallRejectedEventPayload>(settled.payloadJson).result
                buildJsonObject {
                    put("event_ref", settled.eventId)
                    put("tool", call.name)
                    put("effect", call.effect.name)
                    put("execution_status", result.executionStatus.name)
                    put("evidence_status", result.evidenceStatus.name)
                    put("state_changed", result.stateChanged?.let(::JsonPrimitive) ?: JsonNull)
                    result.diagnosticCode?.let { put("diagnostic_code", it) }
                    result.actionEvidence?.let {
                        put("delivery", it.delivery.name)
                        put("transition", it.transition.name)
                    }
                    put("result_observation_ref", result.observationId?.let(::JsonPrimitive) ?: JsonNull)
                    put("observation_sources", JsonArray(attachments[settled.callId].orEmpty()
                        .filter { it.sequence > settled.sequence }.mapNotNull { event ->
                            val attachment = json.decodeFromString<DetailAttachmentAddedEventPayload>(event.payloadJson)
                            attachment.observationId?.takeIf { attachment.role != DetailAttachmentRole.DIAGNOSTIC }?.let { ref ->
                                buildJsonObject {
                                    put("observation_ref", ref)
                                    put("role", attachment.role.name)
                                }
                            }
                        }.distinct()))
                }
            })
    }

    /** Recent receipts are repeated only when the request no longer carries the settled calls. */
    fun render(events: List<AgentEventEnvelope>, threadId: String, turnId: String, recentLimit: Int = 8,
               historyRetained: Boolean = false): String =
        project(events, threadId, turnId).takeIf { it.isNotEmpty() }?.let {
            "<cumulative_execution_facts>\n${json.encodeToString(it)}\n</cumulative_execution_facts>" +
                if (historyRetained) "" else
                "\n<recent_execution_facts>\n${recent(events, threadId, turnId, recentLimit)}\n</recent_execution_facts>"
        }.orEmpty()
}
