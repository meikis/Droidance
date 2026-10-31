// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.workspace

import com.droidance.core.agent.event.*
import com.droidance.core.agent.ToolRecovery
import com.droidance.core.agent.tools.UnifiedDeviceToolProtocol
import com.droidance.core.agent.tools.ToolProtocolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class TaskStep(val id: String, val objective: String, val acceptance: String,
                    val status: String, val evidence_refs: List<String> = emptyList()) {
    companion object {
        val statuses = listOf("pending", "active", "complete", "blocked", "superseded")
    }
}

@Serializable
data class TaskPlanIssue(val field: String, val reason: String, val recovery: String)

data class ParsedTaskPlan(val plan: TaskExecutionPlan?, val issues: List<TaskPlanIssue>) {
    fun rejection(): ToolExecutionResult? = issues.takeIf { it.isNotEmpty() }?.let {
        ToolProtocolResult.notExecuted("TASK_PLAN_INVALID", retryable = true, details = buildJsonObject {
            put("invalid_fields", Json.encodeToJsonElement(it))
            put("plan_committed", false)
        })
    }
}
@Serializable
data class TaskMethodReview(val outcome: String, val finding: String, val next_method: String, val evidence_refs: List<String>)
@Serializable
data class TaskExecutionPlan(val record_path: String = DEFAULT_RECORD_PATH, val state: String, val requirements: List<String>,
                             val steps: List<TaskStep> = emptyList(), val review: TaskMethodReview? = null,
                             val output_constraints: List<String> = emptyList(),
                             val requested_deliverables: List<com.droidance.core.agent.RequestedTaskFile> = emptyList(),
                             val protected_values: List<com.droidance.core.agent.ProtectedTaskValue> = emptyList()) {
    val active: TaskStep? get() = steps.singleOrNull { it.status == "active" }
    fun validationIssues(): List<TaskPlanIssue> = buildList {
        fun checkField(valid: Boolean, field: String, reason: String, recovery: String) {
            if (!valid) add(TaskPlanIssue(field, reason, recovery))
        }
        checkField(runCatching { TaskWorkspace.validatePath(record_path) }.isSuccess,
            "record_path", "invalid_task_relative_path", "Use a relative file path inside this task workspace.")
        checkField(state.isNotBlank(), "state", "blank", "Describe the current working state briefly.")
        checkField(requirements.isNotEmpty() && requirements.all(String::isNotBlank) && requirements.distinct().size == requirements.size,
            "requirements", "empty_blank_or_duplicate_labels", "Preserve distinct, nonblank original requirement labels.")
        addAll(com.droidance.core.agent.TaskOutputConstraints(output_constraints, requested_deliverables, protected_values)
            .validationIssues(TaskRequirementPlan.coverageIds(requirements.size, output_constraints.size)))
        checkField(steps.size <= 32, "steps", "invalid_step_count", "Supply at most 32 optional method steps; [] retains scope without a method plan.")
        checkField(steps.map { it.id }.distinct().size == steps.size, "steps.id", "duplicate_ids", "Use a distinct ID for each method step.")
        checkField(steps.count { it.status == "active" } <= 1, "steps.status", "multiple_active_steps", "Keep at most one active step; a pending draft is allowed.")
        steps.forEachIndexed { index, step ->
            listOf("id" to step.id, "objective" to step.objective, "acceptance" to step.acceptance).forEach { (field, value) ->
                checkField(value.isNotBlank(), "steps[$index].$field", "blank", "Supply a nonblank $field.")
            }
            checkField(step.status in TaskStep.statuses, "steps[$index].status", "unknown_status", "Use one of ${TaskStep.statuses.joinToString()}.")
            checkField(step.evidence_refs.all(String::isNotBlank), "steps[$index].evidence_refs", "blank_reference", "Remove blank references; retain only original sources.")
            checkField(step.status != "complete" || step.evidence_refs.isNotEmpty(), "steps[$index].evidence_refs",
                "completed_step_requires_sources", "Provide original sources or retain an uncompleted status.")
        }
        review?.let {
            checkField(it.outcome in setOf("progress", "replan"), "review.outcome", "unknown_outcome", "Use progress or replan.")
            checkField(it.finding.isNotBlank(), "review.finding", "blank", "Describe what the inspected sources establish.")
            checkField(it.next_method.isNotBlank(), "review.next_method", "blank", "Describe the next method.")
            checkField(it.evidence_refs.isNotEmpty() && it.evidence_refs.all(String::isNotBlank), "review.evidence_refs",
                "review_requires_inspected_sources", "Omit review when initializing or activating a step. Include it only after inspecting original sources; never invent references.")
        }
    }
    fun initialMarkdown(): String = buildString {
        appendLine("# Task working record")
        appendLine("\n## Requirements")
        requirements.forEach { appendLine("- $it") }
        appendLine("\n## Execution steps")
        steps.forEach { appendLine("- ${it.id}: ${it.objective}\n  Acceptance: ${it.acceptance}\n  Status: ${it.status}") }
        appendLine("\n## Progress\n$state")
    }
    companion object {
        /** Working file used when the Agent does not name one; a memo needs no filename decision. */
        const val DEFAULT_RECORD_PATH = "task_memo.md"

        fun parse(arguments: String): ParsedTaskPlan {
            // Name unknown fields directly: the decoder's own message carries library advice that
            // is not an argument the Agent can change.
            val allowed = (0 until serializer().descriptor.elementsCount).map { serializer().descriptor.getElementName(it) }
            val unknown = runCatching { Json.parseToJsonElement(arguments).jsonObject.keys - allowed.toSet() }.getOrNull().orEmpty()
            if (unknown.isNotEmpty()) return ParsedTaskPlan(null, unknown.sorted().map { field ->
                TaskPlanIssue(field, "unknown_field", "Remove $field. Allowed fields: ${allowed.joinToString()}.")
            })
            val plan = try {
                Json.decodeFromString<TaskExecutionPlan>(arguments)
            } catch (error: kotlinx.serialization.SerializationException) {
                return ParsedTaskPlan(null, listOf(TaskPlanIssue("$",
                    error.message.orEmpty().lineSequence().first().take(300),
                    "Follow the task_memo argument schema. The optional review may be omitted.")))
            }
            val issues = plan.validationIssues()
            return ParsedTaskPlan(plan.takeIf { issues.isEmpty() }, issues)
        }
    }
}

