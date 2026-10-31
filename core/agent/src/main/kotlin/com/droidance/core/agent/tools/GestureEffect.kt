// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.ScreenChangeSignature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reports a single drag or swipe whose content did not move between the decision frame and the
 * settled frame, such as a list or picker already at its end in that direction. Elsewhere on the
 * screen pixels may still change, for example a blinking caret, so the general receipt alone
 * reads as a change. Only the band under the gesture is compared; how far content moved is read
 * from the frame, because evenly spaced rows make a measured distance ambiguous. A repeat in the
 * same direction over the same unchanged area cannot change the screen and is recognized here so
 * the runtime can withhold it. These are device facts about the gestured area, not evidence of
 * goal progress.
 */
object GestureEffect {
    private val GESTURES = setOf("drag", "swipe")
    private val PASSIVE = setOf("wait", "screenshot")
    /** Half of the band across the gesture, as a fraction of the frame. */
    private const val BAND_HALF_FRACTION = 0.06
    /** Mean luminance change per sample below which a line counts as unchanged. */
    private const val STILL_DELTA = 4.0
    /** Fewer changed lines than this inside the band means the content stayed put. */
    private const val MOVED_LINES = 2

    private data class Gesture(val op: String, val startX: Int, val startY: Int, val endX: Int, val endY: Int)

    private fun gesture(arguments: String): Gesture? {
        val actions = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
            ?.get("actions") as? JsonArray ?: return null
        val objects = actions.map { it as? JsonObject ?: return null }
        val active = objects.filter { it["op"]?.jsonPrimitive?.contentOrNull !in PASSIVE }
        val action = active.singleOrNull() ?: return null
        val op = action["op"]?.jsonPrimitive?.contentOrNull?.takeIf { it in GESTURES } ?: return null
        fun value(name: String) = action[name]?.jsonPrimitive?.intOrNull
        return Gesture(op, value("startX") ?: return null, value("startY") ?: return null,
            value("endX") ?: return null, value("endY") ?: return null)
    }

    /** Drags observe their own repetitions; only a lone swipe uses the group-level guard. */
    fun applies(arguments: String): Boolean = gesture(arguments)?.op == "swipe"

    /** Pixel change around the dragged rows, not a measured displacement or selected value. */
    fun changed(arguments: String, before: ScreenChangeSignature, after: ScreenChangeSignature): Boolean? {
        val g = gesture(arguments) ?: return null
        val vertical = kotlin.math.abs(g.endY - g.startY) >= kotlin.math.abs(g.endX - g.startX)
        val (crossStart, crossEnd) = band(g, if (vertical) before.width else before.height)
        val start = if (vertical) g.startY else g.startX
        val end = if (vertical) g.endY else g.endX
        val margin = kotlin.math.abs(end - start) / 2
        val size = if (vertical) before.height else before.width
        return before.changedLinesInBand(after, vertical, crossStart, crossEnd, STILL_DELTA,
            (minOf(start, end) - margin) * size / 1000,
            (maxOf(start, end) + margin) * size / 1000)?.let { it >= MOVED_LINES }
    }

    /** A gesture whose content stayed put, kept to recognize an immediate repeat on the same view. */
    class StillGesture internal constructor(
        val op: String, val vertical: Boolean, val towardStart: Boolean,
        internal val crossStart: Int, internal val crossEnd: Int,
        internal val alongMin: Int, internal val alongMax: Int,
        internal val after: ScreenChangeSignature
    ) {
        /** Direction the finger moved, in screen terms. */
        val fingerDirection: String get() = direction(vertical, towardStart)
        /** The only direction that can still bring other content into view under this area. */
        val oppositeDirection: String get() = direction(vertical, !towardStart)
    }

    private fun direction(vertical: Boolean, towardStart: Boolean) =
        if (vertical) (if (towardStart) "up" else "down") else (if (towardStart) "left" else "right")

    private fun band(g: Gesture, crossSize: Int): Pair<Int, Int> {
        val vertical = kotlin.math.abs(g.endY - g.startY) >= kotlin.math.abs(g.endX - g.startX)
        val crossCenter = ((if (vertical) g.startX + g.endX else g.startY + g.endY) / 2.0 * crossSize / 1000).toInt()
        val half = maxOf(2, (crossSize * BAND_HALF_FRACTION).toInt())
        return crossCenter - half to crossCenter + half
    }

    /** The gesture in [arguments] when content inside its band did not change between the frames. */
    fun still(arguments: String, before: ScreenChangeSignature, after: ScreenChangeSignature): StillGesture? {
        val g = gesture(arguments) ?: return null
        val dx = g.endX - g.startX
        val dy = g.endY - g.startY
        val vertical = kotlin.math.abs(dy) >= kotlin.math.abs(dx)
        val (crossStart, crossEnd) = band(g, if (vertical) before.width else before.height)
        val changed = before.changedLinesInBand(after, vertical, crossStart, crossEnd, STILL_DELTA) ?: return null
        if (changed >= MOVED_LINES) return null
        val along = if (vertical) listOf(g.startY, g.endY) else listOf(g.startX, g.endX)
        return StillGesture(g.op, vertical, (if (vertical) dy else dx) < 0, crossStart, crossEnd,
            along.min(), along.max(), after)
    }

    fun describe(still: StillGesture): String =
        "[GESTURE_EFFECT] ${still.op} moved the finger ${still.fingerDirection}, but content under it did not move: " +
            "it is already at its end in that direction or did not respond. Moving the finger " +
            "${still.fingerDirection} here again changes nothing; other content can only come from moving it " +
            "${still.oppositeDirection}, or from another control."

    /**
     * Whether [arguments] repeats [still] in the same direction over most of the same stretch of
     * the same band, while that band still shows exactly what the stalled gesture left.
     */
    fun repeats(still: StillGesture, arguments: String, current: ScreenChangeSignature): Boolean {
        val g = gesture(arguments) ?: return false
        val dx = g.endX - g.startX
        val dy = g.endY - g.startY
        val vertical = kotlin.math.abs(dy) >= kotlin.math.abs(dx)
        if (vertical != still.vertical || ((if (vertical) dy else dx) < 0) != still.towardStart) return false
        val (crossStart, crossEnd) = band(g, if (vertical) current.width else current.height)
        if (crossEnd < still.crossStart || crossStart > still.crossEnd) return false
        val along = if (vertical) listOf(g.startY, g.endY) else listOf(g.startX, g.endX)
        val overlap = minOf(along.max(), still.alongMax) - maxOf(along.min(), still.alongMin)
        if (overlap * 2 < along.max() - along.min()) return false
        val changed = still.after.changedLinesInBand(current, vertical, still.crossStart, still.crossEnd, STILL_DELTA)
            ?: return false
        return changed < MOVED_LINES
    }
}
