// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import com.droidance.core.agent.LocalActionEvidence

import com.droidance.core.agent.event.ToolExecutionResult
import com.droidance.core.device.agent.device.DeviceBackend
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TextInputService(
    private val deviceBackend: DeviceBackend,
    private val captureObservation: suspend (forceFresh: Boolean) -> LocalActionEvidence.ObservationLite,
    private val captureSettledObservation: suspend () -> LocalActionEvidence.ObservationLite,
    private val stagePostActionObservation: (LocalActionEvidence.ObservationLite) -> Unit,
    private val verifyTextDelivery: suspend (String, LocalActionEvidence.ObservationLite, ModelCoordinateMapper.Point, Boolean, Boolean) -> Boolean,
    private val inputFocusProbeTimeoutMs: () -> Long,
    private val resolveSemanticTarget: (String) -> ModelCoordinateMapper.Point?,
    private val wasTextInputVerified: (String, String, String) -> Boolean,
    private val recordTextInputVerification: (String, String, String, Boolean) -> Unit,
    private val recordInputDiagnostic: (String, String, String) -> Unit,
    private val validate: (String, String, LocalActionEvidence.ObservationLite, LocalActionEvidence.ObservationLite, Boolean) -> LocalActionEvidence.ValidationResult,
    private val appendValidation: (ToolExecutionResult, LocalActionEvidence.ValidationResult?) -> ToolExecutionResult,
    private val recordExecutedActionResult: suspend (DeviceInputResult) -> Unit,
    private val coordinateMapper: () -> ModelCoordinateMapper = { ModelCoordinateMapper.identity() },
    private val resolveTarget: (String) -> Pair<Int, Int>? = { null },
    private val deviceLocked: () -> Boolean = { false }
) {
    suspend fun typeText(
        arguments: String,
        batchObservation: LocalActionEvidence.ObservationLite? = null,
        expectedFocusTarget: String? = null
    ): ToolExecutionResult = try {
        executeTypeText(arguments, batchObservation, expectedFocusTarget)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        ToolProtocolResult.interrupted(
            code = "TEXT_INPUT_EFFECT_UNKNOWN",
            details = buildJsonObject {
                put("message", JsonPrimitive(e.message.orEmpty().take(500)))
            }
        )
    }

    private suspend fun executeTypeText(
        arguments: String,
        batchObservation: LocalActionEvidence.ObservationLite?,
        expectedFocusTarget: String?
    ): ToolExecutionResult {
        val json = runCatching { arguments.json() }.getOrElse { return ToolProtocolResult.invalidArguments() }
        val text = json.string("text") ?: return ToolProtocolResult.notExecuted(
            code = "TEXT_INPUT_ARGUMENT_MISSING",
            retryable = true,
            details = buildJsonObject {
                put("field", JsonPrimitive("text"))
            }
        )
        val clear = json.strictBooleanOrNull("clear")
            ?: if ("clear" in json) {
                return ToolProtocolResult.invalidArgument("clear", "expected_boolean")
            } else {
                return ToolProtocolResult.missingArgument("clear")
            }
        val method = if ("method" in json) json.string("method")?.let(TextInputMethod::parse)
            ?: return ToolProtocolResult.invalidArgument("method", "unsupported_value") else TextInputMethod.SHORTCUT
        val targetId = json.string("target_id")?.trim()?.takeIf(String::isNotBlank)
        val resolvedPoint = targetId?.let(resolveTarget)
        if (targetId != null && resolvedPoint == null && json.int("x") == null) {
            return ToolProtocolResult.failed(
                code = "TARGET_NOT_FOUND",
                retryable = false,
                details = buildJsonObject {
                    put("target_id", JsonPrimitive(targetId))
                    put("reason", JsonPrimitive("Target ID was not found in the latest observation frame. Provide valid coordinates (x, y) or an active target_id."))
                }
            )
        }
        val focusX = resolvedPoint?.first ?: json.int("x")
        val focusY = resolvedPoint?.second ?: json.int("y")
        if (method == TextInputMethod.IME && (focusX == null || focusY == null)) {
            return ToolProtocolResult.invalidArgument("x,y", "ime_requires_observed_editor_coordinates_after_driver_binding")
        }
        val targetQuery = json.string("query")?.trim()?.takeIf(String::isNotBlank)
        if ((focusX == null) != (focusY == null)) {
            return ToolProtocolResult.notExecuted(
                code = "TEXT_INPUT_ARGUMENT_MISSING",
                retryable = true,
                details = buildJsonObject {
                    put("fields", JsonPrimitive("x,y"))
                    put("constraint", JsonPrimitive("both_or_neither"))
                }
            )
        }
        val startedAt = System.nanoTime()
        val before = batchObservation ?: captureObservation(false)
        var focusDeadline = System.nanoTime() + inputFocusProbeTimeoutMs() * 1_000_000L
        var focusWaitMs = 0L
        suspend fun awaitFocus(): com.droidance.core.device.agent.device.InputFocusReadiness {
            val remainingMs = ((focusDeadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)
            val readiness = if (remainingMs > 0) deviceBackend.awaitInputFocus(remainingMs)
                else com.droidance.core.device.agent.device.InputFocusReadiness(null, "INPUT_CONNECTION_NOT_READY")
            focusWaitMs += readiness.elapsedMs
            return readiness
        }
        // An omitted target means the current input window, never the last tapped coordinate.
        val readinessBefore = if (focusX == null && targetQuery == null || method == TextInputMethod.IME) {
            expectedFocusTarget?.let { com.droidance.core.device.agent.device.InputFocusReadiness(it, "INPUT_WINDOW_FOCUSED") }
                ?: awaitFocus()
        } else null
        val focusBefore = readinessBefore?.target
        if (expectedFocusTarget != null && (focusTargetId(expectedFocusTarget) == null ||
                (focusX != null || targetQuery != null) && method != TextInputMethod.IME || focusBefore != expectedFocusTarget)) {
            return targetFocusUnverified(text, focusAvailable = focusBefore != null,
                detail = when {
                    focusTargetId(expectedFocusTarget) == null -> "INPUT_WINDOW_IDENTITY_UNAVAILABLE"
                    focusX != null || targetQuery != null -> "INPUT_FOCUS_BINDING_TARGET_CONFLICT"
                    focusBefore == null -> readinessBefore?.diagnosticCode ?: "INPUT_CONNECTION_NOT_READY"
                    else -> "INPUT_WINDOW_CHANGED"
                }, focusWaitMs = focusWaitMs, startedAt = startedAt, readiness = readinessBefore)
        }
        val requestedFocusPoint = if (focusX != null && focusY != null) {
            when (val mapped = coordinateMapper().mapToPhysical(focusX, focusY)) {
                is ModelCoordinateMapper.MappingResult.Valid -> mapped.point
                is ModelCoordinateMapper.MappingResult.Invalid -> return mapped.result
            }
        } else targetQuery?.let(resolveSemanticTarget)
        if (requestedFocusPoint == null && (targetQuery != null || focusBefore == null)) {
            if (targetQuery != null) {
                recordInputDiagnostic(text, "semantic_target_not_found", "query=$targetQuery")
                return ToolProtocolResult.notExecuted(
                    code = "TEXT_INPUT_TARGET_NOT_FOUND",
                    retryable = true,
                    details = buildJsonObject {
                        put("query", JsonPrimitive(targetQuery))
                        put("focus_available", JsonPrimitive(false))
                        put("target_focus_bound", JsonPrimitive(false))
                        put("injected", JsonPrimitive(false))
                    },
                    evidenceRefs = listOfNotNull(before.observationId)
                )
            }
            return targetFocusUnverified(text, focusAvailable = focusBefore != null,
                detail = readinessBefore?.diagnosticCode ?: "INPUT_TARGET_UNAVAILABLE",
                focusWaitMs = focusWaitMs, startedAt = startedAt, readiness = readinessBefore)
        }

        val pageKey = before.semanticState?.pageKey
            ?: before.foregroundPackage
            ?: "screen:${before.screenHash ?: "unknown"}"
        // Explicit visual intent wins; no internal View hierarchy is required or consulted.
        val focusPoint = requestedFocusPoint
        val transactionTargetKey = focusPoint?.let(::transactionTargetKey) ?: "current-input-window"
        if (focusPoint != null && method != TextInputMethod.IME) {
            val focusResult = deviceBackend.tap(focusPoint.x, focusPoint.y, TapPrecision.NORMAL)
            recordExecutedActionResult(focusResult)
            if (!focusResult.success) {
                recordInputDiagnostic(text, "focus_tap_failed", focusResult.message)
                return ToolProtocolResult.failed(
                    code = "TEXT_INPUT_FOCUS_TAP_FAILED",
                    retryable = false,
                    details = buildJsonObject {
                        put("message", JsonPrimitive(focusResult.message.take(500)))
                        put("focus_available", JsonPrimitive(false))
                        put("target_focus_bound", JsonPrimitive(false))
                        put("injected", JsonPrimitive(false))
                    },
                    evidenceRefs = listOfNotNull(before.observationId)
                )
            }
        }
        // The backend binds every probe and input to the frozen display/session. An input
        // window probe establishes dispatch readiness; it does not identify an editable View.
        if (focusPoint != null) focusDeadline = System.nanoTime() + inputFocusProbeTimeoutMs() * 1_000_000L
        // The native text transaction checks this exact input-window identity again before
        // replacement/paste. A causal click handoff needs no duplicate host-side query.
        val readiness = readinessBefore ?: awaitFocus()
        var focusAfter = readiness.target
        val sameExecutionDisplay = before.displayId != null && before.backendSessionId != null &&
            (deviceBackend.activeDisplayId ?: 0) == before.displayId
        if (focusAfter == null || !sameExecutionDisplay ||
            (focusPoint == null && focusBefore != focusAfter)
        ) {
            return targetFocusUnverified(
                text, focusAvailable = focusAfter != null, focusTapDelivered = focusPoint != null,
                detail = when {
                    !sameExecutionDisplay -> "INPUT_EXECUTION_SURFACE_CHANGED"
                    focusPoint == null && focusAfter != null && focusBefore != focusAfter -> "INPUT_WINDOW_CHANGED"
                    else -> readiness.diagnosticCode
                },
                focusWaitMs = focusWaitMs, startedAt = startedAt, readiness = readiness
            )
        }

        val targetField = transactionTargetKey
        val boundTargetKey = "field:$targetField"
        if (clear && focusPoint != null && wasTextInputVerified(pageKey, boundTargetKey, text)) {
            return duplicateVerified(targetField, targetFocusBound = true)
        }
        val focusTargetAttested = false // Window focus does not attest the visual field.

        val focusDurationMs = (System.nanoTime() - startedAt) / 1_000_000
        val imePoint = focusPoint?.takeIf { method == TextInputMethod.IME }?.let { it.x to it.y }
        var result = deviceBackend.typeText(text, clear, focusAfter, method, imePoint)
        recordExecutedActionResult(result)
        var focusRebinds = 0L
        // A tapped page can hand focus to its own input window after the outgoing window was
        // bound. Nothing was injected, so bind the identified window now holding focus on the same
        // display once, within the same focus budget. Input without a causal tap never rebinds.
        val tapCaused = expectedFocusTarget != null || focusPoint != null
        result.textInputEvidence?.takeIf { tapCaused && !it.focusConfirmed && !it.eventInjected }?.let {
            val rebound = awaitFocus().target
            if (rebound != null && rebound != focusAfter && focusTargetId(rebound) != null &&
                (deviceBackend.activeDisplayId ?: 0) == before.displayId) {
                focusAfter = rebound
                focusRebinds = 1L
                result = deviceBackend.typeText(text, clear, rebound, method, imePoint)
                recordExecutedActionResult(result)
            }
        }
        val baseEvidence = result.textInputEvidence ?: run {
            return ToolProtocolResult.failed(
                code = result.diagnosticCode ?: "TEXT_INPUT_FAILED",
                retryable = false,
                details = buildJsonObject {
                    put("message", JsonPrimitive(result.message.take(500)))
                    put("target_field", JsonPrimitive(targetField))
                },
                evidenceRefs = listOfNotNull(before.observationId)
            )
        }
        if (!baseEvidence.accepted && !baseEvidence.eventInjected &&
            runCatching { deviceLocked() }.getOrDefault(false)) {
            return ToolProtocolResult.interrupted("INPUT_BLOCKED_BY_KEYGUARD", details = buildJsonObject {
                put("keyguard_locked", JsonPrimitive(true))
                put("requires_human", JsonPrimitive(true))
                put("injected", JsonPrimitive(false))
                put("payload_confirmed", JsonPrimitive(baseEvidence.payloadConfirmed))
                put("driver_message", JsonPrimitive(result.message.take(500)))
            }).copy(metrics = mapOf("text_input_injected" to 0L))
        }
        if (!baseEvidence.focusConfirmed && !baseEvidence.eventInjected) {
            return targetFocusUnverified(text, focusAvailable = false,
                detail = "INPUT_WINDOW_CHANGED", focusTapDelivered = focusPoint != null,
                focusWaitMs = focusWaitMs, startedAt = startedAt, readiness = readiness)
        }
        if (batchObservation != null) {
            // No fresh pixels exist yet. Never attach the grounding frame as post-action evidence.
            // The editor readback on the same execution display still attests delivery to the
            // current input window; it cannot attest an explicitly targeted visual field.
            val delivered = baseEvidence.accepted && baseEvidence.eventInjected
            val currentEditorReadback = delivered && focusPoint == null &&
                baseEvidence.readback?.matches(text, before.displayId, before.backendSessionId, focusAfter, clear) == true
            val receipt = "[TEXT_INPUT] method=${method.wireName}; accepted=${baseEvidence.accepted}; " +
                "injected=${baseEvidence.eventInjected}; delivery_verified=$currentEditorReadback; " +
                "target_focus_bound=$currentEditorReadback; current_editor_readback_verified=$currentEditorReadback; " +
                "observation_deferred=true; " +
                "focus_ms=$focusDurationMs; injection_ms=${baseEvidence.injectionDurationMs}; " +
                "total_ms=${(System.nanoTime() - startedAt) / 1_000_000}; driver_message=${result.message.take(300)}"
            // A verified receipt keeps its delivery evidence so the batch continues in order.
            return (if (currentEditorReadback) ToolProtocolResult.success("Text delivery verified: $receipt").copy(
                    actionEvidence = com.droidance.core.agent.event.projectToolActionEvidence(true, null, null, null, null))
                else if (delivered) ToolProtocolResult.accepted(receipt)
                else ToolProtocolResult.interrupted("TEXT_INPUT_EFFECT_UNKNOWN", details = buildJsonObject {
                    put("outcome", JsonPrimitive(baseEvidence.outcome.name))
                    put("injected", JsonPrimitive(baseEvidence.eventInjected))
                    put("payload_confirmed", JsonPrimitive(baseEvidence.payloadConfirmed))
                    put("driver_message", JsonPrimitive(result.message.take(500)))
                })).copy(metrics = mapOf(
                "text_input_focus_ms" to focusDurationMs,
                "text_input_focus_wait_ms" to focusWaitMs,
                "text_input_focus_rebinds" to focusRebinds,
                "text_input_query_original_chars" to readiness.queryOriginalChars,
                "text_input_query_selected_chars" to readiness.querySelectedChars,
                "text_input_injection_ms" to (baseEvidence.injectionDurationMs ?: 0L),
                "text_input_readback_ms" to (baseEvidence.readbackDurationMs ?: 0L),
                "text_input_observation_ms" to 0L,
                "text_input_injected" to if (baseEvidence.eventInjected) 1L else 0L,
                "text_input_delivery_verified" to if (currentEditorReadback) 1L else 0L,
                "text_input_current_editor_readback_verified" to if (currentEditorReadback) 1L else 0L,
                "text_input_target_focus_bound" to if (currentEditorReadback) 1L else 0L,
                "text_input_total_ms" to (System.nanoTime() - startedAt) / 1_000_000
            ))
        }
        val observationStarted = System.nanoTime()
        val after = captureSettledObservation()
        stagePostActionObservation(after)
        val observationDurationMs = (System.nanoTime() - observationStarted) / 1_000_000
        val currentEditorReadback = before.displayId == after.displayId &&
            before.backendSessionId == after.backendSessionId &&
            baseEvidence.readback?.matches(text, after.displayId, after.backendSessionId, focusAfter, clear) == true
        val visualDestinationConfirmed = focusPoint != null && verifyTextDelivery(
            text, after, focusPoint, clear, focusTargetAttested
        )
        val targetVerified = visualDestinationConfirmed || (focusPoint == null && currentEditorReadback)
        val evidence = baseEvidence.withPostObservation(
            screenChanged = before.screenHash != null && after.screenHash != null && before.screenHash != after.screenHash,
            observationId = after.observationId,
            destinationConfirmed = targetVerified
        )
        recordTextInputVerification(
            pageKey,
            boundTargetKey,
            text,
            evidence.deliveryVerified
        )
        val targetFocusBound = focusTargetAttested || evidence.deliveryVerified
        val evidenceLine = "[TEXT_INPUT] method=${method.wireName}; accepted=${evidence.accepted}; " +
            "delivery_verified=${evidence.deliveryVerified}; outcome=${evidence.outcome}; focus=${evidence.focusConfirmed}; " +
            "target_focus_bound=$targetFocusBound; target_field=$targetField; " +
            "payload=${evidence.payloadConfirmed}; injected=${evidence.eventInjected}; " +
            "screen_changed=${evidence.screenChanged}; observation=${evidence.observationId.orEmpty()}; " +
            "current_editor_readback_verified=$currentEditorReadback; " +
            "focus_ms=$focusDurationMs; injection_ms=${baseEvidence.injectionDurationMs}; " +
            "readback_ms=${baseEvidence.readbackDurationMs}; observation_ms=$observationDurationMs; " +
            "total_ms=${(System.nanoTime() - startedAt) / 1_000_000}"
        val validation = validate("type_text", arguments, before, after, evidence.deliveryVerified)
        val message = when {
            !evidence.eventInjected || !evidence.accepted -> ToolProtocolResult.interrupted(
                code = result.diagnosticCode ?: "TEXT_INPUT_EFFECT_UNKNOWN",
                details = buildJsonObject {
                    put("accepted", JsonPrimitive(evidence.accepted))
                    put("delivery_verified", JsonPrimitive(evidence.deliveryVerified))
                    put("outcome", JsonPrimitive(evidence.outcome.name))
                    put("focus_available", JsonPrimitive(evidence.focusConfirmed))
                    put("target_focus_bound", JsonPrimitive(targetFocusBound))
                    put("target_field", JsonPrimitive(targetField))
                    put("payload_confirmed", JsonPrimitive(evidence.payloadConfirmed))
                    put("injected", JsonPrimitive(evidence.eventInjected))
                    put("screen_changed", JsonPrimitive(evidence.screenChanged))
                },
                evidenceRefs = listOfNotNull(evidence.observationId)
            )
            evidence.deliveryVerified -> ToolProtocolResult.success("Text delivery verified: $evidenceLine")
            else -> ToolProtocolResult.accepted("Text injection completed: $evidenceLine")
        }
        return appendValidation(message, validation).copy(metrics = message.metrics + mapOf(
            "text_input_focus_ms" to focusDurationMs,
            "text_input_focus_wait_ms" to focusWaitMs,
            "text_input_focus_rebinds" to focusRebinds,
            "text_input_query_original_chars" to readiness.queryOriginalChars,
            "text_input_query_selected_chars" to readiness.querySelectedChars,
            "text_input_injection_ms" to baseEvidence.injectionDurationMs,
            "text_input_readback_ms" to baseEvidence.readbackDurationMs,
            "text_input_observation_ms" to observationDurationMs,
            "text_input_total_ms" to (System.nanoTime() - startedAt) / 1_000_000,
            "text_input_visual_destination_confirmed" to if (visualDestinationConfirmed) 1L else 0L,
            "text_input_explicit_target" to if (requestedFocusPoint != null) 1L else 0L,
            "text_input_injected" to if (evidence.eventInjected) 1L else 0L,
            "text_input_delivery_verified" to if (evidence.deliveryVerified) 1L else 0L,
            "text_input_current_editor_readback_verified" to if (currentEditorReadback) 1L else 0L,
            "text_input_target_focus_bound" to if (targetFocusBound) 1L else 0L
        ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap())
    }

    private fun targetFocusUnverified(
        text: String,
        focusAvailable: Boolean,
        detail: String = "no current visual input target",
        focusTapDelivered: Boolean = false,
        focusWaitMs: Long = 0L,
        startedAt: Long = System.nanoTime(),
        readiness: com.droidance.core.device.agent.device.InputFocusReadiness? = null
    ): ToolExecutionResult {
        recordInputDiagnostic(
            text,
            "target_focus_unverified",
            "input_window_available=$focusAvailable; $detail"
        )
        val details = buildJsonObject {
            put("focus_available", JsonPrimitive(focusAvailable))
            put("target_focus_bound", JsonPrimitive(false))
            put("injected", JsonPrimitive(false))
            put("diagnostic", JsonPrimitive(detail))
            readiness?.let { probe ->
                probe.requestedDisplayId?.let { put("requested_display_id", JsonPrimitive(it)) }
                probe.targetKind?.let { put("focus_target_kind", JsonPrimitive(it)) }
                probe.focusedWindowName?.let { put("focused_window_name", JsonPrimitive(it)) }
                probe.queryCapturedElapsedMs?.let { put("query_captured_elapsed_ms", JsonPrimitive(it)) }
                probe.queryExitCode?.let { put("query_exit_code", JsonPrimitive(it)) }
                probe.queryDiagnosticCode?.let { put("query_diagnostic_code", JsonPrimitive(it)) }
                probe.queryExecutionStatus?.let { put("query_execution_status", JsonPrimitive(it)) }
                probe.queryStderr?.takeIf(String::isNotBlank)?.let { put("query_stderr", JsonPrimitive(it)) }
                probe.binderFailure?.let { put("binder_failure", JsonPrimitive(it)) }
                put("query_attempts", JsonPrimitive(probe.queryAttempts))
                put("query_output_truncated", JsonPrimitive(probe.queryOutputTruncated))
            }
            put("focus_tap_delivered", JsonPrimitive(focusTapDelivered))
            put("focus_state", JsonPrimitive(if (focusAvailable) "ready" else if (detail == "INPUT_CONNECTION_NOT_READY") "not_ready" else "unknown"))
            put("recovery_condition", JsonPrimitive(when (detail) {
                "INPUT_FOCUS_QUERY_TIMEOUT", "INPUT_FOCUS_QUERY_FAILED", "INPUT_FOCUS_FORMAT_UNAVAILABLE" -> "inspect_input_backend_diagnostic_before_retry"
                "INPUT_CONNECTION_NOT_READY" -> "fresh_visual_target_and_display_input_window"
                else -> "fresh_visual_target_and_display_input_window"
            }))
        }
        val code = when (detail) {
            "INPUT_FOCUS_QUERY_TIMEOUT", "INPUT_FOCUS_QUERY_FAILED", "INPUT_FOCUS_BINDER_TIMEOUT",
            "INPUT_FOCUS_FORMAT_UNAVAILABLE", "INPUT_FOCUS_OUTPUT_TRUNCATED", "INPUT_BACKEND_UNAVAILABLE", "INPUT_BACKEND_SESSION_CHANGED",
            "INPUT_WINDOW_FORMAT_UNAVAILABLE", "INPUT_DISPLAY_UNAVAILABLE" -> "TEXT_INPUT_BACKEND_NOT_READY"
            else -> "TEXT_INPUT_TARGET_FOCUS_UNVERIFIED"
        }
        val result = if (focusTapDelivered) ToolProtocolResult.failed(
            code = code, retryable = true, details = details
        ) else ToolProtocolResult.notExecuted(
            code = code, retryable = true, details = details
        )
        return result.copy(metrics = mapOf(
            "text_input_focus_wait_ms" to focusWaitMs,
            "text_input_focus_tap_delivered" to if (focusTapDelivered) 1L else 0L,
            "text_input_query_original_chars" to (readiness?.queryOriginalChars ?: 0),
            "text_input_query_selected_chars" to (readiness?.querySelectedChars ?: 0),
            "text_input_injected" to 0L,
            "text_input_total_ms" to (System.nanoTime() - startedAt) / 1_000_000L
        ))
    }

    private fun duplicateVerified(targetKey: String, targetFocusBound: Boolean): ToolExecutionResult =
        ToolProtocolResult.notExecuted(
            code = "TEXT_INPUT_DUPLICATE_VERIFIED",
            retryable = false,
            details = buildJsonObject {
                put("focus_available", JsonPrimitive(targetFocusBound))
                put("target_focus_bound", JsonPrimitive(targetFocusBound))
                put("target_field", JsonPrimitive(targetKey))
                put("injected", JsonPrimitive(false))
            }
        )

    private fun transactionTargetKey(point: ModelCoordinateMapper.Point): String {
        val normalized = coordinateMapper().toModel(point.x, point.y)
        return "point:${normalized.x / 160}:${normalized.y / 120}"
    }

    private fun focusTargetId(focusState: String?): String? = focusState
        ?.substringAfter("window_id=", missingDelimiterValue = "")
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
}
