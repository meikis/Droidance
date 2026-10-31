// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.debug

import com.droidance.core.agent.debug.*
import com.droidance.core.device.debug.*

import com.droidance.debug.*

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.droidance.core.device.agent.device.CursorMotion
import com.droidance.ui.overlay.TouchIndicatorView
import org.json.JSONObject
import java.io.File

/** Isolated, reversible touch target for device acceptance. Never starts an agent task. */
class MascotGestureProbeActivity : Activity() {
    lateinit var pointer: TouchIndicatorView
    var downs = 0; private set
    var moves = 0; private set
    var ups = 0; private set
    var firstDownAtMs = 0L; private set
    var lastUpAtMs = 0L; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pointer = TouchIndicatorView(this)
        val target = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private var x = 0f
            private var y = 0f
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(243, 240, 250))
                paint.color = Color.rgb(133, 107, 189)
                canvas.drawCircle(if (x == 0f) width / 2f else x, if (y == 0f) height / 2f else y,
                    24f * resources.displayMetrics.density, paint)
                paint.color = Color.DKGRAY
                paint.textSize = 18f * resources.displayMetrics.scaledDensity
                canvas.drawText("点按 $downs · 滑动 $moves · 抬起 $ups", 24f, 80f, paint)
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                x = event.x; y = event.y
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downs++; if (firstDownAtMs == 0L) firstDownAtMs = CursorMotion.nowMs() }
                    MotionEvent.ACTION_MOVE -> moves++
                    MotionEvent.ACTION_UP -> { ups++; lastUpAtMs = CursorMotion.nowMs() }
                }
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    File(filesDir, "mascot-input-${display!!.displayId}.json").writeText(JSONObject()
                        .put("displayId", display!!.displayId).put("downs", downs).put("moves", moves)
                        .put("ups", ups).put("firstDownAtMs", firstDownAtMs).put("lastUpAtMs", lastUpAtMs)
                        .put("lastX", x).put("lastY", y).toString())
                }
                return true
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(target, FrameLayout.LayoutParams(-1, -1))
            addView(pointer, FrameLayout.LayoutParams(-1, -1))
        })
    }
}
