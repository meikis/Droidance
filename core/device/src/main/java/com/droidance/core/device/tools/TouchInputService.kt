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

/** Executes the three touch operations exposed by the unified screen_act contract. */
class TouchInputService(
    private val deviceBackend: DeviceBackend,
    private val planScroll: (String, Float, HumanGesturePlanner.SwipeRegion?) -> IntArray,
    private val preflightScrollTarget: suspend (ModelCoordinateMapper.Point) -> ToolExecutionResult,
    private val scrollDuration: (String) -> Int,
    private val describeTarget: (Int, Int) -> String?,
    private val performVerifiedTap: suspend (String, String, String, Int, Int, TapPrecision) -> ToolExecutionResult,
    private val captureObservation: suspend (forceFresh: Boolean) -> LocalActionEvidence.ObservationLite,
    private val captureSettledObservation: suspend () -> LocalActionEvidence.ObservationLite,
    private val stagePostActionObservation: (LocalActionEvidence.ObservationLite) -> Unit,
    private val validate: (String, String, LocalActionEvidence.ObservationLite, LocalActionEvidence.ObservationLite, Boolean) -> LocalActionEvidence.ValidationResult,
    private val appendValidation: (ToolExecutionResult, LocalActionEvidence.ValidationResult?) -> ToolExecutionResult,
    private val recordExecutedActionResult: suspend (DeviceInputResult) -> Unit,
    private val coordinateMapper: () -> ModelCoordinateMapper = { ModelCoordinateMapper.identity() },
    private val resolveTarget: (String) -> Pair<Int, Int>? = { null }
) {
    suspend fun click(arguments: String, observeAfter: Boolean = true): ToolExecutionResult {
        return try {
            val json = arguments.json()
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
            val x = resolvedPoint?.first ?: json.int("x") ?: return ToolProtocolResult.missingArgument("x")
            val y = resolvedPoint?.second ?: json.int("y") ?: return ToolProtocolResult.missingArgument("y")
            val point = when (val mapped = mapPointOrBoundsFail(x, y)) {
                is ModelCoordinateMapper.MappingResult.Valid -> mapped.point
                is ModelCoordinateMapper.MappingResult.Invalid -> return mapped.result
            }
            if (!observeAfter) {
                val result = deviceBackend.tap(point.x, point.y, TapPrecision.NORMAL)
                recordExecutedActionResult(result)
                return if (result.success) ToolProtocolResult.accepted("Tap delivered; observation deferred to batch boundary.")
                    else actionFailed(result.diagnosticCode ?: "TAP_FAILED", result.message)
            }
            performVerifiedTap(
                "click",
                arguments,
                describeTarget(point.x, point.y) ?: "requested point",
                point.x,
                point.y,
                TapPrecision.NORMAL
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolProtocolResult.failed("TAP_EXECUTION_FAILED", retryable = true, error)
        }
    }

    suspend fun swipe(arguments: String, observeAfter: Boolean = true): ToolExecutionResult {
        return try {
            val json = arguments.json()
            val startX = json.int("startX") ?: return ToolProtocolResult.missingArgument("startX")
            val startY = json.int("startY") ?: return ToolProtocolResult.missingArgument("startY")
            val endX = json.int("endX") ?: return ToolProtocolResult.missingArgument("endX")
            val endY = json.int("endY") ?: return ToolProtocolResult.missingArgument("endY")
            val duration = json.int("duration") ?: 500
            val gesture = when (val mapped = mapGestureOrBoundsFail(startX, startY, endX, endY)) {
                is GestureMapping.Valid -> mapped
                is GestureMapping.Invalid -> return mapped.result
            }
            val before = captureObservation(false)
            val result = deviceBackend.swipe(
                gesture.start.x,
                gesture.start.y,
                gesture.end.x,
                gesture.end.y,
                duration
            )
            recordExecutedActionResult(result)
            if (!result.success) return actionFailed(result.diagnosticCode ?: "SWIPE_FAILED", result.message)
            if (!observeAfter) return ToolProtocolResult.accepted("Swipe delivered; inspect the final group observation.")
            val actual = result.actionEvent as? ExecutedActionEvent.Swipe
            val after = captureSettledObservation()
            stagePostActionObservation(after)
            val validation = validate("swipe", arguments, before, after, false)
            val message = when (validation.progressState) {
                LocalActionEvidence.ProgressState.UNCHANGED ->
                    "Swipe injected but had no effect: the screen did not change."
                LocalActionEvidence.ProgressState.CONTEXT_CHANGED ->
                    "Swipe injected but changed foreground context before the target screen progressed."
                else -> "Swipe injected and progressed in ${actual?.duration ?: duration}ms."
            }
            appendValidation(ToolProtocolResult.accepted(message), validation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolProtocolResult.failed("SWIPE_EXECUTION_FAILED", retryable = true, error)
        }
    }

    suspend fun path(arguments: String, drag: Boolean, observeAfter: Boolean = true): ToolExecutionResult {
        val json = runCatching { arguments.json() }.getOrElse { return ToolProtocolResult.invalidArguments() }
        val duration = json.int("duration") ?: if (drag) 300 else 1500
        if (duration !in 50..10000) return ToolProtocolResult.invalidArgument("duration", "expected_50_to_10000_ms")
        val points = if (drag) {
            val sx = json.int("startX") ?: return ToolProtocolResult.missingArgument("startX")
            val sy = json.int("startY") ?: return ToolProtocolResult.missingArgument("startY")
            val ex = json.int("endX") ?: return ToolProtocolResult.missingArgument("endX")
            val ey = json.int("endY") ?: return ToolProtocolResult.missingArgument("endY")
            listOf(sx to sy, ex to ey)
        } else {
            val array = json["points"] as? kotlinx.serialization.json.JsonArray ?: return ToolProtocolResult.missingArgument("points")
            if (array.size !in 2..128) return ToolProtocolResult.invalidArgument("points", "expected_2_to_128_points")
            array.map { element ->
                val point = element as? JsonObject ?: return ToolProtocolResult.invalidArgument("points", "expected_objects")
                (point.int("x") ?: return ToolProtocolResult.missingArgument("points.x")) to
                    (point.int("y") ?: return ToolProtocolResult.missingArgument("points.y"))
            }
        }
        val mapped = points.map { (x, y) -> when (val result = mapPointOrBoundsFail(x, y)) {
            is ModelCoordinateMapper.MappingResult.Valid -> result.point.x to result.point.y
            is ModelCoordinateMapper.MappingResult.Invalid -> return result.result
        } }
        val before = if (observeAfter) captureObservation(false) else null
        val result = if (drag) deviceBackend.drag(mapped[0].first, mapped[0].second, mapped[1].first, mapped[1].second, duration)
            else deviceBackend.tracePath(mapped, duration)
        recordExecutedActionResult(result)
        if (!result.success) return if (result.diagnosticCode == "GESTURE_BACKEND_UNAVAILABLE")
            ToolProtocolResult.notExecuted("GESTURE_BACKEND_UNAVAILABLE", retryable = false)
        else ToolProtocolResult.interrupted("GESTURE_EFFECT_UNKNOWN", details = buildJsonObject {
            put("diagnostic", JsonPrimitive(result.diagnosticCode ?: "GESTURE_FAILED"))
            put("message", JsonPrimitive(result.message))
        })
        val receipt = ToolProtocolResult.accepted(result.message)
        if (!observeAfter) return receipt
        val after = captureSettledObservation()
        stagePostActionObservation(after)
        return appendValidation(receipt, validate(if (drag) "drag" else "path", arguments, checkNotNull(before), after, false))
    }

    suspend fun scroll(arguments: String, observeAfter: Boolean = true): ToolExecutionResult {
        return try {
            val json = arguments.json()
            val direction = json.string("direction")?.lowercase()
                ?: return ToolProtocolResult.missingArgument("direction")
            val amount = json.string("amount")?.lowercase() ?: "medium"
            val speed = json.string("speed")?.lowercase() ?: "normal"
            val region = json.scrollRegion()
            val swipe = planScroll(direction, amountRatio(amount), region)
            val duration = scrollDuration(speed)
            val before = if (observeAfter) captureObservation(false) else null
            // Plan once: the checked start must be the exact physical point later injected.
            val preflight = preflightScrollTarget(ModelCoordinateMapper.Point(swipe[0], swipe[1]))
            if (!preflight.isVerifiedSuccess) return preflight
            fun checked(receipt: ToolExecutionResult) = receipt.copy(metrics = receipt.metrics + preflight.metrics)
            val result = ObservationTiming.measure("gesture_delivery_ms") {
                deviceBackend.swipe(swipe[0], swipe[1], swipe[2], swipe[3], duration)
            }
            recordExecutedActionResult(result)
            if (!result.success) return checked(actionFailed(result.diagnosticCode ?: "SCROLL_FAILED", result.message))
            if (!observeAfter) return checked(ToolProtocolResult.accepted("Scroll delivered; observation deferred to batch boundary."))
            checkNotNull(before)
            val actual = result.actionEvent as? ExecutedActionEvent.Swipe
            val after = captureSettledObservation()
            stagePostActionObservation(after)
            val validation = validate("scroll", arguments, before, after, false)
            val message = when (validation.progressState) {
                LocalActionEvidence.ProgressState.UNCHANGED ->
                    "Scroll injected; the observed screen did not change. direction=$direction amount=$amount"
                LocalActionEvidence.ProgressState.CONTEXT_CHANGED ->
                    "Scroll injected but changed foreground context before the target screen progressed. direction=$direction amount=$amount"
                else ->
                    "Scroll injected and progressed: direction=$direction amount=$amount duration=${actual?.duration ?: swipe[4]}ms"
            }
            checked(appendValidation(ToolProtocolResult.accepted(message), validation))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolProtocolResult.failed("SCROLL_EXECUTION_FAILED", retryable = true, error)
        }
    }

    private fun actionFailed(code: String, message: String): ToolExecutionResult = ToolProtocolResult.failed(
        code = code,
        retryable = true,
        details = buildJsonObject { put("message", JsonPrimitive(message.take(300))) }
    )

    private fun mapPointOrBoundsFail(x: Int, y: Int): ModelCoordinateMapper.MappingResult =
        coordinateMapper().mapToPhysical(x, y)

    private fun mapGestureOrBoundsFail(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int
    ): GestureMapping {
        val start = when (val mapped = mapPointOrBoundsFail(startX, startY)) {
            is ModelCoordinateMapper.MappingResult.Valid -> mapped.point
            is ModelCoordinateMapper.MappingResult.Invalid -> return GestureMapping.Invalid(mapped.result)
        }
        val end = when (val mapped = mapPointOrBoundsFail(endX, endY)) {
            is ModelCoordinateMapper.MappingResult.Valid -> mapped.point
            is ModelCoordinateMapper.MappingResult.Invalid -> return GestureMapping.Invalid(mapped.result)
        }
        return GestureMapping.Valid(start, end)
    }

    private sealed interface GestureMapping {
        data class Valid(
            val start: ModelCoordinateMapper.Point,
            val end: ModelCoordinateMapper.Point
        ) : GestureMapping

        data class Invalid(val result: ToolExecutionResult) : GestureMapping
    }

    private fun amountRatio(amount: String): Float = when (amount) {
        "small" -> 0.25f
        "medium" -> 0.50f
        "large" -> 0.75f
        "full" -> 0.90f
        else -> 0.50f
    }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.scrollRegion(): HumanGesturePlanner.SwipeRegion? {
        val value = this["region"] ?: return null
        val region = value as? JsonObject
            ?: throw IllegalArgumentException("region must be an object with left, top, right, and bottom")
        fun required(name: String): Int = region[name]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("region.$name must be an integer")
        val left = required("left")
        val top = required("top")
        val right = required("right")
        val bottom = required("bottom")
        if (left !in 0..1000 || top !in 0..1000 || right !in 0..1000 || bottom !in 0..1000) {
            throw IllegalArgumentException("region bounds must be normalized integers in 0..1000")
        }
        if (left >= right || top >= bottom) {
            throw IllegalArgumentException("region must have left < right and top < bottom")
        }
        val bounds = coordinateMapper().toPhysicalBounds(left, top, right, bottom)
        return HumanGesturePlanner.SwipeRegion(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom
        )
    }
}
