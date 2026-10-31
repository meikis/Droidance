// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.TurnEventScope
import com.droidance.core.agent.workspace.*
import com.droidance.core.model.llm.models.ToolDefinition
import com.droidance.core.model.llm.models.ToolParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The settled call owns plan state; the workspace owns detailed cumulative findings. */
class TaskMemoTool(private val workspace: TaskWorkspace, private val scope: () -> TurnEventScope,
                   private val rules: (String) -> String) : Tool {
    override val name = "task_memo"
    override val tier = ToolTier.CORE
    override val category = ToolCategory.META
    override val definition get() = definition(
        rules("output_contract") + "\n\n" + rules("execution_plan")
    )
    override suspend fun execute(arguments: String): com.droidance.core.agent.event.ToolExecutionResult {
        runCatching { com.droidance.core.agent.TaskOutputConstraints.parse(Json.parseToJsonElement(arguments).jsonObject) }.getOrNull()
            ?: return ToolProtocolResult.invalidArgument("output_constraints", "output_contract_fields_required")
        val parsed = TaskExecutionPlan.parse(arguments)
        parsed.rejection()?.let { return it }
        val plan = checkNotNull(parsed.plan)
        return try {
            val owner = scope()
            // Bootstrap is part of the plan call, never another optional model action. Existing
            // findings are preserved when steps change or a process resumes.
            if (workspace.list(owner).none { it.path == plan.record_path }) {
                workspace.write(owner, plan.record_path, plan.initialMarkdown(), false, null,
                    checkNotNull(currentCoroutineContext()[ToolInvocation]).callId,
                    Json.decodeFromString<WorkspacePolicy>(rules("policy")), activate = true)
            } else {
                workspace.focus(owner, plan.record_path, checkNotNull(currentCoroutineContext()[ToolInvocation]).callId)
            }
            ToolProtocolResult.success(plan.state)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { ToolProtocolResult.failed("TASK_PLAN_STORAGE_ERROR", false, error) }
    }
    companion object {
        fun definition(description: String) = ToolDefinition("task_memo", description, linkedMapOf(
            "record_path" to ToolParameter("string", "Optional task-relative working file path; defaults to task_memo.md. Keep it once chosen.", required = false),
            "state" to ToolParameter("string", "Short current-step assessment and pointer; details belong in the working file."),
            "requirements" to ToolParameter("array", "Original requirements with IDs r1, r2, ...; preserve their order and full conditions.",
                items = ToolParameter("string", "Requirement")),
            "output_constraints" to ToolParameter("array", "Original output, disclosure, order and prohibited-action constraints with separate IDs c1, c2, ... . Each needs its own requirement_checks row, even when also mentioned in a requirement. Omit if none; preserve them independently of completion text.", required = false, items = ToolParameter("string", "Constraint")),
            "requested_deliverables" to ToolParameter("array", "Only files the user explicitly asked to receive. Choose a path once and bind it to its requirement ID; omit otherwise.", required = false, items = ToolParameter("object", "", properties = linkedMapOf(
                "path" to ToolParameter("string", "Task-relative path"), "requirement_id" to ToolParameter("string", "r1, r2, ...")
            ), additionalProperties = false)),
            "protected_values" to ToolParameter("array", "Append restricted source values after observing them, before completion; omit if none. Never drop or weaken registered restrictions.", required = false, items = ToolParameter("object", "", properties = linkedMapOf(
                "requirement_id" to ToolParameter("string", "Retained disclosure requirement or constraint ID (r1 or c1, etc.)"),
                "source_ref" to ToolParameter("string", "Original source reference"),
                "value" to ToolParameter("string", "Exact original value kept internal"),
                "policy" to ToolParameter("string", "Disclosure policy", enumValues = listOf("omit", "mask")),
                "visible_prefix" to ToolParameter("integer", "Visible Unicode code points at start; zero for omit"),
                "visible_suffix" to ToolParameter("integer", "Visible Unicode code points at end; zero for omit")
            ), additionalProperties = false)),
            "steps" to ToolParameter("array", "Optional method steps with stable identities and observable acceptance criteria. Omit to retain original scope without a method plan or step evidence.", required = false,
                items = ToolParameter("object", "Step", properties = linkedMapOf(
                    "id" to ToolParameter("string", "Stable step ID"),
                    "objective" to ToolParameter("string", "Step objective"),
                    "acceptance" to ToolParameter("string", "What source evidence would establish completion"),
                    "status" to ToolParameter("string", "Method status; never device authorization or proof of task completion", enumValues = TaskStep.statuses),
                    "evidence_refs" to ToolParameter("array", "Original source references; required for complete steps; omit for uncompleted steps without sources", required = false, requiredWhen = mapOf("status" to listOf("complete")), items = ToolParameter("string", "Reference"))
                ), additionalProperties = false)),
            "review" to ToolParameter("object", "Optional source-backed review of a previously active step. Omit when initializing, activating, or editing steps without a review. Never required to unlock a device action.", required = false,
                properties = linkedMapOf(
                    "outcome" to ToolParameter("string", "Source-backed assessment", enumValues = listOf("progress", "replan")),
                    "finding" to ToolParameter("string", "Concrete new progress, or observed mismatch and failed method", minLength = 1),
                    "next_method" to ToolParameter("string", "Next method: continue supported progress, or a materially different method when replanning", minLength = 1),
                    "evidence_refs" to ToolParameter("array", "Nonempty original sources inspected for this review; omit the entire review if none exist", minItems = 1, items = ToolParameter("string", "Reference", minLength = 1))
                ), additionalProperties = false)
        ))
    }
}