@Serializable
data class TaskExecutionPolicy(val reviewAfterCalls: Int, val maxObservationRecoveries: Int,
                               val maxCallsPerTask: Int, val maxRejectedCalls: Int) {
    fun validate() { require(reviewAfterCalls > 0 && maxObservationRecoveries > 0 &&
        maxCallsPerTask > 0 && maxRejectedCalls > 0) }
}

/** Event-derived protocol state. Model assessments are never promoted to verified business facts. */
data class TaskExecutionProgress(val plan: TaskExecutionPlan?, val attempts: Map<String, Int>,
                                 val reviewedAt: Map<String, Int>, val totalCalls: Int,
                                 val rejectedCalls: Int, val failedMethods: List<JsonObject>, val repeatedProtocolRejections: Int = 0,
                                 val consecutiveObservationRecoveries: Int = 0) {
    fun render(): String = "<model_authored_execution_plan>\n" + buildJsonObject {
        plan?.let { put("plan", Json.encodeToJsonElement(it)) }
        put("device_attempts_by_step", Json.encodeToJsonElement(attempts))
        put("total_device_attempts", totalCalls)
        put("pending_protocol_rejections", repeatedProtocolRejections)
        put("consecutive_observation_recoveries", consecutiveObservationRecoveries)
        put("retained_method_reviews", JsonArray(failedMethods))
        put("assessment_is_not_independent_verification", true)
    } + "\n</model_authored_execution_plan>"

    fun check(tool: String, arguments: String, deviceMutation: Boolean,
              policy: TaskExecutionPolicy, instruction: String,
              sourceExists: (String) -> Boolean): ToolExecutionResult? {
        policy.validate()
        var problem: String? = null
        var problemDetails: (JsonObjectBuilder.() -> Unit)? = null
        if (tool == "task_memo") {
            val parsed = TaskExecutionPlan.parse(arguments)
            parsed.rejection()?.let { return it }
            val next = checkNotNull(parsed.plan)
            val nextActive = next.active
            val currentActive = plan?.active
            problem = when {
                next.protected_values.any { !sourceExists(it.source_ref) } -> {
                    problemDetails = { put("invalid_source_refs", JsonArray(next.protected_values
                        .filter { !sourceExists(it.source_ref) }.map { JsonPrimitive(it.source_ref) })) }
                    "TASK_OUTPUT_CONSTRAINT_SOURCE_REQUIRED"
                }
                plan != null && next.record_path != plan.record_path -> {
                    problemDetails = {
                        put("invalid_fields", Json.encodeToJsonElement(listOf(TaskPlanIssue("record_path",
                            "record_path_changed", "Retain the committed record_path: ${plan.record_path}. Use workspace for additional files."))))
                    }
                    "TASK_PLAN_IDENTITY_CHANGED"
                }
                currentActive != null && nextActive?.id != currentActive.id &&
                    (next.steps.firstOrNull { it.id == currentActive.id }?.status !in setOf("complete", "blocked", "superseded")) -> {
                    problemDetails = {
                        put("invalid_fields", Json.encodeToJsonElement(listOf(TaskPlanIssue("steps",
                            "previous_active_step_unresolved", "Retain step ${currentActive.id} as complete with sources, blocked, or superseded before activating another method. No review is required."))))
                    }
                    "TASK_STEP_TRANSITION_REQUIRED"
                }
                next.steps.filter { it.status == "complete" }.any { !sourceExists(it.evidence_refs.joinToString(",")) } -> {
                    val invalidSteps = next.steps.filter { it.status == "complete" && !sourceExists(it.evidence_refs.joinToString(",")) }
                    problemDetails = {
                        put("invalid_steps", buildJsonArray {
                            invalidSteps.forEach { step ->
                                add(buildJsonObject {
                                    put("step_id", step.id)
                                    put("objective", step.objective)
                                    put("invalid_refs", buildJsonArray {
                                        step.evidence_refs.filter { !sourceExists(it) }.forEach { add(JsonPrimitive(it)) }
                                    })
                                })
                            }
                        })
                    }
                    "TASK_STEP_EVIDENCE_REQUIRED"
                }
                next.review != null && currentActive == null -> {
                    problemDetails = {
                        put("invalid_fields", Json.encodeToJsonElement(listOf(TaskPlanIssue("review",
                            "no_previously_active_step", "Omit review. Initialize or activate the chosen step without reviewing it; device actions do not require a plan."))))
                    }
                    "TASK_METHOD_REVIEW_INVALID"
                }
                next.review != null && (!sourceExists(next.review.evidence_refs.joinToString(",")) ||
                    (next.review.outcome == "replan" && failedMethods.any { retained ->
                        retained["step_id"]?.jsonPrimitive?.content == currentActive?.id &&
                            retained["review"]?.jsonObject?.get("next_method")?.jsonPrimitive?.content == next.review.next_method
                    }) || (next.review.outcome == "progress" && next.review.evidence_refs.all { ref ->
                        failedMethods.any { retained -> retained["step_id"]?.jsonPrimitive?.content == currentActive?.id &&
                            retained["review"]?.jsonObject?.get("evidence_refs")?.jsonArray.orEmpty()
                                .any { it.jsonPrimitive.content == ref } }
                    })) -> {
                    problemDetails = {
                        put("invalid_fields", Json.encodeToJsonElement(listOf(TaskPlanIssue("review",
                            "invalid_or_repeated_review_sources", "Inspect new original sources before reviewing. Omit review for an ordinary plan update; no review is needed to unlock device actions."))))
                        if (!sourceExists(next.review.evidence_refs.joinToString(","))) {
                            put("invalid_review_refs", buildJsonArray {
                                next.review.evidence_refs.filter { !sourceExists(it) }.forEach { add(JsonPrimitive(it)) }
                            })
                        }
                    }
                    "TASK_METHOD_REVIEW_INVALID"
                }
                else -> null
            }
        } else if (deviceMutation) {
            problem = mutationBlockCode(policy)
        }
        return problem?.let { ToolProtocolResult.notExecuted(it, retryable = it != "TASK_EXECUTION_BUDGET_EXHAUSTED", details = buildJsonObject {
            put("execution_state", render()); put("execution_limits", Json.encodeToJsonElement(policy))
            problemDetails?.invoke(this)
            put("recovery_condition", instruction)
        }) }
    }

    fun mutationBlockCode(policy: TaskExecutionPolicy): String? {
        policy.validate()
        // Resource limits belong to the turn, not to model-authored method IDs or statuses.
        return "TASK_EXECUTION_BUDGET_EXHAUSTED".takeIf { totalCalls >= policy.maxCallsPerTask }
    }

    /** An advisory at a model decision boundary, never a gate in an action group. */
    fun reviewDue(policy: TaskExecutionPolicy): Boolean = plan?.active?.id?.let { id ->
        (attempts[id] ?: 0) - (reviewedAt[id] ?: 0) >= policy.reviewAfterCalls
    } ?: false

    companion object {
        fun project(events: List<AgentEventEnvelope>, scope: TurnEventScope,
                    refreshDiagnostics: Set<String> = emptySet()): TaskExecutionProgress {
            val scoped = events.filter { it.threadId == scope.threadId && it.turnId == scope.turnId }
                .distinctBy { it.eventId }.sortedBy { it.sequence }
            val calls = mutableMapOf<String, ToolCallEventPayload>()
            var plan: TaskExecutionPlan? = null
            val attempts = mutableMapOf<String, Int>()
            val reviewed = mutableMapOf<String, Int>()
            val reviews = mutableListOf<JsonObject>()
            var total = 0
            var rejections = 0
            var currentObservationRecoveries = 0
            var protocolRejections = 0
            var protocolKey: String? = null
            var rejectedTool: String? = null
            for (event in scoped) when (event.eventKind) {
                AgentEventKind.USER_STEER -> { plan = null; reviewed.clear() } // attempts survive a user update
                AgentEventKind.TOOL_CALL -> event.callId?.let {
                    calls[it] = Json.decodeFromString<ToolCallEventPayload>(event.payloadJson)
                }
                AgentEventKind.TOOL_FINISHED, AgentEventKind.TOOL_CALL_REJECTED -> {
                    val call = calls[event.callId] ?: continue
                    val result = if (event.eventKind == AgentEventKind.TOOL_FINISHED)
                        Json.decodeFromString<ToolFinishedEventPayload>(event.payloadJson).result
                    else Json.decodeFromString<ToolCallRejectedEventPayload>(event.payloadJson).result
                    val recovery = ToolRecovery.classify(result, refreshDiagnostics)
                    val passiveObservation = UnifiedDeviceToolProtocol.isObservationOnlyAction(call.name, call.argumentsJson)
                    if (recovery == ToolRecovery.OBSERVE) currentObservationRecoveries++
                    else if (event.eventKind == AgentEventKind.TOOL_FINISHED && recovery == ToolRecovery.NONE &&
                        result.executionStatus == ToolExecutionStatus.SUCCEEDED && !passiveObservation &&
                        call.binding == ToolBinding.DEVICE_BOUND && call.effect == ToolEffect.STATE_CHANGING) {
                        // A settled mutation closes the current observation incident. Its earlier receipts
                        // remain in EventStore, and the turn's execution resource count is not renewed.
                        // Closing a technical incident does not claim verified goal progress.
                        currentObservationRecoveries = 0
                    }
                    if (recovery == ToolRecovery.PROTOCOL_REPAIR) {
                        val key = "${plan?.active?.id}:${call.name}:${result.diagnosticCode}"
                        protocolRejections = if (key == protocolKey) protocolRejections + 1 else 1
                        protocolKey = key
                        rejectedTool = call.name
                    } else if (result.executionStatus == ToolExecutionStatus.SUCCEEDED && !passiveObservation &&
                        (call.name == rejectedTool ||
                            (call.binding == ToolBinding.DEVICE_BOUND && call.effect == ToolEffect.STATE_CHANGING))) {
                        // A repaired protocol incident closes; unrelated reads do not renew its budget.
                        protocolRejections = 0
                        protocolKey = null
                        rejectedTool = null
                    }
                    if (result.diagnosticCode?.startsWith("TASK_") == true &&
                        result.executionStatus == ToolExecutionStatus.NOT_EXECUTED) rejections++
                    if (call.name == "task_memo" && result.isVerifiedSuccess) {
                        val next = TaskExecutionPlan.parse(call.argumentsJson).plan ?: continue
                        if (next.review != null && plan?.active != null) {
                            val id = plan.active!!.id
                            reviewed[id] = attempts[id] ?: 0
                            reviews += buildJsonObject {
                                put("step_id", id); put("review", Json.encodeToJsonElement(next.review))
                                put("event_ref", event.eventId)
                            }
                        }
                        if (plan == null || next.active?.id != plan?.active?.id || next.review != null) rejections = 0
                        plan = next
                    }
                    if (call.binding == ToolBinding.DEVICE_BOUND && call.effect == ToolEffect.STATE_CHANGING &&
                        result.executionStatus != ToolExecutionStatus.NOT_EXECUTED) {
                        total++
                        plan?.active?.id?.let { attempts[it] = (attempts[it] ?: 0) + 1 }
                    }
                }
                else -> Unit
            }
            return TaskExecutionProgress(plan, attempts, reviewed, total, rejections, reviews, protocolRejections, currentObservationRecoveries)
        }
    }
}
