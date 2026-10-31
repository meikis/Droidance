// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import kotlin.math.roundToInt

/**
 * 用户把运行浮层拖到的高度偏移，跨任务与 App 重启保留——挡住视线的通常是某个 App 的固定
 * 元素，下次还会挡，所以位置值得记住。
 *
 * 主屏运行岛与虚拟空间查看页控制岛记同一份：两处默认落点都从屏幕底部起算，挪一处等于挪
 * 同一个岛，回到另一处它还在同样的高度上。
 */
object RuntimeIslandPosition {
    const val KEY_BOTTOM_OFFSET = "island_bottom_offset_px"
    const val KEY_OFFSET_X = "island_offset_x_px"
    const val KEY_OFFSET_Y = "island_offset_y_px"

    private const val PREFS = "droidance_runtime_island_pos"

    fun defaultTopPx(screenHeightPx: Int, islandHeightPx: Int): Int =
        (screenHeightPx * (1f - RuntimeIslandStyle.BOTTOM_POSITION_FRACTION) - islandHeightPx / 2f)
            .roundToInt().coerceIn(0, (screenHeightPx - islandHeightPx).coerceAtLeast(0))

    fun read(context: Context): Int = prefs(context).getInt(KEY_OFFSET_Y, prefs(context).getInt(KEY_BOTTOM_OFFSET, 0))

    fun readX(context: Context): Int = prefs(context).getInt(KEY_OFFSET_X, 0)

    fun write(context: Context, offsetPx: Int) {
        prefs(context).edit()
            .putInt(KEY_BOTTOM_OFFSET, offsetPx)
            .putInt(KEY_OFFSET_Y, offsetPx)
            .apply()
    }

    fun write(context: Context, offsetX: Int, offsetY: Int) {
        prefs(context).edit()
            .putInt(KEY_OFFSET_X, offsetX)
            .putInt(KEY_BOTTOM_OFFSET, offsetY)
            .putInt(KEY_OFFSET_Y, offsetY)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
