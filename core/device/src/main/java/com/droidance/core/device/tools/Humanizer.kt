// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.tools

import com.droidance.core.agent.tools.*

import com.droidance.core.device.tools.*

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

object Humanizer {
    fun jitterXY(v: Int, sigma: Int = 12): Int =
        v + (Random.nextGaussian() * sigma).roundToInt()

    fun jitterDuration(ms: Int, ratio: Float = 0.22f): Int {
        val delta = (ms * ratio * Random.nextGaussian()).roundToInt()
        return (ms + delta).coerceAtLeast(50)
    }

    fun jitterSwipeEndpoint(v: Int, sigma: Int = 12): Int =
        v + (Random.nextGaussian() * sigma).roundToInt()

    fun swipePath(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        steps: Int = 12
    ): List<Pair<Int, Int>> {
        val dx = (x2 - x1).toFloat()
        val dy = (y2 - y1).toFloat()
        val ctrlOffsetX = (Random.nextGaussian() * dx * 0.10f).roundToInt()
        val ctrlOffsetY = (Random.nextGaussian() * dy * 0.10f).roundToInt()
        val ctrlRatio = 0.35f + Random.nextFloat() * 0.30f
        val cpx = (x1 + dx * ctrlRatio).roundToInt() + ctrlOffsetX
        val cpy = (y1 + dy * ctrlRatio).roundToInt() + ctrlOffsetY

        return (1..steps).map { i ->
            val t = i.toFloat() / steps
            val mt = 1 - t
            val bx = (mt * mt * x1 + 2 * mt * t * cpx + t * t * x2).roundToInt()
            val by = (mt * mt * y1 + 2 * mt * t * cpy + t * t * y2).roundToInt()
            val pointSigma = if (t in 0.2f..0.8f) 4 else 2
            Pair(jitterXY(bx, pointSigma), jitterXY(by, pointSigma))
        }
    }

    private fun Random.nextGaussian(): Double {
        val u1 = nextDouble()
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1.coerceAtLeast(1e-10))) * cos(2.0 * PI * u2)
    }
}
