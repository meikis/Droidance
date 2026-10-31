// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.TaskRequirementPlan
import com.droidance.core.agent.workspace.TaskWorkspace
import com.droidance.core.agent.workspace.TaskPlanIssue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Model-classified source values; runtime enforces only the declared literal disclosure policy. */
@Serializable
data class ProtectedTaskValue(val requirement_id: String, val source_ref: String, val value: String,
                              val policy: String, val visible_prefix: Int, val visible_suffix: Int) {
    fun valid(ids: List<String>): Boolean = requirement_id in ids && source_ref.isNotBlank() && value.isNotBlank() &&
        policy in setOf("omit", "mask") && visible_prefix >= 0 && visible_suffix >= 0 &&
        visible_prefix.toLong() + visible_suffix < value.codePointCount(0, value.length) &&
        (policy != "omit" || visible_prefix + visible_suffix == 0)

    fun masked(): String {
        val points = value.codePoints().toArray()
        return String(points, 0, visible_prefix) + "*".repeat(points.size - visible_prefix - visible_suffix) +
            String(points, points.size - visible_suffix, visible_suffix)
    }
}

@Serializable
data class RequestedTaskFile(val path: String, val requirement_id: String)

@Serializable
data class TaskOutputConstraints(val output_constraints: List<String> = emptyList(),
                                 val requested_deliverables: List<RequestedTaskFile> = emptyList(),
                                 val protected_values: List<ProtectedTaskValue> = emptyList()) {
    fun valid(ids: List<String>): Boolean = validationIssues(ids).isEmpty()

    /** Field-level repair feedback never includes the restricted source value. */
    fun validationIssues(ids: List<String>): List<TaskPlanIssue> = buildList {
        fun check(valid: Boolean, field: String, reason: String, recovery: String) {
            if (!valid) add(TaskPlanIssue(field, reason, recovery))
        }
        output_constraints.forEachIndexed { i, value ->
            check(value.isNotBlank(), "output_constraints[$i]", "blank", "Retain a nonblank original constraint.")
            check(value !in output_constraints.take(i), "output_constraints[$i]", "duplicate", "Remove only the duplicate constraint.")
        }
        requested_deliverables.forEachIndexed { i, file ->
            check(file.requirement_id in ids, "requested_deliverables[$i].requirement_id", "unknown_requirement", "Use a retained requirement ID.")
            check(!file.path.startsWith(".completion/") && runCatching { TaskWorkspace.validatePath(file.path) }.isSuccess,
                "requested_deliverables[$i].path", "invalid_task_path", "Use a task-relative deliverable path outside .completion/.")
            check(requested_deliverables.take(i).none { it.path == file.path }, "requested_deliverables[$i].path", "duplicate", "Keep each requested file once.")
        }
        protected_values.forEachIndexed { i, value ->
            val field = "protected_values[$i]"
            check(value.requirement_id in ids, "$field.requirement_id", "unknown_requirement", "Use a retained disclosure requirement ID.")
            check(value.source_ref.isNotBlank(), "$field.source_ref", "blank", "Use the exact original source reference.")
            check(value.value.isNotBlank(), "$field.value", "blank", "Register the exact observed restricted value internally.")
            check(value.policy in setOf("omit", "mask"), "$field.policy", "unknown_policy", "Use omit or mask.")
            check(value.visible_prefix >= 0, "$field.visible_prefix", "negative", "Use a nonnegative Unicode character count.")
            check(value.visible_suffix >= 0, "$field.visible_suffix", "negative", "Use a nonnegative Unicode character count.")
            check(value.visible_prefix.toLong() + value.visible_suffix < value.value.codePointCount(0, value.value.length),
                field, "mask_reveals_entire_value", "Hide at least one Unicode character; use omit with both counts zero when the value need not be published.")
            check(value.policy != "omit" || value.visible_prefix.toLong() + value.visible_suffix == 0L,
                field, "omit_has_visible_characters", "For omit, set both visible counts to zero.")
            check(protected_values.take(i).none { it.value == value.value }, "$field.value", "duplicate", "Keep each restricted original value once.")
        }
    }

    fun validatePublicText(text: String) {
        protected_values.forEachIndexed { index, restricted ->
            require(!text.contains(restricted.value)) { "protected_value_disclosed:$index" }
        }
    }

    /** Scope additions may tighten the contract but cannot erase or replace an existing restriction. */
    fun extends(previous: TaskOutputConstraints): Boolean =
        output_constraints.take(previous.output_constraints.size) == previous.output_constraints &&
        requested_deliverables.take(previous.requested_deliverables.size) == previous.requested_deliverables &&
        protected_values.take(previous.protected_values.size) == previous.protected_values

    fun validateCompletion(body: JsonObject, files: List<JsonObject>, plan: TaskRequirementPlan) {
        val checks = (body["requirement_checks"] as? JsonArray).orEmpty().map { it.jsonObject }
        if (checks.isNotEmpty()) {
            require(plan.isCoveredBy(checks.map { it.getValue("requirement_id").jsonPrimitive.content })) { "task_requirements_uncovered" }
        }
        val supported = checks.filter { it.getValue("status").jsonPrimitive.content == "supported" }
            .map { it.getValue("requirement_id").jsonPrimitive.content }.toSet()
        val paths = files.map { it.getValue("path").jsonPrimitive.content }
        val complete = body["status"]?.jsonPrimitive?.content !in setOf("need_more_info", "failed")
        require(requested_deliverables.filter { complete || it.requirement_id in supported }
            .all { it.path in paths }) { "requested_deliverables_not_attached" }
        val entries = (body["results"] ?: body["items"])!!.jsonArray.map { it.jsonObject }
        val publicText = entries.joinToString("\n") { it.getValue("text").jsonPrimitive.content +
            "\n" + (it["deeplink"]?.jsonPrimitive?.content ?: "") } + "\n" +
            (body["unresolved_requirements"] as? JsonArray).orEmpty().joinToString("\n") { it.jsonPrimitive.content }
        val texts = listOf(publicText) + files.map { it.getValue("text").jsonPrimitive.content }
        // Masking permits omission: a privacy requirement does not require disclosing an identifier.
        texts.forEach(::validatePublicText)
    }

    companion object {
        fun parse(args: JsonObject): TaskOutputConstraints? = runCatching {
            Json.decodeFromJsonElement<TaskOutputConstraints>(JsonObject(args.filterKeys {
                it in setOf("output_constraints", "requested_deliverables", "protected_values")
            }))
        }.getOrNull()
    }
}
