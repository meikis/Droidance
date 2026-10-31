// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.json.*

/** Reads only host-authored manifests from the requested task, never scans arbitrary output for blob IDs. */
object ProgramEvidence {
    fun events(history: List<AgentEventEnvelope>, threadId: String, turnId: String) = history.filter {
        it.threadId == threadId && it.turnId == turnId && it.eventKind == AgentEventKind.PROGRAM_EXECUTION
    }.distinctBy { it.eventId }.sortedBy { it.sequence }

    fun artifacts(event: AgentEventEnvelope): List<JsonObject> =
        (Json.parseToJsonElement(event.payloadJson).jsonObject["artifacts"] as? JsonArray).orEmpty().map { it.jsonObject }

    fun manifest(history: List<AgentEventEnvelope>, threadId: String, turnId: String, ref: String): JsonObject? =
        events(history, threadId, turnId).asSequence().flatMap { artifacts(it).asSequence() }.firstOrNull {
            it["content_ref"]?.jsonPrimitive?.content == ref
        }

    fun page(history: List<AgentEventEnvelope>, threadId: String, turnId: String, after: Long, limit: Int): JsonObject {
        val remaining = events(history, threadId, turnId).filter { it.sequence > after }
        val selected = remaining.take(limit)
        return buildJsonObject {
            put("events", JsonArray(selected.map { event ->
                val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
                buildJsonObject { put("event_ref", event.eventId); put("sequence", event.sequence)
                    put("phase", payload["phase"]!!); put("data_ref", payload["data_ref"]!!)
                    put("artifacts", payload["artifacts"]!!) }
            }))
            put("next_after", selected.lastOrNull()?.sequence ?: after); put("has_more", remaining.size > selected.size)
        }
    }
}
