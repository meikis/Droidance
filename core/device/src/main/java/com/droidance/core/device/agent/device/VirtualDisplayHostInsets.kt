// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.agent.device

import com.droidance.core.agent.device.*

import com.droidance.core.device.agent.device.*

import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager

/** Stable bar geometry excludes the IME and survives opening/closing the preview Activity. */
object VirtualDisplayHostInsets {
    fun read(windowManager: WindowManager): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val metrics = windowManager.maximumWindowMetrics
        val insets = metrics.windowInsets
        fun sides(type: Int): IntArray = insets.getInsetsIgnoringVisibility(type).let {
            intArrayOf(it.left, it.top, it.right, it.bottom)
        }
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        return Bundle().apply {
            putInt("width", metrics.bounds.width())
            putInt("height", metrics.bounds.height())
            putInt("rotation", rotation)
            putIntArray("status", sides(WindowInsets.Type.statusBars()))
            putIntArray("navigation", sides(WindowInsets.Type.navigationBars()))
            putIntArray("cutout", sides(WindowInsets.Type.displayCutout()))
        }
    }

    fun signature(geometry: Bundle): String = listOf(
        geometry.getInt("width"), geometry.getInt("height"), geometry.getInt("rotation"),
        geometry.getIntArray("status")?.contentToString(),
        geometry.getIntArray("navigation")?.contentToString(),
        geometry.getIntArray("cutout")?.contentToString()
    ).joinToString(":")
}
