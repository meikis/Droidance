// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.model.llm.models.ReasoningEffort
import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Configuration and protocol only; the same Agent decides which work merits more reasoning. */
@Serializable
data class ReasoningControlPolicy(
    val initialEffort: ReasoningEffort,
    val adaptiveEfforts: List<ReasoningEffort>,
    val minimumDecisionsBetweenChanges: Int,
    /** Lowest agent-mode effort for isolated review, recovery and repair rounds. */
    val focusedRoundMinimumEffort: ReasoningEffort? = null
) {
    fun validate() {
        require(adaptiveEfforts.isNotEmpty() && adaptiveEfforts.distinct() == adaptiveEfforts)
        require(ReasoningEffort.DEFAULT !in adaptiveEfforts && initialEffort in adaptiveEfforts)
        require(minimumDecisionsBetweenChanges >= 1)
        require(focusedRoundMinimumEffort == null || focusedRoundMinimumEffort in adaptiveEfforts)
    }
}

object ReasoningEffortProtocol {
    const val ARGUMENT = "next_reasoning_effort"
    data class Result(val calls: List<ToolCall>, val suggestion: ReasoningEffort?, val status: String)

    fun addParameter(tools: List<ToolDefinition>, description: String, policy: ReasoningControlPolicy) =
        tools.map { tool ->
            require(ARGUMENT !in tool.parameters && description.isNotBlank())
            tool.copy(parameters = tool.parameters + (ARGUMENT to ToolParameter(
                type = "string", description = description, required = false,
                enumValues = policy.adaptiveEfforts.mapNotNull { it.wireValue }, deferredValidation = true
            )))
        }

    /** Optional metadata never blocks or changes executable arguments, even when malformed. */
    fun extract(calls: List<ToolCall>): Result {
        val values = mutableListOf<ReasoningEffort>()
        var invalid = false
        val stripped = calls.map { call ->
            val args = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
                ?: return@map call
            if (ARGUMENT !in args) return@map call
            val raw = (args[ARGUMENT] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val effort = ReasoningEffort.entries.firstOrNull { it.wireValue != null && it.wireValue == raw }
            if (effort == null) invalid = true else values += effort
            call.copy(arguments = JsonObject(args - ARGUMENT).toString())
        }
        val status = when {
            invalid -> "invalid"
            values.distinct().size > 1 -> "conflicting"
            values.isEmpty() -> "absent"
            else -> "proposed"
        }
        return Result(stripped, values.firstOrNull().takeIf { status == "proposed" }, status)
    }
}

class ReasoningControl(private val policy: ReasoningControlPolicy) {
    init { policy.validate() }
    private var effort = policy.initialEffort
    private var decisions = 0
    private var lastChange = 0
    var fixedEffort: ReasoningEffort? = null
    val requestedEffort: ReasoningEffort get() = fixedEffort ?: effort

    /** A fixed preference always wins; agent mode raises focused rounds to the policy floor. */
    fun effortFor(focusedRound: Boolean): ReasoningEffort {
        fixedEffort?.let { return it }
        val floor = policy.focusedRoundMinimumEffort?.takeIf { focusedRound } ?: return effort
        return if (floor.ordinal > effort.ordinal) floor else effort
    }

    data class Decision(val effort: ReasoningEffort, val decisions: Int, val lastChange: Int, val status: String) {
        fun metadata() = mapOf(
            "reasoning_next_effort" to effort.name,
            "reasoning_decisions" to decisions.toString(),
            "reasoning_last_change" to lastChange.toString(),
            "reasoning_suggestion_status" to status
        )
    }

    fun propose(suggestion: ReasoningEffortProtocol.Result): Decision {
        val next = decisions + 1
        val candidate = suggestion.suggestion
        val status = when {
            fixedEffort != null -> "fixed_preference"
            candidate == null -> suggestion.status
            candidate !in policy.adaptiveEfforts -> "outside_policy"
            candidate == effort -> "unchanged"
            next - lastChange < policy.minimumDecisionsBetweenChanges -> "held_by_change_interval"
            else -> "accepted"
        }
        return Decision(if (status == "accepted") checkNotNull(candidate) else effort, next,
            if (status == "accepted") next else lastChange, status)
    }

    /** Restored only from committed runtime metadata, independently of context compaction. */
    fun restore(metadata: Map<String, String>) {
        val restored = ReasoningEffort.entries.firstOrNull { it.name == metadata["reasoning_next_effort"] }
        val count = metadata["reasoning_decisions"]?.toIntOrNull()
        val changed = metadata["reasoning_last_change"]?.toIntOrNull()
        if (restored == null || restored !in policy.adaptiveEfforts || count == null || changed == null || changed !in 0..count) return
        effort = checkNotNull(restored)
        decisions = count
        lastChange = changed
    }

    fun context(): String = "<reasoning_control>" + buildJsonObject {
        put("mode", if (fixedEffort == null) "agent" else "fixed")
        put("requested_effort", requestedEffort.wireValue ?: "default")
        put("adaptive_efforts", JsonArray(policy.adaptiveEfforts.map { JsonPrimitive(it.wireValue) }))
        put("decisions_until_change_allowed", (policy.minimumDecisionsBetweenChanges - (decisions + 1 - lastChange)).coerceAtLeast(0))
    } + "</reasoning_control>"
}
