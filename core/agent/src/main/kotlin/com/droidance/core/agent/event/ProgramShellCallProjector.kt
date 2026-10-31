// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.json.*

/** Projects actual host receipts; stdout cannot invent child calls or their status. */
object ProgramShellCallProjector {
    fun project(events: List<AgentEventEnvelope>, turnId: String): List<ProgramShellCallReadModel> {
        val calls = linkedMapOf<String, ProgramShellCallReadModel>()
        events.filter { it.turnId == turnId && it.eventKind == AgentEventKind.PROGRAM_EXECUTION }.sortedBy { it.sequence }.forEach { event ->
            val payload = Json.parseToJsonElement(event.payloadJson).jsonObject
            val data = payload["data"] as? JsonObject ?: return@forEach
            val child = data["child_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            when (payload["phase"]?.jsonPrimitive?.contentOrNull) {
                "device_started" -> if (data["tool"]?.jsonPrimitive?.contentOrNull == "shell") {
                    val args = data["arguments"] as? JsonObject ?: return@forEach
                    calls[child] = ProgramShellCallReadModel(child,
                        payload["parent_call_id"]?.jsonPrimitive?.contentOrNull.orEmpty(), event.sequence,
                        args["command"]?.jsonPrimitive?.contentOrNull.orEmpty(), event.occurredAt, null, null)
                }
                "device_finished" -> calls[child]?.let { call ->
                    val result = data["result"] ?: return@forEach
                    val decoded = runCatching { Json.decodeFromJsonElement(ToolExecutionResult.serializer(), result) }
                        .getOrNull() ?: return@forEach
                    calls[child] = call.copy(finishedAt = event.occurredAt, result = decoded)
                }
                "device_unknown" -> calls[child]?.let { call ->
                    calls[child] = call.copy(finishedAt = event.occurredAt,
                        result = com.droidance.core.agent.tools.ToolProtocolResult.interrupted("SHELL_EFFECT_UNKNOWN"))
                }
            }
        }
        return calls.values.toList()
    }
}
