// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import com.droidance.core.device.tools.InputAction

/**
 * Pure coordinate mapping for the human-takeover panel.
 *
 * The panel shows the virtual display's latest PNG inside an ImageView with
 * [android.widget.ImageView.ScaleType.FIT_CENTER], so the image is letterboxed with black
 * bars on the axis that does not fill the view. Touches inside a black bar have no
 * corresponding pixel on the virtual display and must be rejected; touches inside the image
 * content map back to virtual-display pixels once.
 *
 * The original DOWN/MOVE/UP stream is preserved after this one coordinate transform.
 */
object VirtualDisplayTouchMapper {

    data class FramePoint(val x: Int, val y: Int)

    /**
     * Map a view-space touch to virtual-display frame pixels under FIT_CENTER, or return null
     * when the touch lands in a letterbox black bar (no underlying pixel).
     */
    fun mapViewToFrame(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        frameWidth: Int,
        frameHeight: Int
    ): FramePoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null

        val scale = minOf(
            viewWidth.toFloat() / frameWidth.toFloat(),
            viewHeight.toFloat() / frameHeight.toFloat()
        )
        val contentWidth = frameWidth * scale
        val contentHeight = frameHeight * scale
        val offsetX = (viewWidth - contentWidth) / 2f
        val offsetY = (viewHeight - contentHeight) / 2f

        val relX = viewX - offsetX
        val relY = viewY - offsetY
        if (relX < 0f || relY < 0f || relX > contentWidth || relY > contentHeight) {
            return null
        }

        val frameX = (relX / scale).toInt().coerceIn(0, frameWidth - 1)
        val frameY = (relY / scale).toInt().coerceIn(0, frameHeight - 1)
        return FramePoint(frameX, frameY)
    }

    data class ViewPoint(val x: Float, val y: Float)

    /**
     * Inverse of [mapViewToDisplay] for feedback drawn above the preview: physical
     * virtual-display pixels to view space under FIT_CENTER. The hand cursor lives in the App
     * preview instead of the virtual display itself, so the display frames the model sees stay
     * free of Droidance's own pixels.
     */
    fun mapDisplayToView(
        displayX: Int,
        displayY: Int,
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int
    ): ViewPoint? {
        if (viewWidth <= 0 || viewHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return null

        val scale = minOf(
            viewWidth.toFloat() / displayWidth.toFloat(),
            viewHeight.toFloat() / displayHeight.toFloat()
        )
        val offsetX = (viewWidth - displayWidth * scale) / 2f
        val offsetY = (viewHeight - displayHeight * scale) / 2f
        return ViewPoint(
            x = offsetX + displayX * scale,
            y = offsetY + displayY * scale
        )
    }

    /**
     * Rewrite an executed virtual-display action into preview-view coordinates so the shared
     * hand indicator can replay it above the mirror. Returns null when the preview geometry is
     * not measurable yet.
     */
    fun mapActionToView(
        action: InputAction,
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int
    ): InputAction? = when (action) {
        is InputAction.Tap -> mapDisplayToView(
            action.x,
            action.y,
            viewWidth,
            viewHeight,
            displayWidth,
            displayHeight
        )?.let { InputAction.Tap(it.x.toInt(), it.y.toInt()) }

        is InputAction.Swipe -> {
            val start = mapDisplayToView(
                action.startX,
                action.startY,
                viewWidth,
                viewHeight,
                displayWidth,
                displayHeight
            )
            val end = mapDisplayToView(
                action.endX,
                action.endY,
                viewWidth,
                viewHeight,
                displayWidth,
                displayHeight
            )
            if (start == null || end == null) {
                null
            } else {
                InputAction.Swipe(
                    startX = start.x.toInt(),
                    startY = start.y.toInt(),
                    endX = end.x.toInt(),
                    endY = end.y.toInt(),
                    durationMs = action.durationMs
                )
            }
        }
    }

    /**
     * Map through a downscaled FIT_CENTER preview into physical virtual-display coordinates.
     */
    fun mapViewToDisplay(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        displayWidth: Int,
        displayHeight: Int
    ): FramePoint? {
        if (displayWidth <= 0 || displayHeight <= 0) return null
        val previewPoint = mapViewToFrame(
            viewX,
            viewY,
            viewWidth,
            viewHeight,
            previewWidth,
            previewHeight
        ) ?: return null
        return FramePoint(
            x = (previewPoint.x.toLong() * displayWidth / previewWidth)
                .toInt()
                .coerceIn(0, displayWidth - 1),
            y = (previewPoint.y.toLong() * displayHeight / previewHeight)
                .toInt()
                .coerceIn(0, displayHeight - 1)
        )
    }
}
