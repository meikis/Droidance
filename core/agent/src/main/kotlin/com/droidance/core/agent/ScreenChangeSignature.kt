// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent

/**
 * Downsampled luminance of one frame. The 64-bit visual fingerprint identifies an approximate
 * viewport; it cannot see a localized content change such as a digit appearing in a display.
 * This signature measures how much of the frame changed so a visible effect is not mistaken
 * for a stationary repeat.
 */
class ScreenChangeSignature(val width: Int, val height: Int, private val luminance: ByteArray) {
    init {
        require(width > 0 && height > 0 && luminance.size == width * height)
    }

    /** Fraction of samples whose luminance moved by more than [channelDelta]; null for another geometry. */
    fun changedFraction(other: ScreenChangeSignature, channelDelta: Int): Double? {
        if (other.width != width || other.height != height) return null
        var changed = 0
        for (index in luminance.indices) {
            val delta = (luminance[index].toInt() and 0xff) - (other.luminance[index].toInt() and 0xff)
            if (kotlin.math.abs(delta) > channelDelta) changed++
        }
        return changed.toDouble() / luminance.size
    }

    /**
     * Lines along one axis whose samples inside the band [crossStart, crossEnd] changed by more
     * than [stillDelta] on average. Content outside the band, such as a blinking caret in another
     * field, is not counted. Null for another geometry or an empty band.
     */
    fun changedLinesInBand(after: ScreenChangeSignature, vertical: Boolean, crossStart: Int, crossEnd: Int,
                           stillDelta: Double, alongStart: Int = 0,
                           alongEnd: Int = (if (vertical) height else width) - 1): Int? {
        if (after.width != width || after.height != height) return null
        val alongSize = if (vertical) height else width
        val crossSize = if (vertical) width else height
        val cross = crossStart.coerceIn(0, crossSize - 1)..crossEnd.coerceIn(0, crossSize - 1)
        if (cross.isEmpty()) return null
        fun at(frame: ByteArray, a: Int, c: Int) = frame[if (vertical) a * width + c else c * width + a].toInt() and 0xff
        return (alongStart.coerceIn(0, alongSize - 1)..alongEnd.coerceIn(0, alongSize - 1)).count { a ->
            cross.sumOf { c -> kotlin.math.abs(at(after.luminance, a, c) - at(luminance, a, c)) }.toDouble() /
                (cross.last - cross.first + 1) > stillDelta
        }
    }
}
