// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.WindowManager

/**
 * 屏幕物理四角 R 角（圆角半径）参数模型。
 *
 * 坐标系与 Android Path 一致：顺时针依次为左上、右上、右下、左下。
 */
data class DeviceCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float
) {
    /**
     * 生成供 [android.graphics.Path.addRoundRect] 使用的 8 元素半径数组：
     * [tlX, tlY, trX, trY, brX, brY, blX, blY]。
     */
    fun toPathRadii(): FloatArray = floatArrayOf(
        topLeft, topLeft,
        topRight, topRight,
        bottomRight, bottomRight,
        bottomLeft, bottomLeft
    )

    fun isAllZero(): Boolean =
        topLeft <= 0f && topRight <= 0f && bottomRight <= 0f && bottomLeft <= 0f

    companion object {
        fun uniform(radius: Float): DeviceCornerRadii =
            DeviceCornerRadii(radius, radius, radius, radius)

        val ZERO = uniform(0f)
    }
}

object DeviceCornerRadius {
    /** 默认保底圆角（38dp），与原有视觉规范保持一致 */
    const val DEFAULT_FALLBACK_RADIUS_DP = 38f

    /**
     * 解析当前设备的屏幕物理四角 R 角。
     *
     * 策略：
     * 1. 优先使用 Android 12+ (API 31+) 官方 [WindowInsets.getRoundedCorner] 提取各角像素半径；
     * 2. 低版本或 Insets 无圆角信息时，尝试读取系统内部资源（rounded_corner_radius / top / bottom）；
     * 3. 若均未获取到有效信息，回退到 [fallbackRadiusPx]（默认 38dp）。
     *
     * 如果设备明确为直角屏（API 31+ 提供了 Insets 且各角 radius 为 0），将忠实返回 0f 直角。
     */
    fun resolve(
        context: Context,
        insets: WindowInsets? = null,
        fallbackRadiusPx: Float = context.resources.displayMetrics.density * DEFAULT_FALLBACK_RADIUS_DP
    ): DeviceCornerRadii {
        val windowInsets = insets ?: resolveWindowInsetsFromContext(context)

        // 1. Android 12+ (API 31+) 官方 WindowInsets.getRoundedCorner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowInsets != null) {
            val tl = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            val tr = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
            val br = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
            val bl = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)

            if (tl != null || tr != null || br != null || bl != null) {
                return DeviceCornerRadii(
                    topLeft = tl?.radius?.toFloat() ?: 0f,
                    topRight = tr?.radius?.toFloat() ?: 0f,
                    bottomRight = br?.radius?.toFloat() ?: 0f,
                    bottomLeft = bl?.radius?.toFloat() ?: 0f
                )
            }
        }

        // 2. 尝试读取系统内部资源 dimen
        val topDimen = getSystemDimenPx(context, "rounded_corner_radius_top")
        val bottomDimen = getSystemDimenPx(context, "rounded_corner_radius_bottom")
        val generalDimen = getSystemDimenPx(context, "rounded_corner_radius")

        if (topDimen != null || bottomDimen != null || generalDimen != null) {
            val top = (topDimen ?: generalDimen ?: 0f)
            val bottom = (bottomDimen ?: generalDimen ?: 0f)
            if (top > 0f || bottom > 0f) {
                return DeviceCornerRadii(
                    topLeft = top,
                    topRight = top,
                    bottomRight = bottom,
                    bottomLeft = bottom
                )
            }
        }

        // 3. 保底
        return DeviceCornerRadii.uniform(fallbackRadiusPx)
    }

    private fun resolveWindowInsetsFromContext(context: Context): WindowInsets? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val wm = context.getSystemService(WindowManager::class.java)
            wm?.currentWindowMetrics?.windowInsets ?: wm?.maximumWindowMetrics?.windowInsets
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSystemDimenPx(context: Context, resName: String): Float? {
        val resId = context.resources.getIdentifier(resName, "dimen", "android")
        return if (resId > 0) {
            try {
                context.resources.getDimensionPixelSize(resId).toFloat()
            } catch (_: Throwable) {
                null
            }
        } else null
    }
}
