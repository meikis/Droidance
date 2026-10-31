// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.event.*
import kotlinx.serialization.json.*

/** Read-only task record projected from committed events, independent of the active work file. */
object TaskObservationJournal {
    private val json = Json { ignoreUnknownKeys = true }

    fun text(history: List<AgentEventEnvelope>, scope: TurnEventScope, through: Long): String =
        history.asSequence().filter {
            it.threadId == scope.threadId && it.turnId == scope.turnId && it.sequence <= through
        }.sortedBy { it.sequence }.mapNotNull { event ->
            when (event.eventKind) {
                AgentEventKind.MODEL_ROUND_COMPLETED -> {
                    val round = json.decodeFromString<ModelRoundCompletedEventPayload>(event.payloadJson)
                    buildJsonObject {
                        put("sequence", event.sequence)
                        put("round_id", event.roundId)
                        put("kind", "model_authored_unverified")
                        put("public_blocks", JsonArray(round.publicBlocks.map(::JsonPrimitive)))
                        put("observations", Json.parseToJsonElement(round.finishMetadata["model_observations"] ?: "[]"))
                        put("observation_id", round.finishMetadata["current_observation_id"].orEmpty())
                        put("round_detail_ref", round.roundDetailRef)
                    }.toString()
                }
                AgentEventKind.TOOL_FINISHED -> {
                    val receipt = json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson)
                    buildJsonObject {
                        put("sequence", event.sequence)
                        put("kind", "source_receipt_index")
                        put("round_id", event.roundId)
                        put("call_id", event.callId)
                        put("observation_id", receipt.result.observationId)
                        put("round_detail_ref", receipt.roundDetailRef)
                    }.toString()
                }
                else -> null
            }
        }.joinToString("\n")

    fun context(history: List<AgentEventEnvelope>, scope: TurnEventScope, maxChars: Int): String {
        val through = history.maxOfOrNull { it.sequence } ?: 0L
        val full = text(history, scope, through)
        if (full.isEmpty()) return ""
        val prefixEnd = boundary(full, minOf(full.length, maxChars / 2))
        val suffixStart = boundary(full, maxOf(prefixEnd, full.length - maxChars / 2))
        return "<task_observation_journal>" + buildJsonObject {
            put("through_sequence", through)
            put("total_chars", full.length)
            put("head", full.substring(0, prefixEnd))
            put("tail_offset", suffixStart)
            put("tail", full.substring(suffixStart))
            put("has_omitted_middle", suffixStart > prefixEnd)
            put("next_offset", prefixEnd)
        } + "</task_observation_journal>"
    }

    fun page(text: String, through: Long, offset: Int, limit: Int): JsonObject {
        require(offset in 0..text.length) { "INVALID_CURSOR" }
        require(offset == boundary(text, offset)) { "INVALID_CURSOR" }
        val end = boundary(text, minOf(text.length.toLong(), offset.toLong() + limit).toInt())
        return buildJsonObject {
            put("through_sequence", through)
            put("text", text.substring(offset, end))
            put("next_offset", end)
            put("has_more", end < text.length)
            put("total_chars", text.length)
        }
    }

    private fun boundary(text: String, offset: Int): Int =
        if (offset in 1 until text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()) offset - 1 else offset
}
