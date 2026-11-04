// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

/** Show external task status only outside the host app and its expanded viewer. */
object RuntimeOverlayVisibility {
    fun shouldShow(active: Boolean, mobileUse: Boolean,
        hostForeground: Boolean, viewerExpanded: Boolean, permissionWaiting: Boolean): Boolean =
        active && mobileUse && !hostForeground && !viewerExpanded && !permissionWaiting
}

/** Activity identity prevents an old pause/stop callback from clearing a newly resumed host. */
internal class RuntimeForegroundActivities<T> {
    private val resumedOnMainDisplay = mutableSetOf<T>()

    fun resume(activity: T, onMainDisplay: Boolean): Boolean {
        if (onMainDisplay) resumedOnMainDisplay.add(activity) else resumedOnMainDisplay.remove(activity)
        return resumedOnMainDisplay.isNotEmpty()
    }

    fun remove(activity: T): Boolean {
        resumedOnMainDisplay.remove(activity)
        return resumedOnMainDisplay.isNotEmpty()
    }
}
