// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

/** Sample app pixels outside the mirrored navigation decoration, without changing its geometry. */
internal data class ViewerSystemBarColors(val status: Int, val navigation: Int) {
    companion object {
        fun samplePoints(width: Int, height: Int, topInset: Int, navigationInset: Int): List<Pair<Int, Int>> {
            require(width > 0 && height > 0)
            val xs = listOf(.12f, .25f, .75f, .88f).map { (width * it).toInt().coerceIn(0, width - 1) }
            val top = (topInset.coerceAtLeast(1) / 2).coerceIn(0, height - 1)
            // IME height is deliberately absent: only the host navigation bar owns this band.
            val bottom = (height - navigationInset.coerceAtLeast(0) - 2).coerceIn(0, height - 1)
            return xs.map { it to top } + xs.map { it to bottom }
        }

        fun fromSamples(colors: IntArray): ViewerSystemBarColors {
            require(colors.size == 8)
            fun average(start: Int): Int {
                fun channel(shift: Int) = (start until start + 4).sumOf { (colors[it] ushr shift) and 255 } / 4
                return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
            return ViewerSystemBarColors(average(0), average(4))
        }

        fun isLight(color: Int, previouslyLight: Boolean?): Boolean {
            val luminance = .299f * ((color ushr 16) and 255) + .587f * ((color ushr 8) and 255) + .114f * (color and 255)
            return luminance >= if (previouslyLight == true) 130f else 165f
        }
    }
}
