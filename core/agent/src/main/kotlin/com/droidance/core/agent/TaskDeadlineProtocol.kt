// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.model.llm.models.ToolCall
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import java.time.OffsetDateTime
import kotlinx.serialization.json.*

/** A host-anchored task budget from the same Operator's ordinary response, before any effects. */
object TaskDeadlineProtocol {
    const val ARGUMENT = "task_deadline"
    data class Bound(val durationSeconds: Long? = null, val deadlineAt: String? = null) {
        fun at(requestAtMs: Long): Long = durationSeconds?.let {
            Math.addExact(requestAtMs, Math.multiplyExact(it, 1_000L))
        } ?: OffsetDateTime.parse(checkNotNull(deadlineAt)).toInstant().toEpochMilli()
    }
    data class Result(val calls: List<ToolCall>, val bound: Bound?)

    fun addParameter(tools: List<ToolDefinition>, description: String): List<ToolDefinition> = tools.map {
        require(ARGUMENT !in it.parameters && description.isNotBlank())
        it.copy(parameters = it.parameters + (ARGUMENT to ToolParameter("object", description, false,
            properties = mapOf(
                "duration_seconds" to ToolParameter("integer", "Positive seconds for the whole task.", false),
                "deadline_at" to ToolParameter("string", "ISO-8601 instant with UTC offset.", false)
            ))))
    }

    fun extract(calls: List<ToolCall>): Result {
        var bound: Bound? = null
        val stripped = calls.map { call ->
            val args = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
                ?: return@map call
            if (ARGUMENT !in args) return@map call
            val value = args[ARGUMENT] as? JsonObject ?: error("INVALID_TASK_DEADLINE")
            require(value.keys.all { it in setOf("duration_seconds", "deadline_at") }) { "INVALID_TASK_DEADLINE" }
            require(value.size == 1) { "MUTUALLY_EXCLUSIVE_TASK_DEADLINE" }
            val candidate = if ("duration_seconds" in value) {
                val seconds = (value["duration_seconds"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                require(seconds != null && seconds > 0L) { "INVALID_TASK_DURATION" }
                Math.multiplyExact(seconds, 1_000L)
                Bound(durationSeconds = seconds)
            } else {
                val text = (value["deadline_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(!text.isNullOrBlank()) { "INVALID_TASK_DEADLINE" }
                OffsetDateTime.parse(text).toInstant().toEpochMilli()
                Bound(deadlineAt = text)
            }
            require(bound == null || bound == candidate) { "CONFLICTING_TASK_DEADLINES" }
            bound = candidate
            call.copy(arguments = JsonObject(args - ARGUMENT).toString())
        }
        return Result(stripped, bound)
    }
}
