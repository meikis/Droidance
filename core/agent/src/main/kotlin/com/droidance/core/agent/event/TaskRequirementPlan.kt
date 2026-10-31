// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.event

import com.droidance.core.agent.event.*

import kotlinx.serialization.json.*

/** Model-derived task scope, retained separately from mutable findings and completion assertions. */
data class TaskRequirementPlan(val eventRef: String, val requirements: List<String>,
                               val constraints: com.droidance.core.agent.TaskOutputConstraints? = null) {
    val requirementIds: List<String> get() = coverageIds(requirements.size, constraints?.output_constraints.orEmpty().size)

    fun render(): String = buildJsonObject {
        put("requirements_event_ref", eventRef)
        put("requirements", JsonArray((requirements + constraints?.output_constraints.orEmpty()).mapIndexed { index, label -> buildJsonObject {
            put("id", requirementIds[index]); put("label", label)
        } }))
        constraints?.let { put("output_contract", Json.encodeToJsonElement(it)) }
    }.toString()

    fun coverageIssues(submitted: List<String>): JsonObject = buildJsonObject {
        put("missing_requirements", JsonArray((requirementIds - submitted.toSet()).map(::JsonPrimitive)))
        put("unlisted_requirements", JsonArray((submitted - requirementIds.toSet()).distinct().map(::JsonPrimitive)))
        put("duplicate_requirements", JsonArray(submitted.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.map(::JsonPrimitive)))
    }

    fun isCoveredBy(submitted: List<String>): Boolean =
        submitted.size == requirementIds.size && submitted.toSet() == requirementIds.toSet()

    companion object {
        const val ARGUMENT = "requirements"

        /** Separate namespaces keep both sets stable when either scope list is extended. */
        fun coverageIds(requirementCount: Int, constraintCount: Int): List<String> =
            (1..requirementCount).map { "r$it" } + (1..constraintCount).map { "c$it" }

        fun parse(value: JsonElement?): List<String>? {
            val items = (value as? JsonArray)?.map { entry ->
                (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf(String::isNotBlank) ?: return null
            } ?: return null
            return items.takeIf { it.isNotEmpty() && it.distinct().size == it.size }
        }

        /** Expose a pending user-update epoch without treating the previous scope as current. */
        fun modelContext(events: List<AgentEventEnvelope>, threadId: String, turnId: String): String {
            current(events, threadId, turnId)?.let { return it.render() }
            val update = events.filter { it.threadId == threadId && it.turnId == turnId &&
                it.eventKind == AgentEventKind.USER_STEER }.maxByOrNull { it.sequence } ?: return ""
            return buildJsonObject {
                put("scope_current", false)
                put("latest_user_update_ref", update.eventId)
                put("latest_user_update_sequence", update.sequence)
            }.toString()
        }

        /** Only a new explicit user update starts another planning epoch. Compaction never does. */
        fun current(events: List<AgentEventEnvelope>, threadId: String, turnId: String): TaskRequirementPlan? =
            project(events, threadId, turnId).current

        /** An existing declaration needs an Agent refresh after user updates; undeclared tasks do not. */
        fun pendingRefresh(events: List<AgentEventEnvelope>, threadId: String, turnId: String): TaskRequirementPlan? =
            project(events, threadId, turnId).let { if (it.current == null) it.previous else null }

        private data class Projection(val current: TaskRequirementPlan?, val previous: TaskRequirementPlan?)

        private fun project(events: List<AgentEventEnvelope>, threadId: String, turnId: String): Projection {
            val scoped = events.filter { it.threadId == threadId && it.turnId == turnId }
                .distinctBy { it.eventId }.sortedBy { it.sequence }
            var userBoundary = 0L
            val calls = scoped.filter { it.eventKind == AgentEventKind.TOOL_CALL }
                .associateBy { it.callId }
            var retained: TaskRequirementPlan? = null
            var previous: TaskRequirementPlan? = null
            scoped.forEach { settled ->
                if (settled.eventKind == AgentEventKind.USER_STEER) {
                    previous = retained ?: previous
                    retained = null
                    userBoundary = settled.sequence
                    return@forEach
                }
                if (settled.eventKind != AgentEventKind.TOOL_FINISHED) return@forEach
                val declaration = calls[settled.callId]?.takeIf {
                    it.sequence > userBoundary && it.sequence < settled.sequence
                }
                    ?: return@forEach
                val call = Json.decodeFromString<ToolCallEventPayload>(declaration.payloadJson)
                val result = Json.decodeFromString<ToolFinishedEventPayload>(settled.payloadJson).result
                if (call.name != "task_memo" || call.source != ToolCallSource.MODEL_REQUESTED || !result.isVerifiedSuccess)
                    return@forEach
                val args = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
                    ?: return@forEach
                val labels = parse(args[ARGUMENT]) ?: return@forEach
                val constraints = com.droidance.core.agent.TaskOutputConstraints.parse(args) ?: return@forEach
                if (!constraints.valid(coverageIds(labels.size, constraints.output_constraints.size))) return@forEach
                if (retained == null || (labels.take(retained!!.requirements.size) == retained!!.requirements &&
                    constraints.extends(checkNotNull(retained!!.constraints)))) {
                    retained = TaskRequirementPlan(settled.eventId, labels, constraints)
                }
            }
            return Projection(retained, previous)
        }
    }
}
