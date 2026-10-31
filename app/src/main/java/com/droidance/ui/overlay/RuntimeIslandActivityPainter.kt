// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient

/** Shared execution cues for the bitmap island and the native viewer. */
class RuntimeIslandActivityPainter {
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rotation = Matrix()
    private val shader = SweepGradient(0f, 0f,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x22FFFFFF, 0x99FFFFFF.toInt(), Color.WHITE, Color.TRANSPARENT),
        floatArrayOf(0f, .63f, .74f, .90f, .97f, 1f))

    fun drawOrbit(canvas: Canvas, centerX: Float, centerY: Float, density: Float, elapsedMs: Long) {
        rotation.setRotate((elapsedMs % ORBIT_PERIOD_MS) * 360f / ORBIT_PERIOD_MS)
        rotation.postTranslate(centerX, centerY)
        shader.setLocalMatrix(rotation)
        orbitPaint.shader = shader
        orbitPaint.strokeWidth = density * RuntimeIslandStyle.SCALE
        canvas.drawCircle(centerX, centerY,
            (RuntimeIslandStyle.CHIP_SIZE_DP / 2f + 2f * RuntimeIslandStyle.SCALE) * density, orbitPaint)
    }

    companion object {
        // Divides the cached eight-second mascot cycle for seamless loop boundaries.
        const val ORBIT_PERIOD_MS = 2_000L
    }
}
