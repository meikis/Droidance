// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

import com.droidance.core.agent.*

import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject

enum class UserDemonstrationActionType(val wireName: String) {
    TAP("tap"),
    LONG_PRESS("long_press"),
    SWIPE("swipe"),
    KEY_PRESS("key_press"),
    TEXT_INPUT("text_input"),
    BACK("back")
}

data class UserDemonstrationGesture(
    val actionType: UserDemonstrationActionType,
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMillis: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val samples: List<GestureSample> = emptyList(),
    val multiPointerObserved: Boolean = false,
    val samplesTruncated: Boolean = false
) {
    init {
        require(displayWidth > 0 && displayHeight > 0) { "Display size must be positive" }
        require(durationMillis >= 0L) { "Gesture duration cannot be negative" }
    }

    fun argumentsJson(): String = buildJsonObject {
        put("op", JsonPrimitive(actionType.wireName))
        put("start_x", JsonPrimitive(startX))
        put("start_y", JsonPrimitive(startY))
        put("end_x", JsonPrimitive(endX))
        put("end_y", JsonPrimitive(endY))
        put("normalized_start_x", JsonPrimitive(normalize(startX, displayWidth)))
        put("normalized_start_y", JsonPrimitive(normalize(startY, displayHeight)))
        put("normalized_end_x", JsonPrimitive(normalize(endX, displayWidth)))
        put("normalized_end_y", JsonPrimitive(normalize(endY, displayHeight)))
        put("duration_ms", JsonPrimitive(durationMillis))
        put("samples", JsonArray(samples.map { sample -> buildJsonObject {
            put("x", JsonPrimitive(sample.x)); put("y", JsonPrimitive(sample.y)); put("time_ms", JsonPrimitive(sample.timeMillis))
        } }))
        put("multi_pointer_observed", JsonPrimitive(multiPointerObserved))
        put("samples_truncated", JsonPrimitive(samplesTruncated))
        put("coordinate_role", JsonPrimitive("recorded_hint_only"))
    }.toString()

    private fun normalize(value: Int, extent: Int): Int =
        ((value.coerceIn(0, extent) * 1000.0) / extent).toInt().coerceIn(0, 1000)
}

data class GestureSample(val x: Int, val y: Int, val timeMillis: Long)

object UserDemonstrationGestureClassifier {
    private const val LONG_PRESS_MILLIS = 500L

    fun classify(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Long,
        displayWidth: Int,
        displayHeight: Int,
        touchSlopPx: Int
    ): UserDemonstrationGesture {
        val distance = hypot((endX - startX).toDouble(), (endY - startY).toDouble())
        val action = when {
            distance > touchSlopPx.coerceAtLeast(1) -> UserDemonstrationActionType.SWIPE
            durationMillis >= LONG_PRESS_MILLIS -> UserDemonstrationActionType.LONG_PRESS
            else -> UserDemonstrationActionType.TAP
        }
        return UserDemonstrationGesture(
            actionType = action,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMillis = durationMillis.coerceAtLeast(0L),
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
    }

    fun direction(gesture: UserDemonstrationGesture): String {
        if (gesture.actionType != UserDemonstrationActionType.SWIPE) return ""
        val dx = gesture.endX - gesture.startX
        val dy = gesture.endY - gesture.startY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }
}
