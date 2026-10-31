// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.droidance.core.device.agent.device.CursorMotion
import com.droidance.core.device.agent.device.RobotHandPainter
import com.droidance.core.device.tools.InputAction

/** Presentation follows the input clock; there is no travel animation before a real gesture. */
class TouchIndicatorView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val handBitmap = HandCursorBitmap.of(context, (density * 36f).toInt())
    private val painter = RobotHandPainter()
    private var cue: VirtualSpaceHandCue? = null

    init { setWillNotDraw(false) }

    fun showCue(value: VirtualSpaceHandCue?) {
        cue = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = cue ?: return
        val action = current.action
        val swipe = action is InputAction.Swipe
        val targetX = when (action) {
            is InputAction.Tap -> action.x.toFloat()
            is InputAction.Swipe -> action.startX.toFloat()
        }
        val targetY = when (action) {
            is InputAction.Tap -> action.y.toFloat()
            is InputAction.Swipe -> action.startY.toFloat()
        }
        val endX = when (action) {
            is InputAction.Tap -> action.x.toFloat()
            is InputAction.Swipe -> action.endX.toFloat()
        }
        val endY = when (action) {
            is InputAction.Tap -> action.y.toFloat()
            is InputAction.Swipe -> action.endY.toFloat()
        }
        val fromX = if (current.travelDurationMs > 0L) current.fromX.toFloat() else targetX
        val fromY = if (current.travelDurationMs > 0L) current.fromY.toFloat() else targetY

        val motion = CursorMotion.sampleMotion(
            CursorMotion.nowMs(), current.startedAtMs, current.travelDurationMs, current.finishedAtMs,
            fromX, fromY, targetX, targetY, endX, endY, action.durationMs, swipe, density
        )
        if (motion.alpha <= 0f) return
        painter.draw(
            canvas, handBitmap, targetX, targetY, motion, ValueAnimator.areAnimatorsEnabled(), density
        )
        if (motion.animating) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        cue = null
        super.onDetachedFromWindow()
    }
}
