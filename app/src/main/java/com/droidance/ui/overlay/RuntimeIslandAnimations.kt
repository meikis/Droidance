// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings

/** Windowless external surfaces also honor the system animation setting. */
internal object RuntimeIslandAnimations {
    fun areEnabled(context: Context): Boolean =
        ValueAnimator.areAnimatorsEnabled() && Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
}
