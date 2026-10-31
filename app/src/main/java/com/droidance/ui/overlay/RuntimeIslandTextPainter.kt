// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil
import kotlin.math.max

/** A centered, single-line title clipped to the same slot on both runtime surfaces. */
class RuntimeIslandTextPainter(context: Context) {
    private val metrics = context.resources.displayMetrics
    private val actionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(RuntimeIslandStyle.LABEL)
        textSize = RuntimeIslandStyle.LABEL_TEXT_SP * metrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fontMetrics = actionPaint.fontMetrics
    private val baselineOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f
    private var cachedAction: String? = null
    private var cachedWidth = Float.NaN
    private var displayedAction = ""
    private var textWidth = 0f
    fun heightPx(): Int = ceil(max(
        RuntimeIslandStyle.HEIGHT_DP * metrics.density,
        fontMetrics.descent - fontMetrics.ascent + 12f * RuntimeIslandStyle.SCALE * metrics.density
    )).toInt()

    fun draw(canvas: Canvas, bounds: RectF, action: String) {
        if (bounds.width() <= 0f) return
        val save = canvas.save()
        canvas.clipRect(bounds)
        if (cachedAction != action || cachedWidth != bounds.width()) {
            cachedAction = action
            cachedWidth = bounds.width()
            displayedAction = ellipsize(action, actionPaint, cachedWidth)
            textWidth = actionPaint.measureText(displayedAction)
        }
        val left = bounds.centerX() - textWidth / 2f
        val baseline = bounds.centerY() + baselineOffset
        canvas.drawText(displayedAction, left, baseline, actionPaint)
        canvas.restoreToCount(save)
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()
}
