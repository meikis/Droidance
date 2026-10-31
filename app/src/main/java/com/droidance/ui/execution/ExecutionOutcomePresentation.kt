// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.execution

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.droidance.R
import com.droidance.core.agent.event.ToolActivityState
import com.droidance.core.agent.event.ToolExecutionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class ExecutionBatchProgress(
    val completed: Int,
    val total: Int,
    val attemptedStep: Int?,
    val unexecuted: Int
)

internal data class ExecutionOutcomePresentation(
    val status: ToolExecutionStatus,
    val batch: Boolean,
    val progress: ExecutionBatchProgress?,
    val reasonResource: Int?
)

/** Retained delivery facts describe this call; they never establish the user's business outcome. */
internal fun executionOutcomePresentation(record: ExecutionCallRecord): ExecutionOutcomePresentation? {
    val detail = record.detail
    val status = detail?.executionStatus ?: when (record.activity.state) {
        ToolActivityState.REJECTED -> ToolExecutionStatus.NOT_EXECUTED
        ToolActivityState.FAILED -> ToolExecutionStatus.FAILED
        else -> return null
    }
    if (status == ToolExecutionStatus.SUCCEEDED) return null
    val arguments = runCatching { Json.parseToJsonElement(detail?.argumentsJson.orEmpty()) as? JsonObject }.getOrNull()
    val batch = detail?.technicalName?.substringBefore('.') == "screen_act" &&
        arguments?.get("actions") is JsonArray
    val metrics = detail?.metrics.orEmpty()
    fun count(key: String): Int? = metrics[key]?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    val total = count("batch_requested_actions")?.takeIf { it > 0 }
        ?: (arguments?.get("actions") as? JsonArray)?.size?.takeIf { it > 0 }
    val completed = count("batch_completed_actions")
        ?: 0.takeIf { status == ToolExecutionStatus.NOT_EXECUTED }
    val attempted = count("batch_attempted_actions")
        ?: 0.takeIf { status == ToolExecutionStatus.NOT_EXECUTED }
    val progress = if (batch && total != null && completed != null && attempted != null &&
        completed in 0..total && attempted in completed..total) {
        ExecutionBatchProgress(completed, total, attempted.takeIf { it > completed }, total - attempted)
    } else null
    return ExecutionOutcomePresentation(status, batch, progress,
        executionFailureReasonResource(detail?.diagnosticCode ?: record.activity.diagnosticCode))
}

internal fun executionFailureReasonResource(code: String?): Int? = when (code) {
    "ARGUMENT_SCHEMA_MISMATCH", "SCREEN_ACT_BATCH_REJECTED" -> R.string.execution_failure_arguments
    "PRE_ACTION_OBSERVATION_CHANGED", "STALE_OBSERVATION", "VISIT_RETURN_NOT_CONFIRMED" -> R.string.execution_failure_page_changed
    "BATCH_EXECUTION_SURFACE_CHANGED" -> R.string.execution_failure_surface_changed
    "TEXT_INPUT_BACKEND_NOT_READY" -> R.string.execution_failure_input_unavailable
    "TEXT_INPUT_TARGET_FOCUS_UNVERIFIED", "BATCH_INPUT_FOCUS_UNAVAILABLE" -> R.string.execution_failure_input_focus
    "BATCH_CAPTURE_FAILED", "BATCH_CAPTURE_UNAVAILABLE", "POST_ACTION_OBSERVATION_UNAVAILABLE",
    "PRE_ACTION_OBSERVATION_UNAVAILABLE" -> R.string.execution_failure_observation_unavailable
    "BATCH_CAPTURE_CAPACITY" -> R.string.execution_failure_capture_capacity
    "TOOL_EXECUTION_TIMEOUT" -> R.string.execution_failure_timeout
    else -> null
}

@Composable
internal fun ExecutionOutcomeSummary(record: ExecutionCallRecord) {
    val outcome = executionOutcomePresentation(record) ?: return
    val progress = outcome.progress
    val text = buildList {
        progress?.let { add(pluralStringResource(R.plurals.execution_batch_progress, it.total, it.completed, it.total)) }
        add(when {
            progress != null && progress.completed == progress.total ->
                stringResource(R.string.execution_result_unconfirmed)
            progress?.attemptedStep != null -> stringResource(
                if (outcome.status in setOf(ToolExecutionStatus.CANCELED, ToolExecutionStatus.INTERRUPTED))
                    R.string.execution_batch_uncertain_step else R.string.execution_batch_failed_step,
                progress.attemptedStep)
            outcome.status == ToolExecutionStatus.NOT_EXECUTED -> stringResource(
                if (outcome.batch) R.string.execution_batch_not_executed else R.string.execution_step_not_executed)
            outcome.status == ToolExecutionStatus.CANCELED -> stringResource(R.string.execution_operation_canceled)
            outcome.status == ToolExecutionStatus.INTERRUPTED -> stringResource(R.string.execution_operation_interrupted)
            else -> stringResource(R.string.execution_step_not_completed)
        })
        if (progress != null && progress.unexecuted > 0 && outcome.status != ToolExecutionStatus.NOT_EXECUTED)
            add(pluralStringResource(R.plurals.execution_batch_unexecuted, progress.unexecuted, progress.unexecuted))
        outcome.reasonResource?.let { add(stringResource(it)) }
    }.joinToString(" ")
    Text(text, modifier = Modifier.testTag("execution-outcome:${record.activity.callId}"),
        style = MaterialTheme.typography.bodySmall,
        color = if (outcome.status == ToolExecutionStatus.CANCELED) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error)
}
