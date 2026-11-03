// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.view.View

/** Draws on the windowless host's UI thread, independently of sidecar commands and Activity state. */
internal open class RuntimeIslandSurfaceView(context: Context) : View(context) {
    // Each host owns its painter: bitmap exports cannot contend with live drawing for its lock.
    private val painter = RuntimeSurfaceBitmapRenderer(context)
    private var islandLayout = painter.islandLayout()
    private var label = ""
    private var action = RuntimeSurfaceBitmapRenderer.IslandAction.NONE
    private var paused = false
    private var collapsed = false
    private var state = RuntimeIslandPresentation.State.READY
    private var animationStartedAtMs = SystemClock.uptimeMillis()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var staticLayer: Bitmap? = null
    private var interactionActive = false
    private var motionEnabled = RuntimeIslandAnimations.areEnabled(context)
    private var nextMotionCheckMs = 0L

    fun present(
        label: String,
        action: RuntimeSurfaceBitmapRenderer.IslandAction,
        paused: Boolean,
        state: RuntimeIslandPresentation.State,
        collapsed: Boolean = false
    ) {
        val wasExecuting = isExecuting()
        val staticChanged = this.label != label || this.action != action || this.paused != paused ||
            this.collapsed != collapsed
        this.label = label
        this.action = action
        this.paused = paused
        this.state = state
        this.collapsed = collapsed
        if (isExecuting() && !wasExecuting) animationStartedAtMs = SystemClock.uptimeMillis()
        if (staticChanged || staticLayer == null) rebuildStaticLayer()
        updateFrameRatePreference()
        contentDescription = if (collapsed) context.getString(com.droidance.R.string.runtime_island_expand) else label
        invalidate()
    }

    private fun isExecuting() = state == RuntimeIslandPresentation.State.EXECUTING && !paused

    fun setInteractionActive(active: Boolean) {
        interactionActive = active
        updateFrameRatePreference()
        invalidate()
    }

    private fun updateFrameRatePreference() {
        if (Build.VERSION.SDK_INT >= 35) {
            setRequestedFrameRate(if (interactionActive || (isExecuting() && motionEnabled))
                REQUESTED_FRAME_RATE_CATEGORY_HIGH else REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
    }

    private fun rebuildStaticLayer() {
        if (islandLayout.widthPx <= 0 || islandLayout.heightPx <= 0) return
        Trace.beginSection("DroidanceIsland.static")
        try {
            val image = Bitmap.createBitmap(islandLayout.widthPx, islandLayout.heightPx, Bitmap.Config.ARGB_8888)
            image.density = Bitmap.DENSITY_NONE
            if (collapsed) painter.drawCollapsedStatic(Canvas(image), islandLayout)
            else painter.drawIslandStatic(Canvas(image), islandLayout, label, action, paused)
            // RenderThread may still reference the previous bitmap; let it retain that buffer safely.
            staticLayer = image
        } finally {
            Trace.endSection()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val measured = painter.islandLayout(collapsed)
        islandLayout = measured.copy(widthPx = w, heightPx = h,
            stopButtonLeftPx = measured.stopButtonLeftPx + w - measured.widthPx)
        rebuildStaticLayer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (now >= nextMotionCheckMs) {
            motionEnabled = RuntimeIslandAnimations.areEnabled(context)
            nextMotionCheckMs = now + MOTION_POLL_INTERVAL_MS
            updateFrameRatePreference()
        }
        val animated = isExecuting() && motionEnabled
        val elapsedMs = if (animated) now - animationStartedAtMs else 0L
        Trace.beginSection("DroidanceIsland.draw")
        try {
            staticLayer?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
            if (collapsed) painter.drawCollapsedActivity(canvas, islandLayout, isExecuting(), motionEnabled, elapsedMs)
            else painter.drawIslandActivity(canvas, islandLayout, action, isExecuting(), motionEnabled, elapsedMs)
        } finally {
            Trace.endSection()
        }
        if (isExecuting() && isAttachedToWindow && isShown && windowVisibility == VISIBLE) {
            // Poll reduced-motion changes quietly; never park execution permanently on a still frame.
            if (animated) postInvalidateOnAnimation() else postInvalidateDelayed(MOTION_POLL_INTERVAL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (staticLayer == null) rebuildStaticLayer()
        nextMotionCheckMs = 0L
        updateFrameRatePreference()
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDetachedFromWindow() {
        staticLayer = null
        interactionActive = false
        if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MOTION_POLL_INTERVAL_MS = 250L
    }
}
