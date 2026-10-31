// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import android.content.res.Resources
import java.util.Random

class HumanGesturePlanner(
    private val resources: Resources,
    private val rng: Random = Random()
) {
    /** Physical-pixel bounds for a grounded scrollable pane. */
    data class SwipeRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    fun durationForSpeed(speed: String): Int {
        return when (speed) {
            "slow" -> 700 + rng.nextInt(300)
            "normal" -> 350 + rng.nextInt(200)
            "fast" -> 150 + rng.nextInt(150)
            else -> 350 + rng.nextInt(200)
        }
    }

    fun safeSwipe(
        direction: String,
        ratio: Float = 0.45f,
        region: SwipeRegion? = null
    ): IntArray {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val bounds = region?.let {
            SwipeRegion(
                left = it.left.coerceIn(0, (w - 1).coerceAtLeast(0)),
                top = it.top.coerceIn(0, (h - 1).coerceAtLeast(0)),
                right = it.right.coerceIn(1, w),
                bottom = it.bottom.coerceIn(1, h)
            )
        }?.takeIf { it.right - it.left >= 4 && it.bottom - it.top >= 4 }
            ?: SwipeRegion(0, 0, w, h)
        val regionWidth = (bounds.right - bounds.left).coerceAtLeast(1)
        val regionHeight = (bounds.bottom - bounds.top).coerceAtLeast(1)
        val cx = (bounds.left + regionWidth / 2 +
            ((rng.nextFloat() - 0.5f) * regionWidth * 0.16f).toInt())
            .coerceIn(bounds.left, (bounds.right - 1).coerceAtLeast(bounds.left))
        val safeTop = (bounds.top + regionHeight * (0.12f + rng.nextFloat() * 0.04f)).toInt()
            .coerceIn(bounds.top, (bounds.bottom - 1).coerceAtLeast(bounds.top))
        val safeBottom = (bounds.bottom - regionHeight * (0.25f - rng.nextFloat() * 0.03f)).toInt()
            .coerceIn((safeTop + 1).coerceAtMost(bounds.bottom), bounds.bottom)
        val safeHeight = safeBottom - safeTop
        val cy = safeTop + safeHeight / 2 + ((rng.nextFloat() - 0.5f) * safeHeight * 0.15f).toInt()
        val jitter = 1f + (rng.nextFloat() - 0.5f) * 0.2f
        val duration = 300 + rng.nextInt(250)

        return when (direction) {
            "down" -> verticalSwipe(cx, cy, safeTop, safeBottom, safeHeight, ratio, jitter, duration, upwards = true, w = regionWidth)
            "up" -> verticalSwipe(cx, cy, safeTop, safeBottom, safeHeight, ratio, jitter, duration, upwards = false, w = regionWidth)
            "left" -> horizontalSwipe(cx, cy, bounds.left, bounds.right, ratio, jitter, duration, leftToRight = true)
            "right" -> horizontalSwipe(cx, cy, bounds.left, bounds.right, ratio, jitter, duration, leftToRight = false)
            else -> intArrayOf(cx, cy, cx, cy, duration)
        }
    }

    private fun verticalSwipe(
        cx: Int,
        cy: Int,
        safeTop: Int,
        safeBottom: Int,
        safeHeight: Int,
        ratio: Float,
        jitter: Float,
        duration: Int,
        upwards: Boolean,
        w: Int
    ): IntArray {
        val travel = (safeHeight * ratio * jitter).toInt()
        val sy = if (upwards) (cy + travel / 2).coerceAtMost(safeBottom) else (cy - travel / 2).coerceAtLeast(safeTop)
        val ey = if (upwards) (sy - travel).coerceAtLeast(safeTop) else (sy + travel).coerceAtMost(safeBottom)
        return intArrayOf(cx, sy, cx + ((rng.nextFloat() - 0.5f) * w * 0.03f).toInt(), ey, duration)
    }

    private fun horizontalSwipe(
        cx: Int,
        cy: Int,
        left: Int,
        right: Int,
        ratio: Float,
        jitter: Float,
        duration: Int,
        leftToRight: Boolean
    ): IntArray {
        val width = (right - left).coerceAtLeast(1)
        val safeLeft = (left + width * 0.08f).toInt()
        val safeRight = (right - width * 0.08f).toInt()
        val travel = ((safeRight - safeLeft) * ratio * jitter).toInt()
        val sx = if (leftToRight) (cx - travel / 2).coerceAtLeast(safeLeft) else (cx + travel / 2).coerceAtMost(safeRight)
        val ex = if (leftToRight) (sx + travel).coerceAtMost(safeRight) else (sx - travel).coerceAtLeast(safeLeft)
        return intArrayOf(sx, cy, ex, cy, duration)
    }
}
