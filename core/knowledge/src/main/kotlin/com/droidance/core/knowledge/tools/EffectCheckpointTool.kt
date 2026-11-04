// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0
package com.droidance.core.knowledge.tools

import com.droidance.core.agent.event.*
import com.droidance.core.agent.tools.*
import com.droidance.core.knowledge.memory.ExperienceEvidenceSources
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Records this Agent's evidence-backed reconciliation; it never performs a device action. */
class EffectCheckpointTool(private val events: AgentEventStore, private val scope: () -> TurnEventScope,
                           private val description: String) : Tool {
    override val name = EffectCheckpointLedger.TOOL_NAME
    override val tier = ToolTier.CORE
    override val definition = ToolDefinition(name, description, linkedMapOf(
        "op" to ToolParameter("string", "Inspect the original effect or record its current assessment.", true, listOf("inspect", "resolve")),
        "key" to ToolParameter("string", "Original effect_key in this task, at most 128 characters.", true, minLength = 1),
        "decision" to ToolParameter("string", "Assessment of the original effect; only not_applied permits retry.", false,
            listOf("completed", "not_applied", "unknown"), requiredWhen = mapOf("op" to listOf("resolve"))),
        "summary" to ToolParameter("string", "What was checked against the original receipt and current external result.", false,
            minLength = 1, requiredWhen = mapOf("op" to listOf("resolve"))),
        "evidence_refs" to ToolParameter("array", "Current-task external evidence obtained after the original call ended; required for a resolved decision.", false,
            items = ToolParameter("string", "Original evidence reference."))
    ))

    override suspend fun execute(arguments: String): ToolExecutionResult {
      return try {
        val input = Json.parseToJsonElement(arguments).jsonObject
        val owner = scope()
        val history = events.eventsForTurn(owner.turnId)
        val key = input.getValue("key").jsonPrimitive.content
        val entry = EffectCheckpointLedger.history(history, owner)[key]
            ?: return ToolProtocolResult.notExecuted("EFFECT_CHECKPOINT_NOT_FOUND", false)
        when (input.getValue("op").jsonPrimitive.content) {
            "inspect" -> ToolProtocolResult.success(entry.facts().toString())
            "resolve" -> {
                val finished = entry.finishedSequence
                    ?: return ToolProtocolResult.notExecuted("EFFECT_CHECKPOINT_IN_FLIGHT", false)
                val decision = input.getValue("decision").jsonPrimitive.content
                require(decision in setOf("completed", "not_applied", "unknown")) { "EFFECT_DECISION_INVALID" }
                require(entry.state != "completed" || decision == "completed") { "EFFECT_ALREADY_COMPLETED" }
                val summary = input.getValue("summary").jsonPrimitive.content
                require(summary.isNotBlank() && summary.length <= 2000) { "EFFECT_SUMMARY_REQUIRED" }
                val refs = (input["evidence_refs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                require(refs.size <= 32 && (decision == "unknown" || refs.isNotEmpty())) { "EFFECT_EVIDENCE_REQUIRED" }
                val evidence = ExperienceEvidenceSources(history, owner.threadId, owner.turnId, Long.MAX_VALUE, finished)
                require(refs.all { evidence.resolve(it) != null }) { "EFFECT_EVIDENCE_NOT_CURRENT_EXTERNAL" }
                val record = JsonObject(entry.copy(state = decision).facts() + buildJsonObject {
                    put("summary", summary); put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
                    put("assessed_by", "task_agent")
                })
                ToolProtocolResult.success("Effect assessment recorded. It performs no device action and does not certify the user's outcome.")
                    .copy(data = buildJsonObject { put("effect_checkpoint", record) })
            }
            else -> ToolProtocolResult.invalidArgument("op", "unsupported_value")
        }
    } catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { ToolProtocolResult.failed("EFFECT_RECONCILIATION_INVALID", false, error) }
    }
}
