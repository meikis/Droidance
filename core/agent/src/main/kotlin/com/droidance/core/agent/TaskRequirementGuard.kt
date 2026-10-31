// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.event.TaskRequirementPlan
import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.json.*

/** Checks an optional declared task scope, never requires one or judges task-specific meaning. */
object TaskRequirementGuard {
    fun check(plan: TaskRequirementPlan?, arguments: String, memo: Boolean, requiresCoverage: Boolean,
              completionRequirements: List<String>?, instruction: String,
              pendingRefreshPlan: TaskRequirementPlan?): ToolExecutionResult? {
        val supplied = runCatching { Json.parseToJsonElement(arguments).jsonObject[TaskRequirementPlan.ARGUMENT] }.getOrNull()
        val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val constraints = args?.let(TaskOutputConstraints::parse)
        val changed = memo && plan != null && supplied != null &&
            (TaskRequirementPlan.parse(supplied)?.take(plan.requirements.size) != plan.requirements ||
                (plan.constraints != null && constraints?.extends(plan.constraints) != true))
        val refresh = requiresCoverage && plan == null && pendingRefreshPlan != null
        val coverage = requiresCoverage && plan != null && (completionRequirements == null ||
            !plan.isCoveredBy(completionRequirements))
        if (!changed && !coverage && !refresh) return null
        return ToolProtocolResult.notExecuted(when {
            changed -> "TASK_REQUIREMENTS_CHANGED"
            refresh -> "TASK_REQUIREMENTS_REFRESH_REQUIRED"
            else -> "TASK_REQUIREMENTS_UNCOVERED"
        }, retryable = true, details = buildJsonObject {
            plan?.let { put("task_requirements", Json.parseToJsonElement(it.render())) }
            if (refresh) put("previous_task_requirements", Json.parseToJsonElement(pendingRefreshPlan!!.render()))
            if (coverage) put("coverage_issues", plan!!.coverageIssues(completionRequirements.orEmpty()))
            put("recovery_condition", instruction)
        })
    }
}
