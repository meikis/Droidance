// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

/**
 * 虚拟空间镜像面板的梦幻流光边缘 View（Siri / 小布助手风格）。
 *
 * - 紧贴屏幕物理边缘的圆角描边，动态适配宿主设备屏幕物理 R 角；
 * - 两层 BlurMaskFilter 柔光以不同速度反向流动，色相错开的渐变相互晕染，产生持续变换的氛围光；
 * - 最上层一条细亮边勾勒轮廓，整体低透明度并叠加缓慢呼吸；
 * - 不处理触摸，事件穿透给下层镜像画面。
 */
class AuroraEdgeView(context: Context) : View(context) {
    private var lastDrawAtMs = 0L
    private val dp = context.resources.displayMetrics.density

    private val painter = com.droidance.core.device.agent.device.AuroraEdgePainter(dp)

    var cornerRadii: DeviceCornerRadii = DeviceCornerRadius.resolve(context)
        private set

    private var phase = 0f

    private companion object {
        /** 装饰流光约 30fps，避免在 120Hz 主屏上做无收益的全屏重绘。 */
        const val ANIMATION_FRAME_INTERVAL_MS = com.droidance.core.device.agent.device.AuroraEdgePainter.FRAME_INTERVAL_MS
    }

    // 一个周期内主环转两圈、副环反向一圈、呼吸整数次，重启无跳变
    private val flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = com.droidance.core.device.agent.device.AuroraEdgePainter.CYCLE_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastDrawAtMs >= ANIMATION_FRAME_INTERVAL_MS) {
                lastDrawAtMs = now
                invalidate()
            }
        }
    }

    init {
        alpha = 0f
    }

    /** 更新设备屏幕 Insets 并动态适配四角 R 角 */
    fun updateWindowInsets(insets: WindowInsets?) {
        val resolved = DeviceCornerRadius.resolve(context, insets)
        if (resolved != cornerRadii) {
            cornerRadii = resolved
            updatePath()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            updateWindowInsets(rootWindowInsets)
        } else {
            updateWindowInsets(null)
        }
        flowAnimator.start()
        animate().alpha(1f).setDuration(com.droidance.core.device.agent.device.AuroraEdgePainter.FADE_IN_MS).start()
    }

    override fun onDetachedFromWindow() {
        flowAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateWindowInsets(insets)
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && rootWindowInsets != null) {
            cornerRadii = DeviceCornerRadius.resolve(context, rootWindowInsets)
        }
        updatePath()
    }

    private fun updatePath() {
        if (width <= 0 || height <= 0) return
        painter.resize(width, height, cornerRadii.toPathRadii())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        painter.draw(canvas, phase, 1f)
    }
}
