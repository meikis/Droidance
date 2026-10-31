// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.agent.device

import com.droidance.core.agent.device.*
import com.droidance.core.knowledge.agent.device.*
import com.droidance.core.device.agent.device.*

import com.droidance.agent.device.*

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class PhysicalDisplaySnapshot(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotationDegrees: Int
)

internal fun captureDisplay(context: Context, displayId: Int): PhysicalDisplaySnapshot {
    val display = checkNotNull(
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
    ) { "Display $displayId is unavailable." }
    val metrics = context.createDisplayContext(display).resources.displayMetrics
    return PhysicalDisplaySnapshot(
        width = metrics.widthPixels.coerceAtLeast(1),
        height = metrics.heightPixels.coerceAtLeast(1),
        densityDpi = metrics.densityDpi.coerceAtLeast(1),
        rotationDegrees = rotationDegrees(display.rotation)
    )
}

internal fun rotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

internal fun resolveDisplaySnapshot(
    displayId: Int,
    physicalDisplay: PhysicalDisplaySnapshot?,
    virtualDisplay: VirtualDisplayMetrics?
): PhysicalDisplaySnapshot = if (displayId == 0) {
    checkNotNull(physicalDisplay) { "Default display is unavailable." }
} else {
    val metrics = checkNotNull(virtualDisplay?.takeIf { it.displayId == displayId }) {
        "Virtual display $displayId is unavailable from the active backend session."
    }
    PhysicalDisplaySnapshot(
        width = metrics.width.coerceAtLeast(1),
        height = metrics.height.coerceAtLeast(1),
        densityDpi = metrics.densityDpi.coerceAtLeast(1),
        rotationDegrees = metrics.rotationDegrees
    )
}

@Singleton
class DisplaySnapshotResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val virtualSpaceManager: VirtualSpaceManager
) {
    internal fun capture(displayId: Int): PhysicalDisplaySnapshot = runCatching {
        resolveDisplaySnapshot(
            displayId = displayId,
            physicalDisplay = if (displayId == 0) captureDisplay(context, displayId) else null,
            virtualDisplay = if (displayId == 0) null else virtualSpaceManager.activeDisplayMetrics()
        )
    }.getOrElse {
        captureDisplay(context, 0)
    }
}
