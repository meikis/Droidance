// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/** Shared timing for small controls, disclosure and content changes. */
internal object AppMotion {
    const val PressMillis = 90
    const val FeedbackMillis = 140
    const val ContentMillis = 200
    const val PageMillis = 260
    const val CopyFeedbackMillis = 2_000L
    val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> controlSpring(): SpringSpec<T> = spring(stiffness = 600f, dampingRatio = 0.95f)
}
