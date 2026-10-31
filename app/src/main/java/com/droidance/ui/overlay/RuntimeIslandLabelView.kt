// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.content.ContextCompat
import com.droidance.R

/** The viewer uses the same action label painter as the external bitmap island. */
class RuntimeIslandLabelView(context: Context) : View(context) {
    private val painter = RuntimeIslandTextPainter(context)
    private val bounds = RectF()
    private var action = ""

    fun setLabel(action: String, executing: Boolean) {
        this.action = action
        contentDescription = action
        ViewCompat.setStateDescription(this, if (executing) ContextCompat.getString(context, R.string.runtime_island_executing) else null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        painter.draw(canvas, bounds, action)
    }
}
