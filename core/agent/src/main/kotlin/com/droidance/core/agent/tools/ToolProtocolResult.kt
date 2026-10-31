// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.agent.event.ToolExecutionStatus
import com.droidance.core.agent.event.ToolEvidenceStatus
import com.droidance.core.agent.event.ActionDeliveryEvidence
import com.droidance.core.agent.event.GoalProgressEvidence
import com.droidance.core.agent.event.StateTransitionEvidence
import com.droidance.core.agent.event.ToolActionEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Machine-readable result for a tool call closed without a normal provider receipt. */
object ToolProtocolResult {
    fun success(output: String): ToolExecutionResult = ToolExecutionResult(
        output, ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.NOT_REQUIRED
    )

    fun accepted(output: String): ToolExecutionResult = ToolExecutionResult(
        output, ToolExecutionStatus.SUCCEEDED, ToolEvidenceStatus.UNKNOWN,
        diagnosticCode = "ACTION_DELIVERED",
        actionEvidence = com.droidance.core.agent.event.projectToolActionEvidence(true, null, null, null, null)
    )

    fun missingArgument(name: String): ToolExecutionResult = notExecuted(
        code = "ARGUMENT_SCHEMA_MISMATCH",
        retryable = true,
        details = buildJsonObject { put("missing_parameter", JsonPrimitive(name)) }
    )

    fun invalidArguments(): ToolExecutionResult = notExecuted(
        code = "ARGUMENT_SCHEMA_MISMATCH",
        retryable = true,
        details = buildJsonObject { put("reason", JsonPrimitive("invalid_json")) }
    )

    fun invalidArgument(name: String, reason: String, value: String? = null): ToolExecutionResult = notExecuted(
        code = "ARGUMENT_SCHEMA_MISMATCH",
        retryable = true,
        details = buildJsonObject {
            put("parameter", JsonPrimitive(name))
            put("reason", JsonPrimitive(reason))
            value?.let { put("value", JsonPrimitive(it)) }
        }
    )

    fun failed(code: String, retryable: Boolean, error: Throwable): ToolExecutionResult {
        if (error is CancellationException) throw error
        return failed(
            code = code,
            retryable = retryable,
            details = buildJsonObject {
                put("error_type", JsonPrimitive(error::class.java.simpleName))
                error.message?.takeIf(String::isNotBlank)?.let { put("message", JsonPrimitive(it.take(300))) }
            }
        )
    }

    fun notExecuted(
        code: String,
        retryable: Boolean,
        details: JsonObject = JsonObject(emptyMap()),
        evidenceRefs: List<String> = emptyList()
    ): ToolExecutionResult = result(
        executionStatus = ToolExecutionStatus.NOT_EXECUTED,
        code = code,
        retryable = retryable,
        details = details,
        evidenceRefs = evidenceRefs
    )

    fun failed(
        code: String,
        retryable: Boolean,
        details: JsonObject = JsonObject(emptyMap()),
        evidenceRefs: List<String> = emptyList()
    ): ToolExecutionResult = result(
        executionStatus = ToolExecutionStatus.FAILED,
        code = code,
        retryable = retryable,
        details = details,
        evidenceRefs = evidenceRefs
    )

    fun canceled(
        code: String,
        details: JsonObject = JsonObject(emptyMap()),
        evidenceRefs: List<String> = emptyList()
    ): ToolExecutionResult = result(
        executionStatus = ToolExecutionStatus.CANCELED,
        code = code,
        retryable = false,
        details = details,
        evidenceRefs = evidenceRefs
    )

    fun interrupted(
        code: String,
        details: JsonObject = JsonObject(emptyMap()),
        evidenceRefs: List<String> = emptyList()
    ): ToolExecutionResult = result(
        executionStatus = ToolExecutionStatus.INTERRUPTED,
        code = code,
        retryable = false,
        details = details,
        evidenceRefs = evidenceRefs
    ).copy(
        evidenceStatus = ToolEvidenceStatus.UNKNOWN,
        actionEvidence = ToolActionEvidence(
            delivery = ActionDeliveryEvidence.UNKNOWN,
            transition = StateTransitionEvidence.UNKNOWN,
            goalProgress = GoalProgressEvidence.NOT_PROVEN
        )
    )

    private fun result(
        executionStatus: ToolExecutionStatus,
        code: String,
        retryable: Boolean,
        details: JsonObject,
        evidenceRefs: List<String>
    ): ToolExecutionResult {
        require(code.matches(CODE_PATTERN)) { "Tool result code must be uppercase snake case" }
        val output = buildJsonObject {
            put("execution_status", JsonPrimitive(executionStatus.name))
            put("code", JsonPrimitive(code))
            put("retryable", JsonPrimitive(retryable))
            put("details", details)
            put(
                "evidenceRefs",
                JsonArray(evidenceRefs.filter(String::isNotBlank).distinct().map(::JsonPrimitive))
            )
            put(
                "available_evidence_refs",
                JsonArray(evidenceRefs.filter(String::isNotBlank).distinct().map(::JsonPrimitive))
            )
        }.toString()
        return ToolExecutionResult(output, executionStatus, ToolEvidenceStatus.NOT_REQUIRED, diagnosticCode = code)
    }

    private val CODE_PATTERN = Regex("[A-Z][A-Z0-9_]*")
}
