// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.droidance.ui.mascot.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** App 进程负责排版，sidecar 只合成像素，避免 raw app_process 依赖字体运行时。 */
@Singleton
class RuntimeSurfaceBitmapRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 岛内主按钮的语义：决定画哪个图标（打开镜像 / 接管主屏 / 把控制交回 agent）。 */
    enum class IslandAction { OPEN, TAKEOVER, RESUME, NONE }

    data class IslandBitmap(
        val png: ByteArray,
        val topOffsetPx: Int,
        val widthPx: Int,
        val heightPx: Int,
        val stopButtonLeftPx: Int
    )

    internal data class IslandLayout(
        val widthPx: Int,
        val heightPx: Int,
        val paddingPx: Int,
        val stopButtonLeftPx: Int
    )

    private val metrics = context.resources.displayMetrics
    private val density = metrics.density
    private val pointerPng: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        renderPointerBitmap()
    }
    private val mascotPainter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MascotPainter(context) }
    private val textPainter = RuntimeIslandTextPainter(context)
    private val activityPainter = RuntimeIslandActivityPainter()
    private val spaceTogglePainter = RuntimeIslandStyle.SpaceTogglePainter()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RuntimeIslandStyle.fillColor() }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(RuntimeIslandStyle.HAIRLINE)
        style = Paint.Style.STROKE
        strokeWidth = dpFloat(0.7f)
    }
    private val pillBounds = RectF()
    private val hairlineBounds = RectF()
    private val labelBounds = RectF()
    private val mascotBounds = RectF()

    internal fun islandLayout(): IslandLayout {
        val padding = dp(ISLAND_SHADOW_PADDING_DP)
        val height = max(dp(RuntimeIslandStyle.HEIGHT_DP), textPainter.heightPx())
        val width = islandWidthPx()
        val stopLeft = width - dp(RuntimeIslandStyle.EDGE_PADDING_DP) - dp(RuntimeIslandStyle.CHIP_SIZE_DP)
        return IslandLayout(width + padding * 2, height + padding * 2, padding,
            stopButtonLeftPx = stopLeft + padding)
    }

    /**
     * A static, single-line task label, ellipsized to its available width.
     * The label is clipped inside its slot so buttons and mascot never move or overlap it.
     */
    @Synchronized
    fun renderIsland(
        currentActionText: String,
        action: IslandAction,
        paused: Boolean,
        state: RuntimeIslandPresentation.State,
        phase: Float
    ): IslandBitmap {
        val layout = islandLayout()
        val bitmap = Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888)
        drawIsland(Canvas(bitmap), layout, currentActionText, action, paused, state, phase)
        return IslandBitmap(
            png = bitmap.toPng().also { bitmap.recycle() },
            topOffsetPx = RuntimeIslandPosition.defaultTopPx(screenHeightPx(), layout.heightPx),
            widthPx = layout.widthPx,
            heightPx = layout.heightPx,
            stopButtonLeftPx = layout.stopButtonLeftPx
        )
    }

    /** Bitmap exports and the live host share exactly the same static and animated layers. */
    @Synchronized
    internal fun drawIsland(
        canvas: Canvas,
        layout: IslandLayout,
        currentActionText: String,
        action: IslandAction,
        paused: Boolean,
        state: RuntimeIslandPresentation.State,
        phase: Float
    ) {
        val executing = state == RuntimeIslandPresentation.State.EXECUTING && !paused
        val motionEnabled = RuntimeIslandAnimations.areEnabled(context)
        drawIslandStatic(canvas, layout, currentActionText, action, paused)
        drawIslandActivity(canvas, layout, action, executing, motionEnabled,
            if (motionEnabled) (phase * MascotMotion.CYCLE_MS).toLong() else 0L)
    }

    /** Re-record only when the layout, label or controls change. */
    @Synchronized
    internal fun drawIslandStatic(
        canvas: Canvas,
        layout: IslandLayout,
        currentActionText: String,
        action: IslandAction,
        paused: Boolean
    ) {
        val padding = layout.paddingPx
        val height = layout.heightPx - padding * 2
        val chipHeight = dp(RuntimeIslandStyle.CHIP_SIZE_DP)
        val gap = dp(RuntimeIslandStyle.CHIP_GAP_DP)
        val pillWidth = layout.widthPx - padding * 2
        val stopLeft = layout.stopButtonLeftPx - padding
        val hasActionChip = action != IslandAction.NONE
        val actionLeft = if (hasActionChip) stopLeft - gap - chipHeight else stopLeft
        val spaceToggleWidth = if (action == IslandAction.OPEN) RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP else 0f
        val labelLeft = dp(RuntimeIslandStyle.LABEL_INSET_DP + spaceToggleWidth)
        val save = canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        pillBounds.set(0f, 0f, pillWidth.toFloat(), height.toFloat())
        val radius = height / 2f
        canvas.drawRoundRect(pillBounds, radius, radius, pillPaint)
        if (action == IslandAction.OPEN) {
            spaceTogglePainter.draw(canvas, density, height / 2f, expanded = false)
        }
        hairlineBounds.set(pillBounds)
        hairlineBounds.inset(hairlinePaint.strokeWidth / 2f, hairlinePaint.strokeWidth / 2f)
        canvas.drawRoundRect(hairlineBounds, radius, radius, hairlinePaint)
        labelBounds.set(labelLeft.toFloat(), 0f, (actionLeft - gap).toFloat(), height.toFloat())
        textPainter.draw(canvas, labelBounds, currentActionText)
        val centerY = height / 2f
        if (hasActionChip) {
            drawIconChip(canvas, actionLeft, centerY, chipHeight, RuntimeIslandStyle.chipFill(paused)) { cx, cy, unit ->
                val glyph = if (action == IslandAction.OPEN)
                    if (paused) IslandAction.RESUME else IslandAction.TAKEOVER else action
                drawActionGlyph(canvas, cx, cy, unit, glyph, RuntimeIslandStyle.chipTint(paused))
            }
        }
        drawIconChip(canvas, stopLeft, centerY, chipHeight, RuntimeIslandStyle.NEUTRAL_CHIP_FILL) { cx, cy, unit ->
            drawStopGlyph(canvas, cx, cy, unit)
        }
        canvas.restoreToCount(save)
    }

    /** The hot path contains only the mascot and orbit, using resources owned by this painter. */
    @Synchronized
    internal fun drawIslandActivity(
        canvas: Canvas,
        layout: IslandLayout,
        action: IslandAction,
        executing: Boolean,
        motionEnabled: Boolean,
        elapsedMs: Long
    ) {
        val padding = layout.paddingPx
        val height = layout.heightPx - padding * 2
        val centerY = height / 2f
        val save = canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        val toggleOffset = if (action == IslandAction.OPEN) RuntimeIslandStyle.SPACE_TOGGLE_WIDTH_DP / 2f else 0f
        drawMascot(canvas, centerY, elapsedMs, executing, motionEnabled, toggleOffset)
        if (executing && motionEnabled && action != IslandAction.NONE) {
            val chipHeight = dp(RuntimeIslandStyle.CHIP_SIZE_DP)
            val actionLeft = layout.stopButtonLeftPx - padding - dp(RuntimeIslandStyle.CHIP_GAP_DP) - chipHeight
            activityPainter.drawOrbit(canvas, actionLeft + chipHeight / 2f, centerY, density, elapsedMs)
        }
        canvas.restoreToCount(save)
    }

    private fun drawMascot(canvas: Canvas, centerY: Float, elapsedMs: Long, executing: Boolean,
            motionEnabled: Boolean, leftOffsetDp: Float) {
        val size = dpFloat(RuntimeIslandStyle.MASCOT_SIZE_DP)
        val left = dpFloat(RuntimeIslandStyle.MASCOT_LEFT_DP + leftOffsetDp)
        mascotBounds.set(left, centerY - size / 2f, left + size, centerY + size / 2f)
        mascotPainter.draw(canvas, mascotBounds, MascotFraming.FULL,
            if (executing) MascotMotion.loopingDancePose(elapsedMs, !motionEnabled) else MascotPose())
    }

    /** 岛宽定死，只有窄屏才收：左右各留 [RuntimeIslandStyle.SIDE_MIN_MARGIN_DP]。 */
    private fun islandWidthPx(): Int {
        val maxWidth = screenWidthPx() - dp(RuntimeIslandStyle.SIDE_MIN_MARGIN_DP) * 2
        return dp(RuntimeIslandStyle.WIDTH_DP).coerceAtMost(maxWidth)
    }

    /** 圆形图标按钮底座；glyph 拿到中心点与 1dp 单位，自己画线条。 */
    private fun drawIconChip(
        canvas: Canvas,
        left: Int,
        centerY: Float,
        size: Int,
        fill: String,
        glyph: (Float, Float, Float) -> Unit
    ) {
        val bounds = RectF(
            left.toFloat(),
            centerY - size / 2f,
            (left + size).toFloat(),
            centerY + size / 2f
        )
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(fill)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bounds, size / 2f, size / 2f, background)
        glyph(bounds.centerX(), bounds.centerY(), density)
    }

    /**
     * 主按钮图标：OPEN = 对角展开箭头（打开镜像），TAKEOVER = 暂停条（我来操作），
     * RESUME = 播放三角（把控制交回 agent）。语义靠形状表达，不再依赖文案长度。
     */
    private fun drawActionGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        action: IslandAction,
        tint: Int
    ) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = unit * 1.3f * RuntimeIslandStyle.SCALE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (action) {
            IslandAction.NONE -> {
                // 驻留态：主按钮无动作，只保留停止按钮。
            }
            IslandAction.OPEN -> {
                val arm = unit * 4.4f * RuntimeIslandStyle.SCALE
                val tip = unit * 2.2f * RuntimeIslandStyle.SCALE
                canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, stroke)
                val upper = Path()
                upper.moveTo(cx + arm - tip, cy - arm)
                upper.lineTo(cx + arm, cy - arm)
                upper.lineTo(cx + arm, cy - arm + tip)
                canvas.drawPath(upper, stroke)
                val lower = Path()
                lower.moveTo(cx - arm + tip, cy + arm)
                lower.lineTo(cx - arm, cy + arm)
                lower.lineTo(cx - arm, cy + arm - tip)
                canvas.drawPath(lower, stroke)
            }
            IslandAction.TAKEOVER -> {
                val half = unit * RuntimeIslandStyle.STOP_GLYPH_SIZE_DP / 2f
                val gap = unit * 1.6f * RuntimeIslandStyle.SCALE
                stroke.strokeWidth = unit * 1.8f * RuntimeIslandStyle.SCALE
                canvas.drawLine(cx - gap, cy - half, cx - gap, cy + half, stroke)
                canvas.drawLine(cx + gap, cy - half, cx + gap, cy + half, stroke)
            }
            IslandAction.RESUME -> {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tint
                    style = Paint.Style.FILL
                }
                val half = unit * 3.6f * RuntimeIslandStyle.SCALE
                val triangle = Path()
                triangle.moveTo(cx - half * 0.62f, cy - half)
                triangle.lineTo(cx + half * 0.9f, cy)
                triangle.lineTo(cx - half * 0.62f, cy + half)
                triangle.close()
                canvas.drawPath(triangle, fill)
            }
        }
    }

    /** 停止图标：iOS 那种圆角方块。 */
    private fun drawStopGlyph(canvas: Canvas, cx: Float, cy: Float, unit: Float) {
        val half = unit * RuntimeIslandStyle.STOP_GLYPH_SIZE_DP / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(RuntimeIslandStyle.STOP_TINT)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(cx - half, cy - half, cx + half, cy + half),
            unit * RuntimeIslandStyle.STOP_GLYPH_RADIUS_DP,
            unit * RuntimeIslandStyle.STOP_GLYPH_RADIUS_DP,
            paint
        )
    }

    fun pointerBitmapPng(): ByteArray = pointerPng

    /** 主屏小手位图：与虚拟屏预览层共用 [HandCursorBitmap] 的同一张 PNG 素材。 */
    private fun renderPointerBitmap(): ByteArray =
        HandCursorBitmap.of(context, dp(POINTER_SIZE_DP)).toPng()

    // sidecar 贴图用的是物理屏幕坐标，所以取整块屏幕而不是应用可用区域。
    internal fun screenBounds(): android.graphics.Rect {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val size = android.util.DisplayMetrics()
        display.getRealMetrics(size)
        return android.graphics.Rect(0, 0, size.widthPixels, size.heightPixels)
    }

    private fun screenWidthPx(): Int = screenBounds().width()

    private fun screenHeightPx(): Int = screenBounds().height()

    private fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
    }

    private fun dp(value: Float): Int = max(1, (value * density).toInt())
    private fun dpFloat(value: Float): Float = value * density

    private companion object {
        /** 主屏小手位图边长；sidecar 按 (0.28w, 0.20h) 当指尖热点贴到落点上。 */
        const val POINTER_SIZE_DP = 36f

        const val ISLAND_SHADOW_PADDING_DP = 12f
    }
}
