// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.agent.event.ToolExecutionResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.roundToInt

enum class CropSourceSpace { FULL_DISPLAY, CURRENT_VIEW }

class ModelCoordinateMapper(
    private val scale: Float,
    private val modelSize: Pair<Int, Int>? = null,
    private val physicalSize: Pair<Int, Int>? = null,
    private val physicalOrigin: Point = Point(0, 0)
) {
    data class Point(val x: Int, val y: Int)
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun cropBounds(source: CropSourceSpace, bounds: Bounds, fullSize: Pair<Int, Int>): Bounds {
        val mapper = if (source == CropSourceSpace.FULL_DISPLAY)
            ModelCoordinateMapper(1f, fullSize, fullSize) else this
        return mapper.toPhysicalBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    sealed class MappingResult {
        data class Valid(val point: Point) : MappingResult()
        data class Invalid(val result: ToolExecutionResult) : MappingResult()
    }

    /**
     * Model actions use one aspect-ratio-independent 0..1000 coordinate space.
     * The attached frame dimensions remain rendering metadata only.
     */
    fun mapToPhysical(x: Int, y: Int): MappingResult {
        val model = modelSize
            ?: return MappingResult.Valid(toPhysical(x, y))
        if (x in 0..NORMALIZED_MAX && y in 0..NORMALIZED_MAX) {
            return MappingResult.Valid(toPhysical(x, y))
        }
        return MappingResult.Invalid(
            ToolProtocolResult.notExecuted(
                code = "COORDINATE_OUT_OF_RANGE",
                retryable = true,
                details = buildJsonObject {
                    put("x", JsonPrimitive(x))
                    put("y", JsonPrimitive(y))
                    put("allowed_min", JsonPrimitive(0))
                    put("allowed_max", JsonPrimitive(NORMALIZED_MAX))
                    put("frame_width", JsonPrimitive(model.first))
                    put("frame_height", JsonPrimitive(model.second))
                }
            )
        )
    }

    fun toPhysical(x: Int, y: Int): Point {
        val effectiveScale = scale.takeIf { it > 0f } ?: 1f
        val framePoint = modelSize
            ?.let { normalizedToFrame(x, y, it) }
            ?: Point(x, y)
        val rawX = physicalOrigin.x + (framePoint.x / effectiveScale).roundToInt()
        val rawY = physicalOrigin.y + (framePoint.y / effectiveScale).roundToInt()
        val size = physicalSize
        return if (size == null) {
            Point(rawX, rawY)
        } else {
            Point(
                rawX.coerceIn(0, (size.first - 1).coerceAtLeast(0)),
                rawY.coerceIn(0, (size.second - 1).coerceAtLeast(0))
            )
        }
    }

    /** 将物理屏幕坐标统一投影到模型可见的 0..1000 坐标系。 */
    fun toModel(x: Int, y: Int): Point {
        val effectiveScale = scale.takeIf { it > 0f } ?: 1f
        val rawX = ((x - physicalOrigin.x) * effectiveScale).roundToInt()
        val rawY = ((y - physicalOrigin.y) * effectiveScale).roundToInt()
        val size = modelSize
        return if (size == null) {
            Point(rawX, rawY)
        } else {
            Point(
                frameToNormalized(rawX, size.first),
                frameToNormalized(rawY, size.second)
            )
        }
    }

    fun toPhysicalBounds(left: Int, top: Int, right: Int, bottom: Int): Bounds {
        val effectiveScale = scale.takeIf { it > 0f } ?: 1f
        val frame = modelSize
        val frameLeft = frame?.let { normalizedBoundaryToFrame(left, it.first) } ?: left
        val frameTop = frame?.let { normalizedBoundaryToFrame(top, it.second) } ?: top
        val frameRight = frame?.let { normalizedBoundaryToFrame(right, it.first) } ?: right
        val frameBottom = frame?.let { normalizedBoundaryToFrame(bottom, it.second) } ?: bottom
        val rawLeft = physicalOrigin.x + (frameLeft / effectiveScale).roundToInt()
        val rawTop = physicalOrigin.y + (frameTop / effectiveScale).roundToInt()
        val rawRight = physicalOrigin.x + (frameRight / effectiveScale).roundToInt()
        val rawBottom = physicalOrigin.y + (frameBottom / effectiveScale).roundToInt()
        val size = physicalSize
        if (size == null) {
            return Bounds(rawLeft, rawTop, rawRight, rawBottom)
        }
        return Bounds(
            left = rawLeft.coerceIn(0, (size.first - 1).coerceAtLeast(0)),
            top = rawTop.coerceIn(0, (size.second - 1).coerceAtLeast(0)),
            right = rawRight.coerceIn(0, size.first.coerceAtLeast(0)),
            bottom = rawBottom.coerceIn(0, size.second.coerceAtLeast(0))
        )
    }

    companion object {
        const val NORMALIZED_MAX = 1000

        fun normalizedToFrame(x: Int, y: Int, frameSize: Pair<Int, Int>): Point = Point(
            normalizedPointToFrame(x, frameSize.first),
            normalizedPointToFrame(y, frameSize.second)
        )

        private fun normalizedPointToFrame(value: Int, frameLength: Int): Int {
            if (frameLength <= 1) return 0
            return (value.toFloat() * frameLength / NORMALIZED_MAX)
                .roundToInt()
                .coerceIn(0, frameLength - 1)
        }

        private fun normalizedBoundaryToFrame(value: Int, frameLength: Int): Int {
            if (frameLength <= 0) return 0
            return (value.toFloat() * frameLength / NORMALIZED_MAX)
                .roundToInt()
                .coerceIn(0, frameLength)
        }

        private fun frameToNormalized(value: Int, frameLength: Int): Int {
            if (frameLength <= 0) return 0
            return (value.toFloat() * NORMALIZED_MAX / frameLength)
                .roundToInt()
                .coerceIn(0, NORMALIZED_MAX)
        }

        fun identity(): ModelCoordinateMapper = ModelCoordinateMapper(1f, null)
    }
}
