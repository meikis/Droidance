// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.agent.tools

import com.droidance.core.agent.event.*
import kotlinx.serialization.json.*

/** Rebuilt from settled tool facts; the Agent declares effect identity and assesses meaning. */
object EffectCheckpointLedger {
    const val TOOL_NAME = "effect_checkpoint"
    val deviceTools = setOf(DeviceToolNames.ANDROID_ACT, DeviceToolNames.SCREEN_ACT)
    val keyParameter = com.droidance.core.model.llm.models.ToolParameter("string",
        "Stable identity (up to 128 characters) for one persistent effect in this task. Reuse across native/GUI recovery. Uncertain effects require reconciliation; completed effects stay blocked.", false,
        minLength = 1)

    data class Entry(val key: String, val callId: String, val startedSequence: Long,
                     val finishedSequence: Long?, val state: String) {
        fun facts(): JsonObject = buildJsonObject {
            put("key", key); put("original_call_id", callId); put("state", state)
            put("started_sequence", startedSequence); finishedSequence?.let { put("finished_sequence", it) }
        }
    }

    fun key(input: JsonObject): String? {
        val value = input["effect_key"] ?: return null
        require(value is JsonPrimitive && value.isString && value.content.isNotBlank() &&
            value.content.length <= 128 && value.content.none { it.isISOControl() }) { "EFFECT_KEY_INVALID" }
        return value.content
    }

    fun history(events: List<AgentEventEnvelope>, scope: TurnEventScope,
                beforeSequence: Long = Long.MAX_VALUE): Map<String, Entry> {
        val prior = events.filter { it.threadId == scope.threadId && it.turnId == scope.turnId && it.sequence < beforeSequence }
        val calls = prior.filter { it.eventKind == AgentEventKind.TOOL_CALL }.associate { event ->
            event.callId to Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
        }
        val finishes = prior.filter { it.eventKind == AgentEventKind.TOOL_FINISHED }.associateBy { it.callId }
        val entries = linkedMapOf<String, Entry>()
        for (event in prior.sortedBy { it.sequence }) {
            val call = calls[event.callId] ?: continue
            if (event.eventKind == AgentEventKind.TOOL_STARTED && call.name in deviceTools) {
                val input = Json.parseToJsonElement(call.argumentsJson).jsonObject
                val key = runCatching { key(input) }.getOrNull() ?: continue
                val finished = finishes[event.callId]
                val result = finished?.let { Json.decodeFromString<ToolFinishedEventPayload>(it.payloadJson).result }
                if (result?.executionStatus == ToolExecutionStatus.NOT_EXECUTED) continue
                entries[key] = Entry(key, checkNotNull(event.callId), event.sequence, finished?.sequence, "pending")
            } else if (event.eventKind == AgentEventKind.TOOL_FINISHED && call.name == TOOL_NAME) {
                val result = Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).result
                if (!result.isVerifiedSuccess) continue
                val resolved = (result.data as? JsonObject)?.get("effect_checkpoint") as? JsonObject ?: continue
                val key = resolved["key"]?.jsonPrimitive?.contentOrNull ?: continue
                val original = entries[key] ?: continue
                val decision = resolved["state"]?.jsonPrimitive?.contentOrNull ?: continue
                if (resolved["original_call_id"]?.jsonPrimitive?.contentOrNull == original.callId &&
                    original.finishedSequence != null && decision in setOf("completed", "not_applied", "unknown") &&
                    (original.state != "completed" || decision == "completed")) {
                    entries[key] = original.copy(state = decision)
                }
            }
        }
        return entries
    }

    fun admission(toolName: String, arguments: String, events: List<AgentEventEnvelope>,
                  scope: TurnEventScope, callId: String): ToolExecutionResult? {
        if (toolName !in deviceTools) return null
        val input = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull() ?: return null
        val key = try { key(input) } catch (_: IllegalArgumentException) {
            return ToolProtocolResult.invalidArgument("effect_key", "expected_nonblank_identity_up_to_128_characters")
        } ?: return null
        val started = events.firstOrNull { it.threadId == scope.threadId && it.turnId == scope.turnId &&
            it.callId == callId && it.eventKind == AgentEventKind.TOOL_STARTED }
            ?: return ToolProtocolResult.notExecuted("EFFECT_CHECKPOINT_CALL_UNRECORDED", false)
        // The earlier started invocation wins even if two calls are admitted concurrently.
        val original = history(events, scope, started.sequence)[key] ?: return null
        if (original.state == "not_applied") return null
        return ToolProtocolResult.notExecuted(
            if (original.state == "completed") "EFFECT_ALREADY_COMPLETED" else "EFFECT_RECONCILIATION_REQUIRED", false,
            buildJsonObject {
                put("effect_checkpoint", original.facts())
                put("recovery_condition", "Inspect the original receipt and current external result. Use effect_checkpoint to reconcile this same key; do not create a new key to retry an uncertain effect.")
            })
    }
}
