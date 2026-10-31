// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.mascot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.droidance.R

/** One articulated character for Compose and the shell island: body, head and LED face. */
class MascotPainter(context: Context) {
    // The same neutral-white material is used on app pages and dark runtime islands.
    private val head = MascotSprites.load(context, R.drawable.droidance_mascot_head)
    private val body = MascotSprites.load(context, R.drawable.droidance_mascot_body)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 248, 246)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val facePath = Path()
    private val headBounds = RectF()
    private val bodyBounds = RectF()

    fun draw(canvas: Canvas, bounds: RectF, framing: MascotFraming, pose: MascotPose) {
        canvas.save()
        canvas.clipRect(bounds)
        val unit = minOf(bounds.width(), bounds.height()) / 100f
        canvas.translate(bounds.centerX() - unit * 50f, bounds.centerY() - unit * 50f)
        canvas.scale(unit, unit)
        if (framing == MascotFraming.FULL) {
            // Reserve overscan for the antenna throughout head turns and lifted steps.
            canvas.scale(.90f, .90f, 50f, 50f)
            canvas.translate(pose.sway, -pose.lift)
            canvas.rotate(pose.bodyTilt, 50f, 90f)
            canvas.scale(1f - pose.squash * .5f, 1f + pose.squash, 50f, 90f)
        }
        when (framing) {
            MascotFraming.FULL -> headBounds.set(17.5f, -3f, 82.5f, 62f)
            MascotFraming.PORTRAIT -> headBounds.set(0f, -6f, 100f, 94f)
            MascotFraming.HEAD -> headBounds.set(-3f, -5f, 103f, 101f)
        }
        if (framing != MascotFraming.HEAD) {
            canvas.save()
            canvas.scale(1f + pose.breath * 0.003f, 1f + pose.breath * 0.007f, 50f, 94f)
            if (framing == MascotFraming.FULL) bodyBounds.set(17.5f, 40f, 82.5f, 105f)
            else bodyBounds.set(5f, 59f, 95f, 149f)
            canvas.drawBitmap(body, null, bodyBounds, bitmapPaint)
            canvas.restore()
        }
        canvas.save()
        canvas.translate(0f, pose.nod * 1.2f + pose.breath * 0.22f)
        canvas.rotate(pose.headTilt, headBounds.centerX(), headBounds.bottom - headBounds.height() * 0.12f)
        canvas.drawBitmap(head, null, headBounds, bitmapPaint)
        drawFace(canvas, headBounds, pose)
        canvas.restore()
        canvas.restore()
    }

    private fun drawFace(canvas: Canvas, head: RectF, pose: MascotPose) {
        fun x(v: Float) = head.left + head.width() * (v + pose.gazeX)
        fun y(v: Float) = head.top + head.height() * (v + pose.gazeY)
        for (eye in 0..1) {
            val cx = if (eye == 0) 0.365f else 0.635f
            facePath.reset()
            facePath.moveTo(x(cx - 0.047f), y(0.671f))
            facePath.cubicTo(x(cx - 0.039f), y(0.671f - 0.080f * pose.eyeOpen),
                x(cx + 0.039f), y(0.671f - 0.080f * pose.eyeOpen), x(cx + 0.047f), y(0.671f))
            drawLedPath(canvas, head.width() * 0.025f)
        }
        facePath.reset()
        facePath.moveTo(x(0.467f), y(0.733f))
        val smileY = 0.733f + 0.028f * pose.smile
        facePath.cubicTo(x(0.483f), y(smileY), x(0.517f), y(smileY), x(0.533f), y(0.733f))
        drawLedPath(canvas, head.width() * 0.016f)
    }

    /** Subtle screen emission, without a raised rim or a cast shadow on the glass. */
    private fun drawLedPath(canvas: Canvas, strokeWidth: Float) {
        // Concentric low-opacity strokes also work on the hardware Compose canvas.
        facePaint.strokeWidth = strokeWidth * 2.2f
        facePaint.alpha = 8
        canvas.drawPath(facePath, facePaint)
        facePaint.strokeWidth = strokeWidth * 1.5f
        facePaint.alpha = 22
        canvas.drawPath(facePath, facePaint)
        facePaint.strokeWidth = strokeWidth
        facePaint.alpha = 242
        canvas.drawPath(facePath, facePaint)
    }
}
